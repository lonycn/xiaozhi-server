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
from urllib.parse import urlsplit

DEV = Path(__file__).resolve().parent
ROOT = DEV.parent
RUNTIME = DEV / ".runtime"
WEB = ROOT / "main/manager-web"
CANDIDATE = ROOT / "main/manager-web-next"
ANNOTATION_DEFAULT_FONT = WEB / "public/generator/static/fonts/noto_qwen.ttf"
CANDIDATE_URL = "http://127.0.0.1:18894"
API = ROOT / "main/manager-api"
WEB_URL = "http://127.0.0.1:18892"
API_URL = "http://127.0.0.1:18893/xiaozhi"


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
        "spring.data.redis.host": "redis",
        "spring.data.redis.port": 6379,
        "features.conversation-enabled": "true",
        "features.support-handoff-enabled": "true",
        "logging.level.xiaozhi": "INFO",
        "logging.level.liquibase": "WARN",
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


def media_settings():
    path = RUNTIME / "media-settings.json"
    if not path.exists():
        return {"enabled": False}
    data = json.loads(path.read_text())
    if not isinstance(data, dict) or not isinstance(data.get("enabled"), bool):
        raise RuntimeError("Invalid media settings")
    return data


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
    modules = CANDIDATE / "node_modules"
    if modules.is_symlink():
        raise RuntimeError("Linked Vue 3 node_modules are not supported")
    if not modules.is_dir():
        print("Installing locked frontend dependencies…", flush=True)
        with (RUNTIME / "install-web.log").open("w") as log:
            subprocess.run(["npm", "ci", "--no-audit", "--no-fund"], cwd=CANDIDATE, stdout=log, stderr=subprocess.STDOUT, check=True)


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
    if web_process():
        return
    with socket.socket() as check:
        if check.connect_ex(("127.0.0.1", 18892)) == 0:
            raise RuntimeError("Port 18892 belongs to another process")
    env = {**os.environ, "VUE_APP_API_BASE_URL": "/xiaozhi", "VUE_APP_API_PROXY_TARGET": "http://127.0.0.1:18893",
           "VUE_APP_CONVERSATION_ENABLED": "true", "VUE_APP_ENV_LABEL": "Dev", "NODE_OPTIONS": "--max-old-space-size=1536"}
    with (RUNTIME / "web.log").open("a") as log:
        process = subprocess.Popen(["npm", "run", "serve", "--", "--host", "127.0.0.1", "--port", "18892"],
                                   cwd=WEB, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    identity = process_identity(process.pid)
    (RUNTIME / "web-process.json").write_text(json.dumps({"pid": process.pid, "identity": identity}))


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
        raise RuntimeError("Install candidate dependencies in manager-web-next with npm ci; linked node_modules are not supported")
    with socket.socket() as check:
        if check.connect_ex(("127.0.0.1", 18894)) == 0:
            raise RuntimeError("Port 18894 belongs to another process")
    env = {**os.environ, "VITE_API_BASE_URL": "/xiaozhi",
           "VITE_API_PROXY_TARGET": "http://127.0.0.1:18893",
           "VITE_CONVERSATION_ENABLED": "true", "VITE_ENV_LABEL": "Dev Vue 3"}
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
    parser.add_argument("action", choices=["up", "stop", "status", "candidate-up", "candidate-status", "candidate-stop", "legacy-up", "legacy-status", "legacy-stop", "ai-enable", "ai-disable", "ai-status", "media-enable", "media-disable", "media-status", "annotations-enable", "annotations-disable", "annotations-status"])
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--seed-local-console", action="store_true")
    parser.add_argument("--storage", choices=["filesystem", "s3"])
    parser.add_argument("--storage-env", help="Existing private S3 configuration file; no bucket creation")
    parser.add_argument("--vision-model", help="Previously verified Bailian vision model for this Dev workspace")
    parser.add_argument("--omni-model", help="Explicit non-realtime qwen3.8-omni-flash; empty disables raw sound analysis")
    args = parser.parse_args()
    configure()
    if args.action == "media-enable":
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
    elif args.action == "ai-enable":
        model = export_ai_config() if args.omni_model is None else export_ai_config(args.omni_model)
        stop_process(ai_process, "ai-worker-process.json")
        settings = {"enabled": True, "model": model}
        omni = ai_settings().get("omni_model", "") if args.omni_model is None else args.omni_model
        if omni: settings["omni_model"] = omni
        private_json("ai-settings.json", settings)
        configure()
        recreate_api()
        start_ai_worker()
        start_candidate()
        wait_http(CANDIDATE_URL)
        print("AI enabled; Vue 3:", CANDIDATE_URL + "/#/conversations")
    elif args.action == "ai-disable":
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
        print("Vue 2 legacy:", "running" if web_process() else "stopped")
        print(WEB_URL + "/#/conversations")
    elif args.action == "legacy-stop":
        stop_process(web_process, "web-process.json")
    elif args.action == "up":
        if args.build or not (API / "target/xiaozhi-esp32-api.jar").exists():
            build()
        compose("up", "-d", "--wait", "mysql", "redis")
        if args.seed_local_console:
            seed_local_console()
        stage_api_jar()
        compose("up", "-d", "--force-recreate", "api")
        print("Waiting for Dev API…", flush=True)
        wait_http(API_URL + "/user/pub-config")
        if media_settings().get("enabled") is True:
            start_media_worker()
        if ai_settings().get("enabled") is True:
            start_ai_worker()
        start_candidate()
        print("Waiting for Dev frontend…", flush=True)
        wait_http(CANDIDATE_URL)
        print(CANDIDATE_URL + "/#/conversations", flush=True)
    elif args.action == "stop":
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
