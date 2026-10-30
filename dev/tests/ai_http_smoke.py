#!/usr/bin/env python3
"""Real HTTP AI protocol checks, with append-only fixtures in a disposable database.

The API must point to the same test database, enable conversation/support/ai-text,
and use AI_TEST_WORKER_KEY. No provider is called by this script.
"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:19893/xiaozhi')
    parser.add_argument('--container', default='xiaozhi-ai-b2-test')
    parser.add_argument('--database', default='xiaozhi_ai_test_full_r2')
    parser.add_argument('--fixture-file')
    args = parser.parse_args()
    url = urllib.parse.urlsplit(args.url)
    if url.scheme != 'http' or url.hostname != '127.0.0.1' or not re.fullmatch(r'xiaozhi_ai_test_[a-z0-9_]+', args.database):
        raise SystemExit('Only disposable loopback xiaozhi_ai_test_* databases are allowed')
    if not re.fullmatch(r'xiaozhi-ai-[a-z0-9-]+-test', args.container):
        raise SystemExit('Use an explicitly named xiaozhi-ai-*-test container')
    key = os.environ.get('AI_TEST_WORKER_KEY', 'b2-isolated-worker-key-not-for-production')

    def sql(statement):
        return subprocess.run(['docker', 'exec', '-i', args.container, 'sh', '-c',
                               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 -N ' + args.database],
                              input=statement, text=True, capture_output=True, check=True).stdout.strip()

    base = 8_200_000_000_000_000_000 + int(time.time() * 1000) * 10
    accounts = {}
    for n, name in enumerate(('admin', 'owner', 'staff', 'stranger')):
        actor, token = base + n, secrets.token_hex(24)
        sql(f"INSERT INTO sys_user(id,username,status,super_admin) VALUES({actor},'b2-http-{name}-{actor}',1,{1 if name == 'admin' else 0});"
            f"INSERT INTO sys_user_token(id,user_id,token,expire_date) VALUES({actor},{actor},'{token}',DATE_ADD(NOW(),INTERVAL 1 DAY));")
        accounts[name] = {'id': str(actor), 'token': token, 'username': f'b2-http-{name}-{actor}'}
    checks = 0

    def call(method, path, actor=None, body=None, expected=200, worker=False, worker_key=None):
        nonlocal checks
        headers = {'Content-Type': 'application/json'}
        if actor: headers['Authorization'] = 'Bearer ' + accounts[actor]['token']
        if worker: headers['X-AI-Worker-Key'] = key if worker_key is None else worker_key
        request = urllib.request.Request(args.url + path, method=method, headers=headers,
                                         data=None if body is None else json.dumps(body).encode())
        try: response = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as error: response = error
        data = json.loads(response.read())
        assert response.status == expected, (method, path, response.status, data)
        assert data['code'] == (0 if expected == 200 else expected), data
        checks += 1
        return data.get('data')

    def worker(path, body, expected=200):
        return call('POST', '/internal/ai-worker' + path, body=body, expected=expected, worker=True)

    def message(key, text): return {'client_message_id': key, 'parts': [{'type': 'text', 'text': text}]}
    def latest(route): return call('GET', route + '/runs', 'owner')['items'][0]
    def sse(route, after='0', actor='owner', expected=200):
        nonlocal checks
        request = urllib.request.Request(args.url + route + '/stream', headers={
            'Authorization': 'Bearer ' + accounts[actor]['token'], 'Last-Event-ID': after, 'Accept': 'text/event-stream'})
        try: response = urllib.request.urlopen(request, timeout=8)
        except urllib.error.HTTPError as error: response = error
        assert response.status == expected, response.status
        if expected != 200:
            assert response.headers['Content-Type'].startswith('application/json')
            assert json.loads(response.read())['code'] == expected
            response.close(); checks += 1; return []
        assert response.headers['Content-Type'].startswith('text/event-stream')
        events = []
        try:
            for _ in range(30):
                line = response.readline().decode().strip()
                if line.startswith('data: '):
                    event = json.loads(line[6:]); assert int(event['sequence']) > int(after); events.append(event)
                    if len(events) == 2: break
        finally: response.close()
        assert len(events) == 2
        checks += 1; return events

    call('GET', '/api/v1/ai/conversations', expected=401)
    call('POST', '/internal/ai-worker/claim', body={'worker_id': 'test'}, expected=401)
    call('POST', '/internal/ai-worker/claim', 'owner', {'worker_id': 'test'}, expected=401)
    call('POST', '/internal/ai-worker/claim', body={'worker_id': 'test'}, worker=True, worker_key='wrong', expected=401)
    call('GET', '/api/v1/ai/conversations', worker=True, expected=401)
    conversation = call('POST', '/api/v1/ai/conversations', 'owner', {'title': 'B2 isolated HTTP'})['id']
    assert sql(f"SELECT COUNT(*) FROM ai_conversation WHERE id='{conversation}';") == '1', 'API uses a different database'
    route = '/api/v1/ai/conversations/' + conversation
    sent = call('POST', route + '/messages', 'owner', message('first', 'isolated streaming protocol'))
    assert call('POST', route + '/messages', 'owner', message('first', 'isolated streaming protocol'))['id'] == sent['id']
    runs = call('GET', route + '/runs', 'owner')['items']; assert len(runs) == 1
    assert not any('lease' in field or 'key' in field for field in runs[0])
    run_route = route + '/runs/' + runs[0]['run_id']
    call('GET', run_route, 'stranger', expected=404)
    call('POST', run_route + '/cancel', 'stranger', {}, expected=404)
    call('POST', run_route + '/retry', 'owner', {'sender_type': 'admin'}, expected=400)
    events = sse(route); sse(route, events[0]['sequence']); sse(route, actor='stranger', expected=404)
    task = worker('/claim', {'worker_id': 'http-test'})
    assert task['run_id'] == runs[0]['run_id'] and len(task['lease_token']) == 64
    path = '/runs/' + task['run_id']; token = {'lease_token': task['lease_token']}
    worker(path + '/delta', {**token, 'sequence': 0.5, 'text': 'invalid'}, expected=400)
    worker(path + '/delta', {**token, 'sequence': 1, 'text': '第一段'})
    event_count = sql(f"SELECT last_event_seq FROM ai_conversation WHERE id='{conversation}';")
    worker(path + '/delta', {**token, 'sequence': 1, 'text': '第一段'})
    assert sql(f"SELECT last_event_seq FROM ai_conversation WHERE id='{conversation}';") == event_count
    worker(path + '/delta', {**token, 'sequence': 1, 'text': '冲突'}, expected=409)
    worker(path + '/delta', {**token, 'sequence': 3, 'text': '乱序'}, expected=409)
    assert worker(path + '/heartbeat', token)['active'] is True
    worker(path + '/complete', {**token, 'last_sequence': 2, 'finish_reason': 'stop', 'usage': None}, expected=409)
    worker(path + '/complete', {**token, 'last_sequence': 1, 'finish_reason': 'stop',
                               'usage': {'input_tokens': 3, 'output_tokens': 2, 'total_tokens': 5}, 'provider_request_id': 'synthetic-http-protocol'})
    messages = call('GET', route + '/messages', 'owner')['items']
    assert len(messages) == 2 and messages[1]['id'] == task['output_message_id'] and messages[1]['status'] == 'completed'
    assert messages[1]['parts'][0]['text'] == '第一段'
    call('POST', run_route + '/retry', 'owner', {}, expected=409)

    call('POST', route + '/messages', 'owner', message('second', 'cancel and retry'))
    second = worker('/claim', {'worker_id': 'http-test'}); second_path = '/runs/' + second['run_id']; old = {'lease_token': second['lease_token']}
    worker(second_path + '/delta', {**old, 'sequence': 1, 'text': 'partial'})
    second_public = route + '/runs/' + second['run_id']
    call('POST', second_public + '/cancel', 'owner', {})
    worker(second_path + '/delta', {**old, 'sequence': 2, 'text': 'late'}, expected=409)
    call('POST', second_public + '/retry', 'owner', {})
    retry = worker('/claim', {'worker_id': 'http-test'}); assert retry['attempt'] == 2 and retry['output_message_id'] == second['output_message_id']
    worker(second_path + '/heartbeat', old, expected=409)
    worker(second_path + '/fail', {'lease_token': retry['lease_token'], 'error_code': 'PROVIDER_TIMEOUT'})
    call('POST', second_public + '/retry', 'owner', {})
    third = worker('/claim', {'worker_id': 'http-test'}); assert third['attempt'] == 3
    worker(second_path + '/delta', {'lease_token': third['lease_token'], 'sequence': 1, 'text': 'recovered'})
    worker(second_path + '/complete', {'lease_token': third['lease_token'], 'last_sequence': 1, 'finish_reason': 'stop', 'usage': None})

    call('POST', route + '/messages', 'owner', message('handoff', 'human takes over'))
    interrupted = worker('/claim', {'worker_id': 'http-test'})
    request = call('POST', route + '/handoff', 'owner', {'client_request_id': 'b2-handoff', 'requested_mode': 'text'})
    assert latest(route)['status'] == 'canceled'
    worker('/runs/' + interrupted['run_id'] + '/delta', {'lease_token': interrupted['lease_token'], 'sequence': 1, 'text': 'must not publish'}, expected=409)
    call('POST', route + '/messages', 'owner', message('human-only', 'queued human message'))
    assert worker('/claim', {'worker_id': 'http-test'}) is None
    call('POST', '/api/v1/support/requests/' + request['request_id'] + '/cancel', 'owner', {})
    call('POST', route + '/resume-ai', 'owner', {})
    assert worker('/claim', {'worker_id': 'http-test'}) is None
    if args.fixture_file:
        path = Path(args.fixture_file)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w') as output: json.dump(accounts, output)
    print(json.dumps({'result': 'passed', 'http_checks': checks, 'isolated_database': args.database,
                      'conversation_id': conversation, 'provider_calls': 0}))


if __name__ == '__main__': main()
