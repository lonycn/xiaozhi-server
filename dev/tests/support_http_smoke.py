#!/usr/bin/env python3
"""Exercise real Shiro + HTTP + MySQL in a disposable, fully migrated test database.

Start the API with both conversation/support feature flags enabled and point it at
the same database. This script refuses application databases and non-loopback URLs.
It creates synthetic accounts/tokens in that disposable database, never prints tokens,
and leaves evidence for inspection until the test container is removed.
"""
import argparse
import json
import re
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:19893/xiaozhi")
    parser.add_argument("--container", default="xiaozhi-support-b1-test")
    parser.add_argument("--database", default="xiaozhi_support_test_full")
    args = parser.parse_args()
    address = urllib.parse.urlparse(args.url)
    if address.hostname != "127.0.0.1" or address.scheme != "http" or not re.fullmatch(
            r"xiaozhi_support_test_[a-z0-9_]+", args.database):
        raise SystemExit("Use a disposable xiaozhi_support_test_* database and loopback API")
    if not re.fullmatch(r"xiaozhi-support-[a-z0-9-]+-test", args.container):
        raise SystemExit("Use an explicitly named xiaozhi-support-*-test container")

    def sql(statement):
        result = subprocess.run([
            "docker", "exec", "-i", args.container, "sh", "-c",
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N ' + args.database,
        ], input=statement, text=True, capture_output=True, check=True)
        return result.stdout.strip()

    # Bind the advertised HTTP target to this specific database before creating fixtures.
    existing = sql("SELECT COUNT(*) FROM support_request;")
    assert existing.isdigit()
    base = 8_000_000_000_000_000_000 + int(time.time() * 1000) * 10
    users = {name: base + n for n, name in enumerate(("admin", "owner", "staff", "expert", "stranger"))}
    tokens = {name: secrets.token_hex(24) for name in users}
    for name, actor in users.items():
        sql(f"INSERT INTO sys_user(id,username,status,super_admin) VALUES "
            f"({actor},'b1-{actor}',1,{1 if name == 'admin' else 0});"
            f"INSERT INTO sys_user_token(id,user_id,token,expire_date) VALUES "
            f"({actor},{actor},'{tokens[name]}',DATE_ADD(NOW(),INTERVAL 1 DAY));")

    checks = 0

    def call(method, path, actor=None, body=None, status=200):
        nonlocal checks
        headers = {"Content-Type": "application/json"}
        if actor:
            headers["Authorization"] = "Bearer " + tokens[actor]
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(args.url + path, data=data, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        payload = json.loads(response.read())
        assert response.status == status, (method, path, response.status, payload)
        assert payload["code"] == (0 if status == 200 else status), payload
        checks += 1
        return payload.get("data")

    call("GET", "/api/v1/support/queue", status=401)
    call("GET", "/api/v1/support/queue", "stranger", status=403)
    assert call("GET", "/api/v1/support/me", "owner") is None
    call("GET", "/api/v1/support/agents", "owner", status=403)
    for name, role in (("staff", "support_agent"), ("expert", "expert")):
        call("PUT", f"/api/v1/support/agents/{users[name]}", "owner",
             {"role": role, "enabled": True, "capacity_text": 1}, status=403)
        call("PUT", f"/api/v1/support/agents/{users[name]}", "admin",
             {"role": role, "enabled": True, "capacity_text": 1})
        call("POST", "/api/v1/support/presence", name, {"accepting": True})
        assert call("GET", "/api/v1/support/me", name)["user_id"] == str(users[name])
    agents = call("GET", "/api/v1/support/agents?limit=1&before=" + str(users["expert"] + 1), "admin")
    assert agents["has_more"] and agents["items"][0]["user_id"] == str(users["expert"])
    assert call("GET", "/api/v1/support/agents?limit=1&before=" + agents["next_cursor"], "admin")["items"][0]["user_id"] == str(users["staff"])
    conversation = call("POST", "/api/v1/ai/conversations", "owner", {"title": "B1 isolated HTTP smoke"})["id"]
    assert sql(f"SELECT COUNT(*) FROM ai_conversation WHERE id='{conversation}';") == "1", "API uses a different database"
    route = f"/api/v1/ai/conversations/{conversation}"
    text = lambda key, value: {"client_message_id": key, "parts": [{"type": "text", "text": value}]}
    call("POST", route + "/messages", "owner", text("question", "请人工协助"))
    handoff = {"client_request_id": "handoff", "requested_mode": "text"}
    call("POST", route + "/handoff", "owner", {**handoff, "owner_user_id": users["owner"]}, status=400)
    request = call("POST", route + "/handoff", "owner", handoff)["request_id"]
    assert call("POST", route + "/handoff", "owner", handoff)["request_id"] == request
    assert call("POST", route + "/handoff", "owner", {**handoff, "client_request_id": "alias"})["request_id"] == request
    support = f"/api/v1/support/requests/{request}"
    assert any(item["request_id"] == request for item in call("GET", "/api/v1/support/queue", "staff")["items"])
    call("POST", support + "/claim", "staff", {})
    call("POST", support + "/claim", "staff", {})
    assert call("GET", "/api/v1/support/assigned", "staff")["items"][0]["request_id"] == request
    assert call("GET", "/api/v1/support/assigned", "expert")["items"] == []
    call("POST", support + "/claim", "expert", {}, status=409)
    call("GET", support, "stranger", status=403)
    call("GET", route + "/messages", "stranger", status=404)
    reply = call("POST", support + "/messages", "staff", text("reply", "先检查接线"))
    assert reply["sender_type"] == "support_agent" and reply["sender_id"] == str(users["staff"])
    assert call("POST", support + "/messages", "staff", text("reply", "先检查接线"))["id"] == reply["id"]
    call("POST", support + "/messages", "staff", {**text("spoof", "回复"), "sender_type": "expert"}, status=400)
    call("POST", support + "/cancel", "owner", {}, status=409)
    call("POST", route + "/resume-ai", "owner", {}, status=409)
    call("POST", support + "/requeue", "staff", {"reason": "转交专家"})
    assert call("GET", "/api/v1/support/assigned", "staff")["items"] == []
    cutoff = call("GET", route + "/messages", "staff")["items"]
    call("POST", support + "/claim", "expert", {})
    expert = call("POST", support + "/messages", "expert", text("expert-reply", "专家指导"))
    assert expert["sender_type"] == "expert"
    assert call("GET", route + "/messages", "staff")["items"] == cutoff
    call("GET", support, "staff", status=404)
    call("GET", route, "staff", status=404)
    call("POST", support + "/messages", "staff", text("late", "迟到回复"), status=403)
    sql(f"UPDATE support_agent_profile SET enabled=0 WHERE user_id={users['expert']};")
    assert call("GET", "/api/v1/support/me", "expert") is None
    call("GET", "/api/v1/support/assigned", "expert", status=403)
    call("POST", support + "/messages", "expert", text("disabled", "禁用回复"), status=403)
    call("GET", route + "/messages", "expert", status=404)
    call("POST", support + "/requeue", "admin", {"reason": "禁用账号恢复排队"})
    call("POST", support + "/claim", "staff", {})
    call("POST", support + "/resolve", "staff", {"reason": "完成"})
    call("POST", support + "/resolve", "staff", {"reason": "完成"})
    assert call("POST", route + "/handoff", "owner", {**handoff, "client_request_id": "alias"})["status"] == "resolved"
    assert call("POST", route + "/resume-ai", "owner", {})["state"] == "AI_ACTIVE"
    assert call("GET", route + "/messages", "owner")["items"][-1]["id"] == expert["id"]
    print(json.dumps({"result": "passed", "http_checks": checks, "isolated_database": args.database,
                      "conversation_id": conversation}, ensure_ascii=False))


if __name__ == "__main__":
    main()
