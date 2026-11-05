#!/usr/bin/env python3
"""Start/stop this checkout's isolated Dev stack. Never changes the device or live console."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import secrets
import shutil
import tempfile
import signal
import socket
import subprocess
import time
import urllib.error
import urllib.request
import zipfile
import re
import shlex
from urllib.parse import urlsplit

DEV = Path(__file__).resolve().parent
ROOT = DEV.parent
RUNTIME = DEV / ".runtime"
WEB = ROOT / "main/manager-web"
CANDIDATE = WEB
ANNOTATION_DEFAULT_FONT = ROOT / "assets/fonts/noto_qwen.ttf"
CANDIDATE_URL = "http://127.0.0.1:18894"
API = ROOT / "main/manager-api"
WEB_URL = CANDIDATE_URL
API_URL = "http://127.0.0.1:18893/xiaozhi"
SPEECH_DEFAULT_PROFILE = ("cosyvoice-v3.5-flash", "longantai_v3")
SPEECH_PROFILES = (SPEECH_DEFAULT_PROFILE, ("cosyvoice-v2", "longxiaochun_v2"))


def speech_worker_profile():
    """Keep a provisioned worker and manager on the same model during rollout."""
    path = RUNTIME / "answer-speech-worker.json"
    if not path.exists():
        return SPEECH_DEFAULT_PROFILE
    try:
        if not path.is_file() or path.stat().st_mode & 0o077:
            raise ValueError()
        worker = json.loads(path.read_text())
        profile = (worker["model"], worker["voice"])
        if profile not in SPEECH_PROFILES:
            raise ValueError()
        return profile
    except (OSError, ValueError, TypeError, KeyError):
        raise RuntimeError("Invalid private answer speech profile") from None


def esp32_intents_enabled():
    path = RUNTIME / "esp32-intents-settings.json"
    if not path.exists():
        return False
    settings = json.loads(path.read_text())
    if not isinstance(settings, dict) or not isinstance(settings.get("enabled"), bool):
        raise RuntimeError("Invalid ESP32 keyword settings")
    return settings["enabled"]


def configure():
    os.umask(0o077)
    RUNTIME.mkdir(exist_ok=True, mode=0o700)
    path = RUNTIME / "credentials.json"
    if not path.exists():
        path.write_text(json.dumps({"root": secrets.token_hex(24), "app": secrets.token_hex(24)}))
    credentials = json.loads(path.read_text())
    (RUNTIME / "compose.env").write_text(
        f"MYSQL_ROOT_PASSWORD={credentials['root']}\nMYSQL_APP_PASSWORD={credentials['app']}\n")
    properties = {
        "server.tomcat.threads.max": 64,
        "server.tomcat.threads.min-spare": 4,
        "spring.datasource.druid.url": "jdbc:mysql://mysql:3306/xiaozhi_esp32_server?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&nullCatalogMeansCurrent=true&allowPublicKeyRetrieval=true&useSSL=false",
        "spring.datasource.druid.username": "xiaozhi",
        "spring.datasource.druid.password": credentials['app'],
        "spring.datasource.druid.initial-size": 2,
        "spring.datasource.druid.min-idle": 2,
        "spring.datasource.druid.max-active": 12,
        # Validate pooled Dev connections before use; never replay a failed write.
        "spring.datasource.druid.test-on-borrow": "true",
        "spring.datasource.druid.validation-query": "SELECT 1",
        "spring.datasource.druid.validation-query-timeout": 2,
        "spring.datasource.druid.max-wait": 3000,
        "spring.data.redis.host": "redis",
        "spring.data.redis.port": 6379,
        "features.conversation-enabled": "true",
        "features.support-handoff-enabled": "true",
        "logging.level.xiaozhi": "INFO",
        "logging.level.liquibase": "WARN",
        "model-test.audio-dir": "/app/attachments/model-tests",
    }
    settings = ai_settings()
    if settings.get("enabled") is True:
        key = (RUNTIME / "ai-worker-key").read_text().strip()
        model = settings.get("model", "")
        if not key or not model or any(c in key + model for c in "\r\n"):
            raise RuntimeError("Invalid AI configuration")
        properties.update({"features.ai-text-enabled": "true", "ai.worker.key": key,
                           "ai.execution.model": model})
        omni = settings.get("omni_model", "")
        if omni:
            if omni != "qwen3.8-omni-flash":
                raise RuntimeError("Unsupported Omni model")
            properties["ai.execution.omni-model"] = omni
    tools = agent_tools_settings()
    first = ai_first_settings()
    first_active = first.get("enabled") is True and tools.get("enabled") is True and settings.get("enabled") is True
    if tools.get("enabled") is True and settings.get("enabled") is True:
        properties["features.agent-tools-enabled"] = "true"
        if tools.get("technical_local") is True or first_active and first.get("technical_local") is True:
            properties["agent-tools.technical-local-enabled"] = "true"
    if first_active:
        properties.update({"features.ai-context-enabled": "true", "features.agent-tool-results-enabled": "true"})
        models = ai_first_models()
        if models:
            properties["ai.execution.multimodal-tools-enabled"] = "true"
            properties["ai.execution.multimodal-tools-models"] = ",".join(models)
        if first.get("realtime_tools") is True and realtime_settings().get("enabled") is True:
            properties["features.realtime-tools-enabled"] = "true"
        if first.get("materials_enabled") is True and realtime_settings().get("enabled") is True:
            prepared = ai_first_materials_worker_config()
            current = json.loads((RUNTIME / "realtime-worker.json").read_text())
            if any(current.get(key) != prepared[key] for key in ("perception_model", "perception_base_url", "perception_api_key", "perception_workspace_id")):
                raise RuntimeError("Realtime materials configuration changed; rerun ai-first-enable --materials")
            properties["features.realtime-materials-enabled"] = "true"
            properties["realtime.materials.perception-model"] = prepared["perception_model"]
    if first.get("enabled") is True and first.get("runtime_control_enabled") is True:
        properties["ai.runtime-control.enabled"] = "true"
    if first_active and first.get("skills_enabled") is True:
        properties["features.ai-skills-enabled"] = "true"
    if first.get("enabled") is True and first.get("speech_enabled") is True:
        key_path = RUNTIME / "answer-speech-worker-key"
        try:
            if key_path.stat().st_mode & 0o077:
                raise ValueError()
            speech_key = key_path.read_text().strip()
            if not re.fullmatch(r"[a-f0-9]{64}", speech_key):
                raise ValueError()
        except (OSError, ValueError):
            raise RuntimeError("Answer speech requires its private dedicated worker key") from None
        speech_model, speech_voice = speech_worker_profile()
        properties.update({"features.answer-speech-enabled": "true", "answer-speech.worker.key": speech_key,
                           "answer-speech.storage-root": "/app/attachments/answer-speech",
                           "answer-speech.model": speech_model, "answer-speech.voice": speech_voice})
    if summary_settings().get("enabled") is True and settings.get("enabled") is True:
        properties["features.support-summary-assistance-enabled"] = "true"
    media = media_settings()
    if media.get("enabled") is True:
        worker_key = (RUNTIME / "media-worker-key").read_text().strip()
        ticket_key = (RUNTIME / "media-ticket-key").read_text().strip()
        if any(len(key) < 32 or any(c in key for c in "\r\n") for key in (worker_key, ticket_key)):
            raise RuntimeError("Invalid media private keys")
        properties.update({"features.attachments-enabled": "true",
                           "media.worker.key": worker_key,
                           "media.ticket-key": ticket_key,
                           "media.storage.type": media.get("storage", "filesystem"),
                           "media.storage.root": "/app/attachments",
                           "media.transcription-enabled": str(media.get("transcription", False)).lower()})
        if media.get("storage") == "s3":
            storage = json.loads((RUNTIME / "media-storage.json").read_text())
            for key in ("endpoint", "region", "bucket", "prefix", "access-key", "secret-key"):
                value = storage[key]
                if not isinstance(value, str) or not value or any(c in value for c in "\r\n"):
                    raise RuntimeError("Invalid private media storage configuration")
                properties["media.storage.s3." + key] = value
        if media.get("annotations_enabled") is True:
            properties["features.annotations-enabled"] = "true"
        if media.get("vision_model"):
            properties["ai.execution.vision-model"] = media["vision_model"]
    rtc = rtc_settings()
    if rtc.get("room-prefix"):
        validate_rtc(rtc)
        for name in ("internal-url", "public-url", "api-key", "api-secret", "room-prefix"):
            properties["support-media.livekit." + name] = rtc[name]
    if rtc.get("enabled") is True:
        if not rtc.get("room-prefix"):
            raise RuntimeError("RTC enabled without private configuration")
        properties["features.support-media-enabled"] = "true"
    realtime = realtime_settings()
    if realtime.get("enabled") is True:
        validate_realtime_settings(realtime)
        properties.update({"features.realtime-ai-enabled": "true",
                           "realtime.worker.key": realtime["worker_key"],
                           "realtime.worker.public-url": realtime["public_url"]})
        if realtime.get("server_vad_enabled") is True:
            properties["features.realtime-server-vad-enabled"] = "true"
    if esp32_intents_enabled():
        properties["features.esp32-intents-enabled"] = "true"
    device_settings = RUNTIME / "esp32-device-settings.json"
    if device_settings.exists():
        if device_settings.stat().st_mode & 0o077:
            raise RuntimeError("ESP32 device settings must be private")
        device = json.loads(device_settings.read_text())
        if device.get("enabled") is True:
            for name in ("gateway_key", "download_key"):
                if not isinstance(device.get(name), str) or len(device[name]) < 32 or any(c in device[name] for c in "\r\n"):
                    raise RuntimeError("Invalid ESP32 service key")
            properties.update({
                "features.esp32-enabled": "true", "features.esp32-ota-enabled": "true",
                "esp32.gateway.key": device["gateway_key"],
                "esp32.ota.download-key": device["download_key"],
                "esp32.ota.directory": "/app/esp32-firmware",
                "esp32.ota.public-base-url": device["public_base_url"],
                "esp32.ota.allow-lan-http": "true", "esp32.ota.development-only": "true",
                "esp32.ota.usb-bootstrap-enabled": "true",
            })
    (RUNTIME / "application-dev.properties").write_text("\n".join(f"{key}={value}" for key, value in properties.items()) + "\n")
    for name in ("credentials.json", "compose.env", "application-dev.properties"):
        (RUNTIME / name).chmod(0o600)
    for name in ("logs", "uploadfile", "attachments"):
        (RUNTIME / name).mkdir(exist_ok=True)


def compose(*args, **kwargs):
    return subprocess.run(["docker", "compose", "--env-file", str(RUNTIME / "compose.env"),
                           "-f", str(DEV / "compose.yml"), *args], check=True, **kwargs)


def ai_settings():
    path = RUNTIME / "ai-settings.json"
    if not path.exists():
        return {"enabled": False}
    data = json.loads(path.read_text())
    if not isinstance(data, dict) or not isinstance(data.get("enabled"), bool):
        raise RuntimeError("Invalid AI settings")
    return data


def agent_tools_settings():
    path = RUNTIME / "agent-tools-settings.json"
    if not path.exists():
        return {"enabled": False, "technical_local": False}
    data = json.loads(path.read_text())
    if (not isinstance(data, dict) or type(data.get("enabled")) is not bool
            or type(data.get("technical_local")) is not bool):
        raise RuntimeError("Invalid agent tools settings")
    return data


def agent_tools_jar_check():
    markers = {"BOOT-INF/classes/xiaozhi/modules/agenttool/AgentToolService.class",
               "BOOT-INF/classes/xiaozhi/modules/ai/AiProviderUsageStore.class",
               "BOOT-INF/classes/db/changelog/agent-tools.changelog.yaml"}
    try:
        with zipfile.ZipFile(API / "target/xiaozhi-esp32-api.jar") as archive:
            if not markers.issubset(set(archive.namelist())):
                raise RuntimeError("Agent tools require a freshly built API")
    except (OSError, zipfile.BadZipFile):
        raise RuntimeError("Agent tools require a freshly built API") from None


def prepare_agent_tools(technical_local=False):
    if type(technical_local) is not bool:
        raise RuntimeError("Invalid technical tool setting")
    agent_tools_jar_check()
    if ai_settings().get("enabled") is not True:
        raise RuntimeError("Enable the configured AI worker before agent tools")
    python = os.environ.get("XIAOZHI_AI_WORKER_PYTHON", str(ROOT.parent / ".venv/bin/python"))
    try:
        subprocess.run([python, "-c", "import openai, httpx, ai_worker.tools"], cwd=ROOT / "main/xiaozhi-server", check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError):
        raise RuntimeError("Agent tools require the updated AI worker environment") from None
    config = RUNTIME / "ai-worker.json"
    if not config.is_file() or config.stat().st_mode & 0o077:
        raise RuntimeError("Agent tools require a private AI worker configuration")
    private_json("agent-tools-settings.json", {"enabled": True, "technical_local": technical_local})


def ai_first_settings():
    path = RUNTIME / "ai-first-settings.json"
    if not path.exists():
        return {"enabled": False, "technical_local": False, "realtime_tools": False, "materials_enabled": False, "speech_enabled": False, "skills_enabled": False, "runtime_control_enabled": False}
    data = json.loads(path.read_text())
    if not isinstance(data, dict) or any(type(data.get(key)) is not bool for key in ("enabled", "technical_local", "realtime_tools")):
        raise RuntimeError("Invalid AI-first settings")
    if any(type(data.get(key, False)) is not bool for key in ("materials_enabled", "speech_enabled", "skills_enabled", "runtime_control_enabled")):
        raise RuntimeError("Invalid AI-first settings")
    return {**data, **{key: data.get(key, False) for key in ("materials_enabled", "speech_enabled", "skills_enabled", "runtime_control_enabled")}}


def ai_first_models():
    media = media_settings()
    vision = media.get("vision_model", "") if media.get("enabled") is True else ""
    omni = ai_settings().get("omni_model", "")
    if vision not in ("", "qwen3.8-flash", "qwen3-vl-plus", "qwen3-vl-flash"):
        raise RuntimeError("Configured vision model is not approved for multimodal tools")
    if omni not in ("", "qwen3.8-omni-flash"):
        raise RuntimeError("Configured audio model is not approved for multimodal tools")
    return list(dict.fromkeys(model for model in (vision, omni) if model))


def ai_first_jar_check():
    markers = {"BOOT-INF/classes/xiaozhi/modules/agenttool/AgentToolExecutionCore.class",
               "BOOT-INF/classes/xiaozhi/modules/agenttool/PublicToolResultStore.class",
               "BOOT-INF/classes/xiaozhi/modules/aicontext/ConversationEvidenceService.class",
               "BOOT-INF/classes/xiaozhi/modules/realtimetool/RealtimeToolService.class",
               "BOOT-INF/classes/db/changelog/agent-tools.changelog.yaml",
               "BOOT-INF/classes/db/changelog/ai-context.changelog.yaml",
               "BOOT-INF/classes/db/changelog/ai-public-results.changelog.yaml",
               "BOOT-INF/classes/db/changelog/realtime-tools.changelog.yaml"}
    try:
        with zipfile.ZipFile(API / "target/xiaozhi-esp32-api.jar") as archive:
            if not markers.issubset(set(archive.namelist())):
                raise RuntimeError("AI-first requires a freshly built API")
    except (OSError, zipfile.BadZipFile):
        raise RuntimeError("AI-first requires a freshly built API") from None


def ai_first_gateway_preflight():
    """Read this Dev database before an API upgrade that includes gateway triggers."""
    jar = API / "target/xiaozhi-esp32-api.jar"
    migration = "BOOT-INF/classes/db/changelog/esp32-gateway.changelog.yaml"
    master = "BOOT-INF/classes/db/changelog/db.changelog-master.yaml"
    try:
        with zipfile.ZipFile(jar) as archive:
            if migration not in archive.namelist():
                return
            # Only an included migration can run during this upgrade.
            if master not in archive.namelist():
                raise RuntimeError("AI-first preflight cannot verify the API migration manifest")
            if not re.search(rb"(?m)^\s*file:\s*esp32-gateway\.changelog\.yaml\s*$", archive.read(master)):
                return
            strict_id = "202611021102-esp32-gateway-strict-revocation"
            strict_present = bool(re.search(
                rb"(?m)^\s*id:\s*202611021102-esp32-gateway-strict-revocation\s*$",
                archive.read(migration),
            ))
    except (OSError, zipfile.BadZipFile, KeyError):
        raise RuntimeError("AI-first preflight cannot read the API migration manifest") from None
    if not (RUNTIME / "compose.env").is_file():
        raise RuntimeError("AI-first preflight requires the existing Dev stack and database; start Dev first")
    migrations = ["202611020700-esp32-gateway"]
    if strict_present:
        migrations.append(strict_id)
    counts = ["(SELECT COUNT(*) FROM DATABASECHANGELOG WHERE ID='" + migration_id +
              "' AND AUTHOR='codex' AND EXECTYPE IN ('EXECUTED','RERAN','MARK_RAN'))"
              for migration_id in migrations]
    sql = "SELECT @@GLOBAL.log_bin, @@GLOBAL.log_bin_trust_function_creators, " + ", ".join(counts)
    try:
        result = compose("exec", "-T", "mysql", "sh", "-c",
                         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw --skip-column-names xiaozhi_esp32_server -e "$1"',
                         "--", sql, capture_output=True, text=True)
    except (OSError, subprocess.SubprocessError):
        raise RuntimeError("AI-first preflight cannot read the existing Dev database or migration history; verify the dedicated Dev MySQL is running and initialized") from None
    fields = result.stdout.strip().split("\t")
    if len(fields) != 2 + len(migrations) or any(not re.fullmatch(r"[0-9]+", field) for field in fields) or fields[0] not in ("0", "1") or fields[1] not in ("0", "1"):
        raise RuntimeError("AI-first preflight received an invalid Dev MySQL migration status")
    binlog, trust, *applied = map(int, fields)
    if any(not count for count in applied) and binlog and not trust:
        raise RuntimeError("AI-first cannot apply the pending esp32-gateway trigger migration: Dev MySQL binary logging is enabled and log_bin_trust_function_creators is disabled. Arrange a reviewed, temporary Dev-only migration operation with the database administrator; no services or AI-first settings were changed.")


def ai_first_optional_jar_check(feature):
    definitions = {
        "speech": ("answerspeech", ("AnswerSpeechService", "AnswerSpeechController", "AnswerSpeechWorkerController", "AnswerSpeechWorkerKeyFilter"), "answer-speech.changelog.yaml"),
        "skills": ("aiskill", ("AiSkillService", "AiSkillController"), "ai-skills.changelog.yaml"),
        "runtime-control": ("airuntime", ("AiRuntimeControl", "AiRuntimeControlController"), "ai-runtime-control.changelog.yaml"),
    }
    module, classes, migration = definitions[feature]
    root = "BOOT-INF/classes/"
    markers = {root + "xiaozhi/modules/" + module + "/" + name + ".class" for name in classes}
    markers.add(root + "db/changelog/" + migration)
    master = root + "db/changelog/db.changelog-master.yaml"
    try:
        with zipfile.ZipFile(API / "target/xiaozhi-esp32-api.jar") as archive:
            if not markers.issubset(set(archive.namelist())) or master not in archive.namelist():
                raise ValueError()
            pattern = rb"(?m)^\s*file:\s*" + re.escape(migration.encode()) + rb"\s*$"
            if not re.search(pattern, archive.read(master)):
                raise ValueError()
    except (OSError, zipfile.BadZipFile, KeyError, ValueError):
        raise RuntimeError("AI-first " + feature + " requires a freshly built API with its migration") from None


def ai_first_materials_jar_check():
    root = "BOOT-INF/classes/"
    markers = {root + "xiaozhi/modules/realtimeinput/" + name + ".class" for name in
               ("RealtimeInputService", "RealtimeInputController", "RealtimeInputWorkerController", "RealtimeInputModels")}
    markers.add(root + "db/changelog/realtime-inputs.changelog.yaml")
    master = root + "db/changelog/db.changelog-master.yaml"
    try:
        with zipfile.ZipFile(API / "target/xiaozhi-esp32-api.jar") as archive:
            if not markers.issubset(set(archive.namelist())) or master not in archive.namelist():
                raise ValueError()
            if not re.search(rb"(?m)^\s*file:\s*realtime-inputs\.changelog\.yaml\s*$", archive.read(master)):
                raise ValueError()
    except (OSError, zipfile.BadZipFile, KeyError, ValueError):
        raise RuntimeError("Realtime materials require a freshly built API with the realtime input migration") from None


def ai_first_materials_worker_config():
    """Prepare an in-memory private worker copy; never write during preflight."""
    try:
        source_path = RUNTIME / "ai-worker.json"
        target_path = RUNTIME / "realtime-worker.json"
        if source_path.stat().st_mode & 0o077 or target_path.stat().st_mode & 0o077:
            raise ValueError()
        source, target = json.loads(source_path.read_text()), json.loads(target_path.read_text())
        if not isinstance(source, dict) or not isinstance(target, dict):
            raise ValueError()
        model = source["vision_model"]
        base = source.get("vision_base_url") or source["base_url"]
        key = source.get("vision_api_key") or source["api_key"]
        workspace = source.get("vision_workspace_id") or source.get("workspace_id", "")
        if model != "qwen3.8-flash" or not isinstance(base, str) or not isinstance(key, str) or not key.strip() or not isinstance(workspace, str):
            raise ValueError()
        if any(ord(c) < 32 or ord(c) == 127 for c in key + workspace):
            raise ValueError()
        url = urlsplit(base)
        host = url.hostname or ""
        if (url.scheme != "https" or url.username or url.password or url.query or url.fragment or url.port not in (None, 443)
                or not (host in ("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com")
                        or re.fullmatch(r"[a-z0-9-]+\.(cn-beijing|ap-southeast-1)\.maas\.aliyuncs\.com", host))):
            raise ValueError()
        if workspace and not re.fullmatch(r"[a-zA-Z0-9-]{1,80}", workspace):
            raise ValueError()
        if host.endswith(".maas.aliyuncs.com") and workspace and workspace.lower() != host.split(".", 1)[0]:
            raise ValueError()
        return {**target, "perception_model": model, "perception_base_url": base,
                "perception_api_key": key, "perception_workspace_id": workspace}
    except (OSError, ValueError, TypeError, KeyError, AttributeError):
        raise RuntimeError("Realtime materials require private AI vision credentials for an approved Bailian model and HTTPS endpoint") from None


def prepare_ai_first(technical_local=False, materials=False, speech=False, skills=False, runtime_control=False):
    if type(runtime_control) is not bool:
        raise RuntimeError("Invalid AI-first runtime control setting")
    if type(speech) is not bool or type(skills) is not bool:
        raise RuntimeError("Invalid AI-first speech or skills setting")
    if type(materials) is not bool:
        raise RuntimeError("Invalid realtime materials setting")
    if type(technical_local) is not bool:
        raise RuntimeError("Invalid technical tool setting")
    ai_first_jar_check()
    if materials:
        ai_first_materials_jar_check()
    if speech:
        ai_first_optional_jar_check("speech")
    if skills:
        ai_first_optional_jar_check("skills")
    if runtime_control:
        ai_first_optional_jar_check("runtime-control")
    ai_first_models()
    if ai_settings().get("enabled") is not True or agent_tools_settings().get("enabled") is not True:
        raise RuntimeError("AI-first requires enabled AI and agent tools")
    path = RUNTIME / "ai-worker.json"
    if not path.is_file() or path.stat().st_mode & 0o077:
        raise RuntimeError("AI-first requires a private AI worker configuration")
    try:
        worker = json.loads(path.read_text())
        media = media_settings()
        vision = media.get("vision_model", "") if media.get("enabled") is True else ""
        omni = ai_settings().get("omni_model", "")
        if (not isinstance(worker, dict) or vision and worker.get("vision_model") != vision
                or omni and worker.get("omni_model") != omni):
            raise ValueError()
    except (OSError, ValueError, TypeError):
        raise RuntimeError("AI-first model configuration does not match the private worker") from None
    python = os.environ.get("XIAOZHI_AI_WORKER_PYTHON", str(ROOT.parent / ".venv/bin/python"))
    try:
        subprocess.run([python, "-c", "import openai, httpx, ai_worker.tools"], cwd=ROOT / "main/xiaozhi-server", check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError):
        raise RuntimeError("AI-first requires the updated AI worker environment") from None
    realtime = realtime_settings().get("enabled") is True
    if materials and not realtime:
        raise RuntimeError("Realtime materials require enabled realtime")
    if realtime:
        validate_realtime_settings(realtime_settings())
        realtime_interpreter = realtime_python()
        try:
            subprocess.run([realtime_interpreter, "-c", "import realtime_worker.session, realtime_worker.tool_contract"],
                           cwd=ROOT / "main/xiaozhi-server", check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except (OSError, subprocess.SubprocessError):
            raise RuntimeError("AI-first requires the updated realtime worker environment") from None
        validate_realtime_worker_file(realtime_settings())
    return {"enabled": True, "technical_local": technical_local, "realtime_tools": realtime, "materials_enabled": materials, "speech_enabled": speech, "skills_enabled": skills, "runtime_control_enabled": runtime_control}


def summary_settings():
    path = RUNTIME / "summary-settings.json"
    if not path.exists():
        return {"enabled": False}
    data = json.loads(path.read_text())
    if not isinstance(data, dict) or type(data.get("enabled")) is not bool:
        raise RuntimeError("Invalid summary assistance settings")
    return data


def summary_jar_check():
    marker = "BOOT-INF/classes/xiaozhi/modules/supportsummary/SupportAssistanceService.class"
    try:
        with zipfile.ZipFile(API / "target/xiaozhi-esp32-api.jar") as archive:
            if marker not in archive.namelist():
                raise RuntimeError("Summary assistance requires a freshly built API")
    except (OSError, zipfile.BadZipFile):
        raise RuntimeError("Summary assistance requires a freshly built API") from None


def prepare_summary():
    summary_jar_check()
    if ai_settings().get("enabled") is not True:
        raise RuntimeError("Enable the configured AI worker before summary assistance")
    summary_python()
    export_ai_config()
    private_json("summary-settings.json", {"enabled": True})


def summary_process():
    path = RUNTIME / "summary-worker-process.json"
    if not path.exists():
        return None
    try:
        info = json.loads(path.read_text())
        pid = info["pid"]
        if type(pid) is not int or pid <= 1 or not info["identity"] or process_identity(pid) != info["identity"]:
            return None
        command = subprocess.run(["ps", "-o", "command=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
        args = shlex.split(command)
        if not any(args[i:i + 2] == ["-m", "ai_worker.summary"] for i in range(len(args) - 1)):
            return None
        if not any(args[i:i + 2] == ["--config", str(RUNTIME / "ai-worker.json")] for i in range(len(args) - 1)):
            return None
        return info
    except (OSError, ValueError, KeyError, TypeError):
        return None


def summary_python():
    python = os.environ.get("XIAOZHI_AI_WORKER_PYTHON", str(ROOT.parent / ".venv/bin/python"))
    try:
        subprocess.run([python, "-c", "import openai, httpx, ai_worker.summary"], cwd=ROOT / "main/xiaozhi-server", check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError):
        raise RuntimeError("Summary assistance requires a prepared AI worker environment") from None
    return python


def start_summary_worker():
    if summary_process():
        return
    if summary_settings().get("enabled") is not True or ai_settings().get("enabled") is not True:
        raise RuntimeError("Summary assistance and AI must both be enabled")
    config = RUNTIME / "ai-worker.json"
    if not config.is_file() or config.stat().st_mode & 0o077:
        raise RuntimeError("Summary assistance requires the private AI worker configuration")
    python = summary_python()
    with (RUNTIME / "summary-worker.log").open("a") as log:
        process = subprocess.Popen([python, "-m", "ai_worker.summary", "--config", str(config)],
                                   cwd=ROOT / "main/xiaozhi-server", stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
    identity = process_identity(process.pid)
    if not identity:
        raise RuntimeError("Summary worker exited; inspect dev/.runtime/summary-worker.log")
    private_json("summary-worker-process.json", {"pid": process.pid, "identity": identity})


def stop_summary_worker():
    path = RUNTIME / "summary-worker-process.json"
    if not path.exists():
        return
    info = summary_process()
    if info:
        os.killpg(info["pid"], signal.SIGTERM)
        path.unlink()
        return
    stored = json.loads(path.read_text())
    if type(stored.get("pid")) is int and process_identity(stored["pid"]):
        raise RuntimeError("Summary worker process identity mismatch; refusing to stop")
    path.unlink()


def media_settings():
    path = RUNTIME / "media-settings.json"
    if not path.exists():
        return {"enabled": False}
    data = json.loads(path.read_text())
    if not isinstance(data, dict) or not isinstance(data.get("enabled"), bool):
        raise RuntimeError("Invalid media settings")
    return data


def rtc_settings():
    path = RUNTIME / "rtc-settings.json"
    if not path.exists():
        return {"enabled": False}
    data = json.loads(path.read_text())
    if not isinstance(data, dict) or not isinstance(data.get("enabled"), bool):
        raise RuntimeError("Invalid RTC settings")
    return data


def rtc_endpoint(value, schemes, docker_loopback=False):
    if not isinstance(value, str) or not value or any(ord(c) < 33 or ord(c) == 127 or c == "\\" for c in value):
        raise RuntimeError("Invalid RTC endpoint")
    try:
        parsed = urlsplit(value)
        if (parsed.scheme not in schemes or not parsed.hostname or parsed.username or parsed.password
                or parsed.query or parsed.fragment or ".." in parsed.path):
            raise ValueError()
        port = parsed.port
        if docker_loopback and parsed.hostname in ("127.0.0.1", "localhost", "::1"):
            parsed = parsed._replace(netloc="host.docker.internal" + (":" + str(port) if port else ""))
        return parsed.geturl()
    except ValueError:
        raise RuntimeError("Invalid RTC endpoint") from None


def validate_rtc(settings):
    rtc_endpoint(settings.get("internal-url"), {"http", "https"})
    rtc_endpoint(settings.get("public-url"), {"ws", "wss"})
    for name, maximum in (("api-key", 256), ("api-secret", 2048)):
        value = settings.get(name)
        if (not isinstance(value, str) or not value or len(value) > maximum
                or any(ord(c) < 33 or ord(c) == 127 or c == "\\" for c in value)):
            raise RuntimeError("Invalid RTC credentials")
    import re
    if not isinstance(settings.get("room-prefix"), str) or not re.fullmatch(r"[A-Za-z0-9_-]{2,47}-", settings["room-prefix"]):
        raise RuntimeError("Invalid RTC room prefix")


def enable_rtc(env_path=None, public_url=None):
    previous = rtc_settings()
    if env_path:
        values = {}
        for line in Path(env_path).read_text().splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            key, sep, value = stripped.partition("=")
            if sep and key in ("LIVEKIT_INTERNAL_URL", "LIVEKIT_URL", "LIVEKIT_API_KEY", "LIVEKIT_API_SECRET"):
                if key in values:
                    raise RuntimeError("Duplicate RTC configuration field")
                value = value.strip()
                if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
                    value = value[1:-1]
                if value.startswith(("\"", "'")) or value.endswith(("\"", "'")):
                    raise RuntimeError("Invalid RTC configuration field")
                values[key] = value
        internal = rtc_endpoint(values.get("LIVEKIT_INTERNAL_URL"), {"http", "https"}, True)
        source_public = values.get("LIVEKIT_URL")
        key, secret = values.get("LIVEKIT_API_KEY"), values.get("LIVEKIT_API_SECRET")
    elif all(previous.get(k) for k in ("internal-url", "public-url", "api-key", "api-secret")):
        internal, source_public = previous["internal-url"], previous["public-url"]
        key, secret = previous["api-key"], previous["api-secret"]
    else:
        raise RuntimeError("RTC requires --rtc-env with private LiveKit configuration")
    public = rtc_endpoint(public_url or source_public, {"ws", "wss", "http", "https"})
    parsed = urlsplit(public)
    public = parsed._replace(scheme={"http": "ws", "https": "wss"}.get(parsed.scheme, parsed.scheme)).geturl()
    if previous.get("room-prefix") and any(previous.get(k) != v for k, v in (("internal-url", internal), ("api-key", key), ("api-secret", secret))):
        raise RuntimeError("Refusing to redirect existing RTC rooms or credentials")
    config = {"enabled": True, "internal-url": internal, "public-url": public,
              "api-key": key, "api-secret": secret,
              "room-prefix": previous.get("room-prefix") or "xz-b5-" + secrets.token_hex(12) + "-"}
    validate_rtc(config)
    private_json("rtc-settings.json", config)


def disable_rtc():
    settings = rtc_settings()
    if settings.get("enabled") is True:
        # The next API must contain the independently gated drain controller; an old
        # JAR would disable maintenance along with the public endpoints.
        jar = API / "target/xiaozhi-esp32-api.jar"
        marker = "BOOT-INF/classes/xiaozhi/modules/supportmedia/MediaDrainController.class"
        try:
            with zipfile.ZipFile(jar) as archive:
                if marker not in archive.namelist():
                    raise RuntimeError("RTC disable requires a built API with drain support")
        except (OSError, zipfile.BadZipFile):
            raise RuntimeError("RTC disable requires a built API with drain support") from None
        validate_rtc(settings)
        settings["enabled"] = False
        private_json("rtc-settings.json", settings)


def realtime_settings():
    path = RUNTIME / "realtime-settings.json"
    if not path.exists():
        return {"enabled": False}
    data = json.loads(path.read_text())
    if not isinstance(data, dict) or type(data.get("enabled")) is not bool:
        raise RuntimeError("Invalid realtime settings")
    return data


def realtime_public_url(value):
    try:
        if not isinstance(value, str) or any(ord(c) < 33 or ord(c) == 127 or c == "\\" for c in value):
            raise ValueError()
        u = urlsplit(value)
        if (u.scheme not in ("ws", "wss") or not u.hostname or u.username or u.password
                or u.query or u.fragment or u.path != "/realtime" or u.port == 0
                or (u.scheme == "ws" and u.hostname not in ("127.0.0.1", "localhost", "::1"))):
            raise ValueError()
        return u.geturl()
    except ValueError:
        raise RuntimeError("Invalid realtime public WebSocket URL") from None


def validate_realtime_settings(settings):
    if type(settings.get("server_vad_enabled", False)) is not bool:
        raise RuntimeError("Invalid realtime server VAD setting")
    key = settings.get("worker_key")
    if not isinstance(key, str) or not 32 <= len(key) <= 512 or not re.fullmatch(r"[A-Za-z0-9_-]+", key):
        raise RuntimeError("Invalid realtime worker key")
    if not isinstance(settings.get("server_id"), str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,100}", settings["server_id"]):
        raise RuntimeError("Invalid realtime server identity")
    realtime_public_url(settings.get("public_url"))


def realtime_jar_check(require_vad=False):
    marker = "BOOT-INF/classes/xiaozhi/modules/realtime/RealtimeController.class"
    maintenance = "BOOT-INF/classes/xiaozhi/modules/realtime/RealtimeMaintenance.class"
    try:
        with zipfile.ZipFile(API / "target/xiaozhi-esp32-api.jar") as archive:
            if not {marker, maintenance}.issubset(archive.namelist()):
                raise RuntimeError("Realtime requires a built API with realtime and maintenance support")
            if require_vad and "BOOT-INF/classes/db/changelog/realtime-vad.changelog.yaml" not in archive.namelist():
                raise RuntimeError("Server VAD requires a freshly built API with the VAD migration")
    except (OSError, zipfile.BadZipFile):
        raise RuntimeError("Realtime requires a built API with realtime and maintenance support") from None


def realtime_provider_config():
    path = RUNTIME / "ai-worker.json"
    try:
        if path.stat().st_mode & 0o077:
            raise ValueError()
        source = json.loads(path.read_text())
        url = urlsplit(source["base_url"])
        api_key = source["api_key"]
        workspace = source.get("workspace_id", "")
        if (url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment
                or url.port not in (None, 443) or not isinstance(api_key, str) or not api_key.strip()
                or any(ord(c) < 32 or ord(c) == 127 for c in api_key)):
            raise ValueError()
        host = url.hostname
        if host in ("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com"):
            if not isinstance(workspace, str) or not re.fullmatch(r"[a-zA-Z0-9-]{1,80}", workspace):
                raise ValueError()
            region = "cn-beijing" if host == "dashscope.aliyuncs.com" else "ap-southeast-1"
            host = f"{workspace}.{region}.maas.aliyuncs.com"
        elif not re.fullmatch(r"[a-z0-9-]+\.(cn-beijing|ap-southeast-1)\.maas\.aliyuncs\.com", host):
            raise ValueError()
        elif workspace and workspace.lower() != host.split(".", 1)[0]:
            raise ValueError()
        return "wss://" + host + "/api-ws/v1/realtime", api_key
    except (OSError, ValueError, TypeError, KeyError, AttributeError):
        raise RuntimeError("Realtime needs a valid private workspace Bailian AI worker configuration") from None


def prepare_realtime(public_url=None, server_vad=None):
    realtime_jar_check()
    provider_url, api_key = realtime_provider_config()
    existing = realtime_settings()
    realtime_jar_check(server_vad is True or server_vad is None and existing.get("server_vad_enabled") is True)
    if server_vad is not None and type(server_vad) is not bool:
        raise RuntimeError("Invalid realtime server VAD setting")
    key = existing.get("worker_key") or secrets.token_urlsafe(48)
    server_id = existing.get("server_id") or "dev-realtime-" + secrets.token_hex(12)
    settings = {"enabled": True, "worker_key": key, "server_id": server_id,
                "server_vad_enabled": existing.get("server_vad_enabled", False) if server_vad is None else server_vad,
                "public_url": realtime_public_url(public_url or existing.get("public_url") or "ws://127.0.0.1:18899/realtime")}
    validate_realtime_settings(settings)
    if key == api_key:
        raise RuntimeError("Realtime worker key must be independent")
    worker = {"enabled": True, "manager_url": API_URL, "worker_key": key,
              "provider_url": provider_url, "api_key": api_key, "host": "127.0.0.1", "port": 18899,
              "worker_id": "dev-realtime-local", "server_id": server_id,
              "origins": ["http://127.0.0.1:18894", "http://localhost:18894"], "allow_missing_origin": False}
    private_json("realtime-worker.json", worker)
    private_json("realtime-settings.json", settings)


def realtime_process():
    path = RUNTIME / "realtime-worker-process.json"
    if not path.exists():
        return None
    try:
        info = json.loads(path.read_text())
        pid = info["pid"]
        if type(pid) is not int or pid <= 1 or not info["identity"] or process_identity(pid) != info["identity"]:
            return None
        command = subprocess.run(["ps", "-o", "command=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
        args = shlex.split(command)
        if not any(args[i:i + 2] == ["-m", "realtime_worker"] for i in range(len(args) - 1)):
            return None
        if not any(args[i:i + 2] == ["--config", str(RUNTIME / "realtime-worker.json")] for i in range(len(args) - 1)):
            return None
        return info
    except (OSError, ValueError, KeyError, TypeError):
        return None


def realtime_python():
    python = os.environ.get("XIAOZHI_REALTIME_WORKER_PYTHON", str(ROOT.parent / ".realtime-venv/bin/python"))
    try:
        subprocess.run([python, "-c", "import aiohttp, httpx, websockets, PIL"], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError):
        raise RuntimeError("Realtime worker requires its prepared independent Python environment") from None
    return python


def validate_realtime_worker_file(settings):
    try:
        path = RUNTIME / "realtime-worker.json"
        if path.stat().st_mode & 0o077:
            raise ValueError()
        worker = json.loads(path.read_text())
        if (worker.get("enabled") is not True or worker.get("worker_key") != settings["worker_key"]
                or worker.get("server_id") != settings["server_id"] or worker.get("host") != "127.0.0.1"
                or worker.get("port") != 18899 or worker.get("manager_url") != API_URL
                or worker.get("origins") != ["http://127.0.0.1:18894", "http://localhost:18894"]
                or worker.get("allow_missing_origin") is not False):
            raise ValueError()
    except (OSError, ValueError, TypeError, AttributeError):
        raise RuntimeError("Invalid private realtime worker configuration") from None

def start_realtime_worker():
    if realtime_process(): return
    settings = realtime_settings()
    if settings.get("enabled") is not True:
        raise RuntimeError("Realtime is not enabled")
    validate_realtime_settings(settings)
    python = realtime_python()
    validate_realtime_worker_file(settings)
    with (RUNTIME / "realtime-worker.log").open("a") as log:
        process = subprocess.Popen([python, "-m", "realtime_worker", "--config", str(RUNTIME / "realtime-worker.json")],
                                   cwd=ROOT / "main/xiaozhi-server", stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
    identity = process_identity(process.pid)
    if not identity:
        raise RuntimeError("Realtime worker exited; inspect its private log")
    private_json("realtime-worker-process.json", {"pid": process.pid, "identity": identity})
    if not realtime_process():
        raise RuntimeError("Realtime worker identity verification failed")


def stop_realtime_worker():
    info = realtime_process()
    if not info:
        record = RUNTIME / "realtime-worker-process.json"
        if record.exists():
            try:
                pid = json.loads(record.read_text())["pid"]
                if type(pid) is not int or pid <= 1:
                    raise ValueError()
                if process_identity(pid):
                    raise RuntimeError("Realtime process identity mismatch; refusing to stop or disable")
                record.unlink()
            except (OSError, ValueError, KeyError, TypeError):
                raise RuntimeError("Invalid realtime process record; refusing to stop or disable") from None
        return
    os.killpg(info["pid"], signal.SIGTERM)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if not realtime_process():
            (RUNTIME / "realtime-worker-process.json").unlink(missing_ok=True)
            return
        time.sleep(.1)
    raise RuntimeError("Realtime worker did not stop; feature remains enabled")


def prepare_speech_worker():
    """Read only the specifically authorized Dev TTS record; return private data in memory."""
    query = "SELECT config_json FROM ai_model_config WHERE model_type='TTS' AND model_code='TTS_BailianCosyVoice' AND is_default=1 AND is_enabled=1"
    try:
        result = compose("exec", "-T", "mysql", "sh", "-c",
                         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw --skip-column-names xiaozhi_esp32_server -e "$1"',
                         "sh", query, capture_output=True, text=True)
        rows = result.stdout.strip().splitlines()
        if len(rows) != 1:
            raise ValueError()
        source = json.loads(rows[0])
        if not isinstance(source, dict):
            raise ValueError()
        provider, key = source["ws_url"], source["api_key"]
        model, voice = source["model"], source.get("private_voice") or source["voice"]
        workspace = source.get("workspace_id") or ""
        if not all(isinstance(x, str) for x in (provider, key, model, voice, workspace)):
            raise ValueError()
        url = urlsplit(provider)
        host = url.hostname or ""
        if (url.scheme != "wss" or url.username or url.password or url.query or url.fragment
                or url.port not in (None, 443) or url.path.rstrip("/") != "/api-ws/v1/inference"
                or not (host in ("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com")
                        or re.fullmatch(r"[a-z0-9][a-z0-9.-]*\.maas\.aliyuncs\.com", host))
                or (model, voice) not in SPEECH_PROFILES
                or not key.strip() or any(ord(c) < 32 or ord(c) == 127 for c in key + workspace)
                or len(workspace) > 100):
            raise ValueError()
        target_config = RUNTIME / "answer-speech-worker.json"
        if target_config.exists() and (not target_config.is_file() or target_config.stat().st_mode & 0o077):
            raise ValueError()
        path = RUNTIME / "answer-speech-worker-key"
        if path.exists():
            if path.stat().st_mode & 0o077:
                raise ValueError()
            worker_key = path.read_text().strip()
            if not re.fullmatch(r"[a-f0-9]{64}", worker_key):
                raise ValueError()
        else:
            worker_key = secrets.token_hex(32)
        if worker_key == key:
            raise ValueError()
        for other in ("ai-worker-key", "media-worker-key", "realtime-worker.json"):
            other_path = RUNTIME / other
            if other_path.is_file():
                value = other_path.read_text().strip()
                if other.endswith(".json"):
                    value = json.loads(value).get("worker_key", "")
                if worker_key == value:
                    raise ValueError()
        worker = {"enabled": True, "manager_url": API_URL, "worker_key": worker_key,
                  "worker_id": "answer-speech-dev", "provider_url": provider, "api_key": key,
                  "model": model, "voice": voice, "workspace_id": workspace}
        python = speech_worker_python()
        subprocess.run([python, "-c", "import json,sys; from answer_speech_worker.config import Config; Config(**json.load(sys.stdin))"],
                       cwd=ROOT / "main/xiaozhi-server", input=json.dumps(worker), text=True, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return worker
    except (OSError, subprocess.SubprocessError, ValueError, TypeError, KeyError, AttributeError):
        raise RuntimeError("Answer speech requires exactly one enabled default trusted Bailian CosyVoice configuration and a prepared private worker") from None


def write_speech_key(value):
    path = RUNTIME / "answer-speech-worker-key"
    if path.exists():
        if path.stat().st_mode & 0o077 or path.read_text().strip() != value:
            raise RuntimeError("Answer speech worker key changed during preflight")
        return
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as output:
        output.write(value)


def speech_worker_python():
    python = os.environ.get("XIAOZHI_ANSWER_SPEECH_PYTHON", str(DEV / ".venv-answer-speech/bin/python"))
    try:
        subprocess.run([python, "-c", "import httpx, websockets, answer_speech_worker.config"],
                       cwd=ROOT / "main/xiaozhi-server", check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError):
        raise RuntimeError("Answer speech requires its prepared independent Python environment") from None
    return python


def speech_worker_process():
    path = RUNTIME / "answer-speech-worker-process.json"
    if not path.exists():
        return None
    try:
        info = json.loads(path.read_text()); pid = info["pid"]
        if type(pid) is not int or pid <= 1 or not info["identity"] or process_identity(pid) != info["identity"]:
            return None
        command = subprocess.run(["ps", "-o", "command=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
        args = shlex.split(command)
        if not any(args[i:i + 2] == ["-m", "answer_speech_worker"] for i in range(len(args) - 1)):
            return None
        if not any(args[i:i + 2] == ["--config", str(RUNTIME / "answer-speech-worker.json")] for i in range(len(args) - 1)):
            return None
        return info
    except (OSError, ValueError, KeyError, TypeError):
        return None


def speech_worker_status():
    """Distinguish a missing process from a record that cannot safely identify one."""
    path = RUNTIME / "answer-speech-worker-process.json"
    if not path.exists():
        return "stopped (no process record)"
    try:
        record = json.loads(path.read_text())
        pid = record["pid"]
        if type(pid) is not int or pid <= 1 or not isinstance(record.get("identity"), str) or not record["identity"]:
            raise ValueError()
        if speech_worker_process():
            return "running (identity verified)"
        identity = process_identity(pid)
        return "unverified (process record stale)" if not identity else "unverified (process identity or command mismatch)"
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        return "unverified (invalid or unreadable process record)"


def stop_speech_worker():
    path = RUNTIME / "answer-speech-worker-process.json"
    info = speech_worker_process()
    if not info:
        if path.exists():
            try:
                pid = json.loads(path.read_text())["pid"]
                if type(pid) is not int or pid <= 1:
                    raise ValueError()
                if process_identity(pid):
                    raise RuntimeError("Answer speech process identity mismatch; refusing to stop or disable")
                path.unlink()
            except (OSError, ValueError, KeyError, TypeError):
                raise RuntimeError("Invalid answer speech process record; refusing to stop or disable") from None
        return
    os.killpg(info["pid"], signal.SIGTERM)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if not speech_worker_process():
            path.unlink(missing_ok=True)
            return
        time.sleep(.1)
    raise RuntimeError("Answer speech worker did not stop; feature remains enabled")


def start_speech_worker():
    if speech_worker_process():
        return
    if not ai_first_settings().get("enabled") or not ai_first_settings().get("speech_enabled"):
        raise RuntimeError("Answer speech is not enabled")
    config = RUNTIME / "answer-speech-worker.json"
    if not config.is_file() or config.stat().st_mode & 0o077:
        raise RuntimeError("Answer speech worker configuration is missing or not private")
    try:
        worker = json.loads(config.read_text())
        key_path = RUNTIME / "answer-speech-worker-key"
        if (key_path.stat().st_mode & 0o077 or worker.get("worker_key") != key_path.read_text().strip()
                or worker.get("manager_url") != API_URL or worker.get("enabled") is not True
                or (worker.get("model"), worker.get("voice")) not in SPEECH_PROFILES):
            raise ValueError()
        python = speech_worker_python()
        subprocess.run([python, "-c", "import json,sys; from answer_speech_worker.config import Config; Config(**json.load(sys.stdin))"],
                       cwd=ROOT / "main/xiaozhi-server", input=json.dumps(worker), text=True, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except (OSError, ValueError, TypeError, AttributeError, subprocess.SubprocessError):
        raise RuntimeError("Invalid private answer speech worker configuration") from None
    with (RUNTIME / "answer-speech-worker.log").open("a") as log:
        process = subprocess.Popen([python, "-m", "answer_speech_worker", "--config", str(config)],
                                   cwd=ROOT / "main/xiaozhi-server", stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
    identity = process_identity(process.pid)
    if not identity:
        raise RuntimeError("Answer speech worker exited; inspect its private log")
    private_json("answer-speech-worker-process.json", {"pid": process.pid, "identity": identity})
    if not speech_worker_process():
        raise RuntimeError("Answer speech worker identity verification failed")


def set_annotations_enabled(enabled):
    """Only adjust private Dev flags; reuse the already-configured media worker and storage."""
    settings = media_settings()
    if enabled:
        if settings.get("enabled") is not True:
            raise RuntimeError("Annotations require enabled media")
        worker_path = RUNTIME / "media-worker.json"
        if not worker_path.is_file():
            raise RuntimeError("Annotations require existing media worker configuration")
        worker = json.loads(worker_path.read_text())
        if not isinstance(worker, dict):
            raise RuntimeError("Invalid media worker configuration")
        font = Path(worker.get("annotation_font") or ANNOTATION_DEFAULT_FONT)
        if not font.is_file():
            raise RuntimeError("Annotation font is missing")
    settings["annotations_enabled"] = enabled
    private_json("media-settings.json", settings)


def private_json(name, data):
    path = RUNTIME / name
    # Create privately even when called outside configure().
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as output:
        json.dump(data, output)
    path.chmod(0o600)


def export_ai_config(omni_model=None):
    # This query is read-only and selects exactly one enabled default LLM.
    query = "SELECT config_json FROM ai_model_config WHERE model_type='LLM' AND is_default=1 AND is_enabled=1"
    try:
        result = compose("exec", "-T", "mysql", "sh", "-c",
                         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw --skip-column-names xiaozhi_esp32_server -e "$1"',
                         "sh", query, capture_output=True, text=True)
        rows = result.stdout.strip().splitlines()
        if len(rows) != 1:
            raise ValueError("expected one default model")
        config = json.loads(rows[0])
        url = urlsplit(config.get("base_url", ""))
        host = url.hostname or ""
        if (url.scheme != "https" or url.username or url.password or url.query or url.fragment or
                not (host in ("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com") or host.endswith(".maas.aliyuncs.com"))):
            raise ValueError("unsupported provider")
        model, api_key = config.get("model_name"), config.get("api_key")
        if not isinstance(model, str) or not model.strip() or not isinstance(api_key, str) or not api_key.strip():
            raise ValueError("missing model credentials")
        max_tokens = config.get("max_tokens", 500)
        if isinstance(max_tokens, bool) or not isinstance(max_tokens, int) or max_tokens <= 0:
            raise ValueError("invalid max tokens")
        key_path = RUNTIME / "ai-worker-key"
        if not key_path.exists():
            fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w") as output: output.write(secrets.token_urlsafe(48))
        key_path.chmod(0o600)
        worker_key = key_path.read_text().strip()
        if not worker_key or any(c in worker_key + model for c in "\r\n"):
            raise ValueError("invalid model or worker key")
        worker = {"manager_url": API_URL, "worker_key": worker_key, "worker_id": "dev-local",
                  "base_url": config["base_url"], "api_key": api_key, "model": model, "max_tokens": max_tokens}
        if config.get("workspace_id"):
            worker["workspace_id"] = config["workspace_id"]
        vision = media_settings().get("vision_model")
        if vision:
            worker.update(vision_model=vision, vision_base_url=config["base_url"],
                          vision_api_key=api_key, vision_workspace_id=config.get("workspace_id", ""))
        omni = ai_settings().get("omni_model", "") if omni_model is None else omni_model
        if omni:
            if omni != "qwen3.8-omni-flash":
                raise ValueError("unsupported Omni model")
            worker.update(omni_model=omni, omni_base_url=config["base_url"],
                          omni_api_key=api_key, omni_workspace_id=config.get("workspace_id", ""))
        private_json("ai-worker.json", worker)
        return model
    except (ValueError, TypeError, KeyError, OSError, subprocess.SubprocessError):
        raise RuntimeError("Cannot load a valid enabled default Bailian LLM configuration from Dev") from None


def ai_process():
    path = RUNTIME / "ai-worker-process.json"
    if path.exists():
        try:
            info = json.loads(path.read_text())
            if info["identity"] and process_identity(info["pid"]) == info["identity"]:
                return info
        except (ValueError, KeyError, TypeError):
            pass
    return None


def start_ai_worker():
    if ai_process(): return
    python = os.environ.get("XIAOZHI_AI_WORKER_PYTHON", str(ROOT.parent / ".venv/bin/python"))
    try:
        subprocess.run([python, "-c", "import openai, httpx"], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError):
        raise RuntimeError("AI worker Python needs openai and httpx; set XIAOZHI_AI_WORKER_PYTHON to a prepared environment") from None
    if not (RUNTIME / "ai-worker.json").is_file():
        raise RuntimeError("AI worker configuration is missing; run ai-enable")
    with (RUNTIME / "ai-worker.log").open("a") as log:
        process = subprocess.Popen([python, "-m", "ai_worker", "--config", str(RUNTIME / "ai-worker.json")],
                                   cwd=ROOT / "main/xiaozhi-server", stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
    identity = process_identity(process.pid)
    if not identity:
        raise RuntimeError("AI worker exited; inspect dev/.runtime/ai-worker.log")
    private_json("ai-worker-process.json", {"pid": process.pid, "identity": identity})


def media_process():
    path = RUNTIME / "media-worker-process.json"
    if path.exists():
        try:
            info = json.loads(path.read_text())
            if info["identity"] and process_identity(info["pid"]) == info["identity"]:
                return info
        except (ValueError, KeyError, TypeError):
            pass
    return None


def export_media_config(storage_type=None, storage_env=None, vision_model=None):
    """Read existing ASR settings and opt-in storage credentials; never initializes a bucket."""
    settings = media_settings()
    selected = storage_type or settings.get("storage", "filesystem")
    if selected not in ("filesystem", "s3"):
        raise RuntimeError("Unsupported media storage")
    if settings.get("storage") and selected != settings["storage"]:
        raise RuntimeError("Media storage cannot be switched in place; existing objects must remain readable")
    if vision_model and (not all(c.isalnum() or c in "._-" for c in vision_model) or len(vision_model) > 128):
        raise RuntimeError("Invalid vision model")
    for name in ("media-worker-key", "media-ticket-key"):
        path = RUNTIME / name
        if not path.exists():
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w") as output: output.write(secrets.token_urlsafe(48))
        path.chmod(0o600)
    worker = {"manager_url": API_URL, "worker_key": (RUNTIME / "media-worker-key").read_text().strip(),
              "worker_id": "dev-media-local", "ffmpeg": shutil.which("ffmpeg") or "ffmpeg",
              "ffprobe": shutil.which("ffprobe") or "ffprobe"}
    query = "SELECT config_json FROM ai_model_config WHERE model_type='ASR' AND is_default=1 AND is_enabled=1"
    result = compose("exec", "-T", "mysql", "sh", "-c",
                     'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --raw --skip-column-names xiaozhi_esp32_server -e "$1"',
                     "sh", query, capture_output=True, text=True)
    rows = result.stdout.strip().splitlines()
    transcription = False
    if len(rows) == 1:
        config = json.loads(rows[0])
        url = urlsplit(config.get("base_url", ""))
        if (url.scheme == "https" and not url.username and not url.password and not url.query and not url.fragment
                and (url.hostname in ("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com")
                     or (url.hostname or "").endswith(".maas.aliyuncs.com"))
                and config.get("api_key") and config.get("model_name") == "qwen3-asr-flash"):
            worker.update(asr_base_url=config["base_url"], asr_api_key=config["api_key"],
                          asr_model=config["model_name"], asr_workspace_id=config.get("workspace_id", ""))
            transcription = True
    if selected == "s3":
        storage_path = RUNTIME / "media-storage.json"
        if storage_env:
            values = {}
            for line in Path(storage_env).read_text().splitlines():
                if "=" in line and not line.lstrip().startswith("#"):
                    key, value = line.split("=", 1)
                    values[key.strip()] = value.strip().strip('"').strip("'")
            endpoint = values.get("S3_ENDPOINT", "")
            parsed = urlsplit(endpoint)
            if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
                raise RuntimeError("Invalid private S3 endpoint")
            # Docker's host gateway is needed only for the API container, never a public URL.
            if parsed.hostname in ("127.0.0.1", "localhost"):
                endpoint = parsed._replace(netloc="host.docker.internal" + (":" + str(parsed.port) if parsed.port else "")).geturl()
            previous = json.loads(storage_path.read_text()) if storage_path.exists() else None
            storage = {"endpoint": endpoint, "region": values.get("S3_REGION", "us-east-1"),
                       "bucket": values.get("S3_BUCKET", ""), "access-key": values.get("S3_ACCESS_KEY", ""),
                       "secret-key": values.get("S3_SECRET_KEY", ""),
                       "prefix": previous["prefix"] if previous else "xiaozhi-dev/" + secrets.token_hex(12) + "/"}
            if any(not v or any(c in v for c in "\r\n") for v in storage.values()):
                raise RuntimeError("Incomplete private S3 configuration")
            if previous and any(previous[k] != storage[k] for k in ("endpoint", "bucket", "prefix")):
                raise RuntimeError("Refusing to redirect existing media to another S3 location")
            private_json("media-storage.json", storage)
        elif not storage_path.exists():
            raise RuntimeError("S3 requires --storage-env with an existing private configuration")
    private_json("media-worker.json", worker)
    settings.update(enabled=True, storage=selected, transcription=transcription)
    if vision_model: settings["vision_model"] = vision_model
    private_json("media-settings.json", settings)


def start_media_worker():
    if media_process(): return
    python = os.environ.get("XIAOZHI_MEDIA_WORKER_PYTHON", str(ROOT.parent / ".media-venv/bin/python"))
    try:
        subprocess.run([python, "-c", "import PIL,pypdf,httpx"], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        config = json.loads((RUNTIME / "media-worker.json").read_text())
        for name in ("ffmpeg", "ffprobe"):
            subprocess.run([config[name], "-version"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except (OSError, KeyError, subprocess.SubprocessError):
        raise RuntimeError("Media worker requires its prepared Python environment, ffmpeg and ffprobe") from None
    with (RUNTIME / "media-worker.log").open("a") as log:
        process = subprocess.Popen([python, "-m", "media_worker", "--config", str(RUNTIME / "media-worker.json")],
                                   cwd=ROOT / "main/xiaozhi-server", stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
    identity = process_identity(process.pid)
    if not identity:
        raise RuntimeError("Media worker exited; inspect its private runtime log")
    private_json("media-worker-process.json", {"pid": process.pid, "identity": identity})


def stage_api_jar():
    """Replace the staged file atomically, preserving running containers' old inode."""
    source = API / "target/xiaozhi-esp32-api.jar"
    if not source.is_file():
        raise RuntimeError("Dev API jar is missing; run up --build first")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix=".manager-api-", suffix=".jar", dir=RUNTIME, delete=False) as output:
            temporary = Path(output.name)
            with source.open("rb") as input_file:
                shutil.copyfileobj(input_file, output)
        os.replace(temporary, RUNTIME / "manager-api.jar")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def recreate_api():
    stage_api_jar()
    compose("up", "-d", "--force-recreate", "--no-deps", "api")
    wait_http(API_URL + "/user/pub-config")


