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
    (RUNTIME / "application-dev.properties").write_text("\n".join(f"{key}={value}" for key, value in properties.items()) + "\n")
    for name in ("credentials.json", "compose.env", "application-dev.properties"):
        (RUNTIME / name).chmod(0o600)
    for name in ("logs", "uploadfile"):
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


def private_json(name, data):
    path = RUNTIME / name
    # Create privately even when called outside configure().
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as output:
        json.dump(data, output)
    path.chmod(0o600)


def export_ai_config():
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
    parser.add_argument("action", choices=["up", "stop", "status", "candidate-up", "candidate-status", "candidate-stop", "legacy-up", "legacy-status", "legacy-stop", "ai-enable", "ai-disable", "ai-status"])
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--seed-local-console", action="store_true")
    args = parser.parse_args()
    configure()
    if args.action == "ai-enable":
        model = export_ai_config()
        stop_process(ai_process, "ai-worker-process.json")
        private_json("ai-settings.json", {"enabled": True, "model": model})
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
        if ai_settings().get("enabled") is True:
            start_ai_worker()
        start_candidate()
        print("Waiting for Dev frontend…", flush=True)
        wait_http(CANDIDATE_URL)
        print(CANDIDATE_URL + "/#/conversations", flush=True)
    elif args.action == "stop":
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
