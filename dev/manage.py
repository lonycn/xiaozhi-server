#!/usr/bin/env python3
"""Start/stop this checkout's isolated Dev stack. Never changes the device or live console."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import time
import urllib.error
import urllib.request

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
        "logging.level.xiaozhi": "INFO",
        "logging.level.liquibase": "WARN",
    }
    (RUNTIME / "application-dev.properties").write_text("\n".join(f"{key}={value}" for key, value in properties.items()) + "\n")
    for name in ("credentials.json", "compose.env", "application-dev.properties"):
        (RUNTIME / name).chmod(0o600)
    for name in ("logs", "uploadfile"):
        (RUNTIME / name).mkdir(exist_ok=True)


def compose(*args, **kwargs):
    return subprocess.run(["docker", "compose", "--env-file", str(RUNTIME / "compose.env"),
                           "-f", str(DEV / "compose.yml"), *args], check=True, **kwargs)


def build():
    env = os.environ.copy()
    java = Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")
    if "JAVA_HOME" not in env and java.exists():
        env["JAVA_HOME"] = str(java)
    print("Building Dev API…", flush=True)
    with (RUNTIME / "build-api.log").open("w") as log:
        subprocess.run(["mvn", "-B", "-DskipTests", "package"], cwd=API, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    if not (WEB / "node_modules").exists():
        print("Installing locked frontend dependencies…", flush=True)
        with (RUNTIME / "install-web.log").open("w") as log:
            subprocess.run(["npm", "ci", "--no-audit", "--no-fund"], cwd=WEB, stdout=log, stderr=subprocess.STDOUT, check=True)


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
        info = json.loads(path.read_text())
        if info["identity"] and process_identity(info["pid"]) == info["identity"]:
            return info
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


def wait_http(url, timeout=90):
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["up", "stop", "status", "candidate-up", "candidate-status", "candidate-stop"])
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--seed-local-console", action="store_true")
    args = parser.parse_args()
    configure()
    if args.action == "candidate-up":
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
    elif args.action == "up":
        if args.build or not (API / "target/xiaozhi-esp32-api.jar").exists():
            build()
        compose("up", "-d", "--wait", "mysql", "redis")
        if args.seed_local_console:
            seed_local_console()
        compose("up", "-d", "--force-recreate", "api")
        print("Waiting for Dev API…", flush=True)
        wait_http(API_URL + "/user/pub-config")
        start_web()
        print("Waiting for Dev frontend…", flush=True)
        wait_http(WEB_URL)
        print(WEB_URL + "/#/conversations", flush=True)
    elif args.action == "stop":
        info = web_process()
        if info:
            os.killpg(info["pid"], signal.SIGTERM)
            (RUNTIME / "web-process.json").unlink(missing_ok=True)
        compose("stop")
    else:
        compose("ps")
        print("Frontend process:", "running" if web_process() else "stopped")
        print(WEB_URL + "/#/conversations")


if __name__ == "__main__":
    main()