def build():
    env = os.environ.copy()
    java = Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")
    if "JAVA_HOME" not in env and java.exists():
        env["JAVA_HOME"] = str(java)
    print("Building Dev API…", flush=True)
    with (RUNTIME / "build-api.log").open("w") as log:
        subprocess.run(["mvn", "-B", "-DskipTests", "package"], cwd=API, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)


def seed_local_console():
    """Explicit first-time copy. Reads the old DB, writes only an empty Dev database."""
    result = compose("exec", "-T", "mysql", "sh", "-c",
                     'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=\'xiaozhi_esp32_server\'"',
                     capture_output=True, text=True)
    if int(result.stdout.strip()) != 0:
        raise RuntimeError("Dev database already has tables; refusing to overwrite it")
    snapshot = RUNTIME / "local-console-seed.sql"
    print("Copying the local console database into the empty Dev database…", flush=True)
    with snapshot.open("wb") as output:
        subprocess.run(["docker", "exec", "xiaozhi-console-mysql-1", "sh", "-c",
                        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --no-tablespaces --set-gtid-purged=OFF xiaozhi_esp32_server'], stdout=output, check=True)
    snapshot.chmod(0o600)
    with snapshot.open("rb") as source:
        compose("exec", "-T", "mysql", "sh", "-c",
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot xiaozhi_esp32_server', stdin=source)
    # Existing passwords remain useful; tokens must be reissued separately for Dev.
    compose("exec", "-T", "mysql", "sh", "-c",
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot xiaozhi_esp32_server -e "DELETE FROM sys_user_token"')
    (RUNTIME / "seed.json").write_text(json.dumps({"source": "xiaozhi-console-mysql-1", "copied_at": datetime.now(timezone.utc).isoformat()}))


def process_identity(pid):
    return subprocess.run(["ps", "-o", "lstart=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()


def web_process():
    path = RUNTIME / "web-process.json"
    if path.exists():
        try:
            info = json.loads(path.read_text())
            if info["identity"] and process_identity(info["pid"]) == info["identity"]:
                return info
        except (ValueError, KeyError, TypeError):
            pass
    return None


def start_web():
    """Compatibility alias for the current Vue 3 frontend."""
    start_candidate()


def candidate_process():
    path = RUNTIME / "candidate-process.json"
    if path.exists():
        try:
            info = json.loads(path.read_text())
            if info["identity"] and process_identity(info["pid"]) == info["identity"]:
                return info
        except (ValueError, KeyError, TypeError):
            pass
    return None


def start_candidate():
    if candidate_process():
        return
    modules = CANDIDATE / "node_modules"
    if modules.is_symlink() or not modules.is_dir():
        raise RuntimeError("Install frontend dependencies in main/manager-web with npm ci; linked node_modules are not supported")
    with socket.socket() as check:
        if check.connect_ex(("127.0.0.1", 18894)) == 0:
            raise RuntimeError("Port 18894 belongs to another process")
    env = {**os.environ, "VITE_API_BASE_URL": "/xiaozhi",
           "VITE_API_PROXY_TARGET": "http://127.0.0.1:18893",
           "VITE_CONVERSATION_ENABLED": "true", "VITE_ENV_LABEL": "Dev Vue 3"}
    if esp32_intents_enabled():
        env["VITE_ESP32_INTENTS_ENABLED"] = "true"
    with (RUNTIME / "candidate.log").open("a") as log:
        process = subprocess.Popen(["npm", "run", "dev", "--", "--host", "127.0.0.1", "--port", "18894", "--strictPort"],
                                   cwd=CANDIDATE, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    identity = process_identity(process.pid)
    if not identity:
        raise RuntimeError("Candidate process exited; inspect dev/.runtime/candidate.log")
    (RUNTIME / "candidate-process.json").write_text(json.dumps({"pid": process.pid, "identity": identity}))


def wait_http(url, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(1)
    raise RuntimeError(f"Timed out waiting for {url}; inspect dev/.runtime logs")


def stop_process(find_process, filename):
    info = find_process()
    if info:
        os.killpg(info["pid"], signal.SIGTERM)
        (RUNTIME / filename).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["up", "stop", "status", "candidate-up", "candidate-status", "candidate-stop", "legacy-up", "legacy-status", "legacy-stop", "ai-enable", "ai-disable", "ai-status", "media-enable", "media-disable", "media-status", "annotations-enable", "annotations-disable", "annotations-status", "rtc-enable", "rtc-disable", "rtc-status", "realtime-enable", "realtime-disable", "realtime-status", "summary-enable", "summary-disable", "summary-status", "agent-tools-enable", "agent-tools-disable", "agent-tools-status", "ai-first-enable", "ai-first-disable", "ai-first-status"])
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--no-frontend", action="store_true", help="Start API and workers without starting the web frontend")
    parser.add_argument("--seed-local-console", action="store_true")
    parser.add_argument("--storage", choices=["filesystem", "s3"])
    parser.add_argument("--storage-env", help="Existing private S3 configuration file; no bucket creation")
    parser.add_argument("--vision-model", help="Previously verified Bailian vision model for this Dev workspace")
    parser.add_argument("--omni-model", help="Explicit non-realtime qwen3.8-omni-flash; empty disables raw sound analysis")
    parser.add_argument("--rtc-env", help="Existing private LiveKit configuration file")
    parser.add_argument("--rtc-public-url", help="Client-reachable LiveKit URL")
    parser.add_argument("--realtime-public-url", help="Client-reachable realtime WebSocket URL")
    parser.add_argument("--server-vad", action=argparse.BooleanOptionalAction, default=None,
                        help="Enable/disable automatic realtime speech detection; omitted preserves current setting")
    parser.add_argument("--technical-local", action="store_true", help="Explicitly allow technical-only local acceptance tools")
    parser.add_argument("--materials", action="store_true", help="Explicitly enable realtime uploaded materials for AI-first")
    parser.add_argument("--speech", action="store_true", help="Explicitly enable ordinary answer speech")
    parser.add_argument("--skills", action="store_true", help="Explicitly enable fixed readonly skills")
    parser.add_argument("--runtime-control", action="store_true", help="Explicitly enable AI runtime admission control")
    args = parser.parse_args()
    if args.no_frontend and args.action != "up":
        parser.error("--no-frontend is only valid with up")
    if args.runtime_control and args.action != "ai-first-enable":
        parser.error("--runtime-control is only valid with ai-first-enable")
    if (args.speech or args.skills) and args.action != "ai-first-enable":
        parser.error("--speech and --skills are only valid with ai-first-enable")
    if args.materials and args.action != "ai-first-enable":
        parser.error("--materials is only valid with ai-first-enable")
    if args.technical_local and args.action not in ("agent-tools-enable", "ai-first-enable"):
        parser.error("--technical-local is only valid with agent-tools-enable or ai-first-enable")
    if args.action not in ("ai-first-enable", "ai-first-disable", "ai-first-status"):
        configure()
    if args.action == "ai-first-enable":
        if not RUNTIME.is_dir():
            raise RuntimeError("AI-first requires the existing Dev stack; start Dev first")
        if args.build: build()
        first_settings = prepare_ai_first(args.technical_local, args.materials, args.speech, args.skills, args.runtime_control)
        materials_worker = ai_first_materials_worker_config() if args.materials else None
        speech_worker = prepare_speech_worker() if args.speech else None
        ai_first_gateway_preflight()
        stop_speech_worker()
        stop_realtime_worker()
        stop_process(ai_process, "ai-worker-process.json")
        if materials_worker is not None:
            private_json("realtime-worker.json", materials_worker)
        if speech_worker is not None:
            write_speech_key(speech_worker["worker_key"])
            private_json("answer-speech-worker.json", speech_worker)
        private_json("ai-first-settings.json", first_settings)
        configure(); recreate_api(); start_ai_worker()
        if realtime_settings().get("enabled") is True: start_realtime_worker()
        if args.speech: start_speech_worker()
        print("AI-first enabled; technical local tools:", args.technical_local)
    elif args.action == "ai-first-disable":
        stop_speech_worker()
        stop_realtime_worker()
        stop_process(ai_process, "ai-worker-process.json")
        private_json("ai-first-settings.json", {"enabled": False, "technical_local": False, "realtime_tools": False, "materials_enabled": False, "speech_enabled": False, "skills_enabled": False, "runtime_control_enabled": False})
        configure(); recreate_api()
        if ai_settings().get("enabled") is True: start_ai_worker()
        if realtime_settings().get("enabled") is True: start_realtime_worker()
        print("AI-first disabled; histories, base features and private configuration retained")
    elif args.action == "ai-first-status":
        first = ai_first_settings()
        active = first.get("enabled") is True and ai_settings().get("enabled") is True and agent_tools_settings().get("enabled") is True
        print("AI-first enabled:", active)
        print("AI-first technical local tools:", active and first.get("technical_local") is True)
        print("AI-first realtime tools:", active and first.get("realtime_tools") is True and realtime_settings().get("enabled") is True)
        print("AI-first realtime materials:", active and first.get("materials_enabled") is True and realtime_settings().get("enabled") is True)
        print("AI-first answer speech:", first.get("enabled") is True and first.get("speech_enabled") is True, "worker:", speech_worker_status())
        print("AI-first readonly skills:", active and first.get("skills_enabled") is True)
        print("AI-first runtime control:", first.get("enabled") is True and first.get("runtime_control_enabled") is True)
        print("These are configuration and process identity only; API deployment, provider and job health are not checked.")
    elif args.action == "agent-tools-enable":
        if args.build:
            build()
        prepare_agent_tools(args.technical_local)
        stop_process(ai_process, "ai-worker-process.json")
        configure(); recreate_api(); start_ai_worker()
        print("Agent tools enabled; technical local tools:", args.technical_local)
    elif args.action == "agent-tools-disable":
        stop_process(ai_process, "ai-worker-process.json")
        private_json("agent-tools-settings.json", {"enabled": False, "technical_local": False})
        configure(); recreate_api()
        if ai_settings().get("enabled") is True: start_ai_worker()
        print("Agent tools disabled; history and data retained")
    elif args.action == "agent-tools-status":
        tools = agent_tools_settings()
        active = tools.get("enabled") is True and ai_settings().get("enabled") is True
        print("Agent tools enabled:", active)
        print("Technical local tools enabled:", active and tools.get("technical_local") is True)
    elif args.action == "summary-enable":
        if args.build:
            build()
        prepare_summary()
        stop_summary_worker()
        configure()
        recreate_api()
        start_summary_worker()
        print("Internal summary assistance enabled; customer messages are never sent automatically")
    elif args.action == "summary-disable":
        stop_summary_worker()
        private_json("summary-settings.json", {"enabled": False})
        configure()
        recreate_api()
        print("Summary assistance disabled; revisions and task records retained")
    elif args.action == "summary-status":
        print("Summary assistance enabled:", summary_settings().get("enabled") is True and ai_settings().get("enabled") is True)
        print("Summary worker running:", bool(summary_process()))
    elif args.action == "media-enable":
        export_media_config(args.storage, args.storage_env, args.vision_model)
        stop_process(media_process, "media-worker-process.json")
        if ai_settings().get("enabled") is True:
            export_ai_config()
            stop_process(ai_process, "ai-worker-process.json")
        configure()
        recreate_api()
        start_media_worker()
        if ai_settings().get("enabled") is True: start_ai_worker()
        start_candidate()
        print("Private media enabled; Vue 3:", CANDIDATE_URL + "/#/conversations")
    elif args.action == "media-disable":
        stop_process(media_process, "media-worker-process.json")
        settings = media_settings(); settings["enabled"] = False; settings["annotations_enabled"] = False
        private_json("media-settings.json", settings)
        configure(); recreate_api()
        print("Media disabled; existing objects and database rows retained")
    elif args.action == "media-status":
        settings = media_settings()
        print("Media enabled:", settings.get("enabled") is True)
        print("Media worker running:", bool(media_process()))
        print("Media storage:", settings.get("storage", "filesystem"))
        print("Transcription enabled:", settings.get("transcription") is True)
        print("Vision model:", settings.get("vision_model", ""))
    elif args.action in ("annotations-enable", "annotations-disable"):
        enabled = args.action == "annotations-enable"
        set_annotations_enabled(enabled)
        configure()
        recreate_api()
        print("Annotations enabled" if enabled else "Annotations disabled; existing data retained")
    elif args.action == "annotations-status":
        settings = media_settings()
        print("Annotations enabled:", settings.get("enabled") is True and settings.get("annotations_enabled") is True)
        print("Media enabled:", settings.get("enabled") is True)
    elif args.action == "rtc-enable":
        enable_rtc(args.rtc_env, args.rtc_public_url)
        configure()
        recreate_api()
        print("RTC enabled")
    elif args.action == "rtc-disable":
        disable_rtc()
        configure()
        recreate_api()
        print("RTC disabled; room cleanup continues asynchronously when configured")
    elif args.action == "rtc-status":
        print("RTC enabled:", rtc_settings().get("enabled") is True)
    elif args.action == "realtime-enable":
        realtime_jar_check(args.server_vad is True or args.server_vad is None and realtime_settings().get("server_vad_enabled") is True)
        realtime_provider_config()
        realtime_python()
        if args.realtime_public_url:
            realtime_public_url(args.realtime_public_url)
        stop_realtime_worker()
        prepare_realtime(args.realtime_public_url, args.server_vad)
        configure()
        recreate_api()
        start_realtime_worker()
        print("Realtime enabled")
    elif args.action == "realtime-disable":
        realtime_jar_check()
        stop_realtime_worker()
        settings = realtime_settings()
        settings["enabled"] = False
        private_json("realtime-settings.json", settings)
        configure()
        recreate_api()
        print("Realtime disabled; session history retained and leases expire asynchronously")
    elif args.action == "realtime-status":
        print("Realtime enabled:", realtime_settings().get("enabled") is True)
        print("Realtime server VAD enabled:", realtime_settings().get("enabled") is True and realtime_settings().get("server_vad_enabled") is True)
        print("Realtime worker running:", bool(realtime_process()))
    elif args.action == "ai-enable":
        model = export_ai_config() if args.omni_model is None else export_ai_config(args.omni_model)
        stop_summary_worker()
        stop_process(ai_process, "ai-worker-process.json")
        settings = {"enabled": True, "model": model}
        omni = ai_settings().get("omni_model", "") if args.omni_model is None else args.omni_model
        if omni: settings["omni_model"] = omni
        private_json("ai-settings.json", settings)
        configure()
        recreate_api()
        start_ai_worker()
        if summary_settings().get("enabled") is True:
            start_summary_worker()
        start_candidate()
        wait_http(CANDIDATE_URL)
        print("AI enabled; Vue 3:", CANDIDATE_URL + "/#/conversations")
    elif args.action == "ai-disable":
        stop_summary_worker()
        stop_process(ai_process, "ai-worker-process.json")
        settings = ai_settings()
        settings["enabled"] = False
        private_json("ai-settings.json", settings)
        configure()
        recreate_api()
        print("AI disabled")
    elif args.action == "ai-status":
        settings = ai_settings()
        print("AI enabled:", settings.get("enabled") is True)
        print("AI worker running:", bool(ai_process()))
        print("AI model:", settings.get("model", ""))
    elif args.action == "candidate-up":
        start_candidate()
        wait_http(CANDIDATE_URL)
        print(CANDIDATE_URL + "/#/conversations")
    elif args.action == "candidate-status":
        print("Vue 3 candidate:", "running" if candidate_process() else "stopped")
        print(CANDIDATE_URL + "/#/conversations")
    elif args.action == "candidate-stop":
        info = candidate_process()
        if info:
            os.killpg(info["pid"], signal.SIGTERM)
            (RUNTIME / "candidate-process.json").unlink(missing_ok=True)
    elif args.action == "legacy-up":
        start_web()
        wait_http(WEB_URL)
        print(WEB_URL + "/#/conversations")
    elif args.action == "legacy-status":
        print("Vue 3 frontend (legacy alias):", "running" if candidate_process() else "stopped")
        print(WEB_URL + "/#/conversations")
    elif args.action == "legacy-stop":
        stop_process(candidate_process, "candidate-process.json")
        stop_process(web_process, "web-process.json")
    elif args.action == "up":
        if args.build or not (API / "target/xiaozhi-esp32-api.jar").exists():
            build()
        compose("up", "-d", "--wait", "mysql", "redis")
        if args.seed_local_console:
            seed_local_console()
        if realtime_settings().get("enabled") is True:
            realtime_jar_check(realtime_settings().get("server_vad_enabled") is True)
        if summary_settings().get("enabled") is True and ai_settings().get("enabled") is True:
            summary_jar_check()
        if ai_first_settings().get("enabled") is True and ai_first_settings().get("speech_enabled") is True:
            ai_first_optional_jar_check("speech"); speech_worker_python()
        if ai_first_settings().get("enabled") is True and ai_first_settings().get("skills_enabled") is True:
            ai_first_optional_jar_check("skills")
        if ai_first_settings().get("enabled") is True and ai_first_settings().get("runtime_control_enabled") is True:
            ai_first_optional_jar_check("runtime-control")
        stage_api_jar()
        compose("up", "-d", "--force-recreate", "api")
        print("Waiting for Dev API…", flush=True)
        wait_http(API_URL + "/user/pub-config")
        if media_settings().get("enabled") is True:
            start_media_worker()
        if ai_settings().get("enabled") is True:
            start_ai_worker()
            if summary_settings().get("enabled") is True:
                start_summary_worker()
        if realtime_settings().get("enabled") is True:
            start_realtime_worker()
        if ai_first_settings().get("enabled") is True and ai_first_settings().get("speech_enabled") is True:
            start_speech_worker()
        if (RUNTIME / "esp32-device-settings.json").exists():
            subprocess.run([str(ROOT.parent / ".venv/bin/python"), str(DEV / "esp32_services.py"), "start"], check=True)
        if not args.no_frontend:
            start_candidate()
            print("Waiting for Dev frontend…", flush=True)
            wait_http(CANDIDATE_URL)
            print(CANDIDATE_URL + "/#/conversations", flush=True)
        else:
            print(API_URL, flush=True)
    elif args.action == "stop":
        if (RUNTIME / "esp32-device-settings.json").exists():
            subprocess.run([str(ROOT.parent / ".venv/bin/python"), str(DEV / "esp32_services.py"), "stop"], check=True)
        stop_speech_worker()
        stop_summary_worker()
        stop_realtime_worker()
        stop_process(media_process, "media-worker-process.json")
        stop_process(ai_process, "ai-worker-process.json")
        stop_process(candidate_process, "candidate-process.json")
        stop_process(web_process, "web-process.json")
        compose("stop")
    else:
        compose("ps")
        print("Vue 3 frontend:", "running" if candidate_process() else "stopped")
        print(CANDIDATE_URL + "/#/conversations")


if __name__ == "__main__":
    main()
