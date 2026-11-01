#!/usr/bin/env python3
"""Bounded real-HTTP + authorized Bailian tool acceptance, in a NEW retained schema.

Never imports, resets or cleans an existing database. Secrets remain in memory or
0600 private configuration. This runner uses only the harmless opt-in technical
tool; it never configures a business adapter or sends customer business data.

Run after Maven has compiled current classes (no concurrent target writes):
  <existing-ai-venv>/bin/python <this-file> --repo <checkout> --work <private-work-dir>
The existing native test MySQL metadata and provider config may be selected with
--mysql-info, --mysql-credential-source and --provider-config. No key is printed.
"""
import argparse
import asyncio
import base64
import contextlib
import datetime
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile


def private_json(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        json.dump(value, output, ensure_ascii=False, indent=2)


def available_port():
    with socket.socket() as candidate:
        candidate.bind(('127.0.0.1', 0))
        return candidate.getsockname()[1]


class Acceptance:
    def __init__(self, args):
        self.args, self.repo = args, args.repo.resolve()
        tag = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d_%H%M%S') + '_' + secrets.token_hex(3)
        self.schema = 'xiaozhi_agent_tool_test_http_' + tag
        self.folder = args.work.resolve() / ('http-' + tag)
        self.folder.mkdir(parents=True, mode=0o700)
        self.native = json.loads(args.mysql_info.read_text())
        source = args.mysql_credential_source.read_text()
        match = re.search(r"SPRING_DATASOURCE_DRUID_PASSWORD='([^']+)'", source)
        if not match:
            raise RuntimeError('Existing native test credential source is unsupported')
        self.sql_env = dict(os.environ, MYSQL_PWD=match.group(1))
        self.mysql = ['/opt/homebrew/bin/mysql', '--no-defaults', '--socket=' + self.native['socket'], '-uroot', '-N', '-B']
        self.worker_key, self.secret = secrets.token_urlsafe(48), secrets.token_bytes(32)
        self.key_id, self.issuer = 'xiaozhi-http-tool-test', 'xiaozhi-http-tool-test'
        self.port, self.redis_port = available_port(), available_port()
        self.base = 'http://127.0.0.1:' + str(self.port) + '/xiaozhi'
        self.processes, self.checks = [], []
        self.proof = dict(schema=self.schema, api=self.base, business_data_sent=False,
                          business_adapter_configured=False, actual_provider=False, checks=self.checks)

    def sql(self, statement, database=True):
        if not re.fullmatch(r'xiaozhi_agent_tool_test_http_[a-z0-9_]+', self.schema):
            raise RuntimeError('Unsafe schema')
        command = self.mysql + (['-D', self.schema] if database else [])
        result = subprocess.run(command, input=statement, env=self.sql_env, capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError('Isolated MySQL operation failed; no SQL or credentials printed')
        return result.stdout.strip()

    def start(self):
        # CREATE without IF NOT EXISTS refuses every pre-existing schema.
        self.sql('CREATE DATABASE ' + self.schema + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;', False)
        properties = {}
        source = self.repo / 'dev/.runtime/application-dev.properties'
        if source.exists():
            properties.update(line.split('=', 1) for line in source.read_text().splitlines()
                              if '=' in line and not line.startswith('#'))
        properties.update({
            'server.address': '127.0.0.1', 'server.port': str(self.port),
            'spring.datasource.druid.url': 'jdbc:mysql://127.0.0.1:' + str(self.native['port']) + '/' + self.schema + '?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
            'spring.datasource.druid.username': 'root', 'spring.datasource.druid.password': self.sql_env['MYSQL_PWD'],
            'spring.datasource.druid.initial-size': '1', 'spring.datasource.druid.min-idle': '1', 'spring.datasource.druid.max-active': '8',
            'spring.data.redis.host': '127.0.0.1', 'spring.data.redis.port': str(self.redis_port),
            'features.conversation-enabled': 'true', 'features.ai-text-enabled': 'true', 'features.agent-tools-enabled': 'true',
            'agent-tools.technical-local-enabled': 'true', 'features.external-identity-enabled': 'true',
            'external-identity.renewal-enabled': 'true', 'external-identity.renewal-max-seconds': '300',
            'external-identity.token-seconds': '300', 'external-business.enabled': 'false', 'external-business.sources-json': '',
            'external-identity.keys-json': json.dumps({self.key_id: dict(issuer=self.issuer, secret_b64=base64.b64encode(self.secret).decode())}, separators=(',', ':')),
            'ai.worker.key': self.worker_key, 'ai.execution.model': self.provider_config()['model'],
            'logging.file.path': str(self.folder / 'logs'), 'media.storage.root': str(self.folder / 'private-media'),
        })
        for feature in ('attachments', 'annotations', 'support-handoff', 'support-media', 'realtime-ai',
                        'external-realtime-media', 'realtime-server-vad', 'support-summary-assistance'):
            properties['features.' + feature + '-enabled'] = 'false'
        config = self.folder / 'application-isolated.properties'
        descriptor = os.open(config, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w') as output:
            output.write('\n'.join(key + '=' + str(value) for key, value in properties.items()) + '\n')
        libraries = self.folder / 'libraries'
        if self.args.compiled_runtime:
            shutil.copytree(self.args.compiled_runtime / 'libraries', libraries)
        else:
            libraries.mkdir(mode=0o700)
            with zipfile.ZipFile(self.repo / 'main/manager-api/target/xiaozhi-esp32-api.jar') as jar:
                for member in jar.infolist():
                    if member.filename.startswith('BOOT-INF/lib/') and member.filename.endswith('.jar'):
                        (libraries / Path(member.filename).name).write_bytes(jar.read(member))
        # Snapshot compiled classes so later Maven work cannot alter this acceptance process.
        classes = self.folder / 'classes'
        shutil.copytree(self.args.compiled_runtime / 'classes' if self.args.compiled_runtime else self.repo / 'main/manager-api/target/classes', classes)
        self.proof['compiled_class_hashes'] = {name: hashlib.sha256((classes / name).read_bytes()).hexdigest() for name in (
            'xiaozhi/modules/agenttool/AgentToolService.class', 'xiaozhi/modules/ai/AiExecutionService.class')}
        classpath = str(classes) + ':' + str(libraries / '*')
        self.launch('redis', ['/opt/homebrew/bin/redis-server', '--bind', '127.0.0.1', '--port', str(self.redis_port),
                              '--save', '', '--appendonly', 'no', '--dir', str(self.folder)])
        self.launch('api', ['/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java', '-Xms64m', '-Xmx512m',
                            '-cp', classpath, 'xiaozhi.AdminApplication', '--spring.config.additional-location=file:' + str(config)])
        for _ in range(120):
            if self.processes[-1].poll() is not None:
                raise RuntimeError('Isolated API exited; inspect private api.log')
            try:
                status, payload = self.http('GET', '/user/pub-config')
                if status == 200:
                    self.checks.append('isolated_api_started_with_additive_migrations')
                    return
            except Exception:
                pass
            time.sleep(.5)
        raise RuntimeError('Isolated API did not become ready within 60 seconds')

    def launch(self, name, arguments):
        path = self.folder / (name + '.log')
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w') as output:
            self.processes.append(subprocess.Popen(arguments, cwd=self.folder, stdout=output, stderr=subprocess.STDOUT, start_new_session=True))

    def provider_config(self):
        source = json.loads(self.args.provider_config.read_text())
        return {key: source[key] for key in ('model', 'base_url', 'api_key') if key in source} | {
            'workspace_id': source.get('workspace_id', '')}

    def http(self, method, path, body=None, token=None, worker=False, signed=False):
        raw = body if isinstance(body, bytes) else None if body is None else json.dumps(body, ensure_ascii=False, separators=(',', ':')).encode()
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if worker:
            headers['X-AI-Worker-Key'] = self.worker_key
        if signed:
            timestamp, nonce = str(int(time.time())), secrets.token_hex(16)
            canonical = '\n'.join(('POST', '/xiaozhi' + path, timestamp, nonce, hashlib.sha256(raw).hexdigest()))
            headers.update({'X-External-Key-Id': self.key_id, 'X-External-Timestamp': timestamp, 'X-External-Nonce': nonce,
                            'X-External-Signature': hmac.new(self.secret, canonical.encode(), hashlib.sha256).hexdigest()})
        request = urllib.request.Request(self.base + path, data=raw, method=method, headers=headers)
        try:
            response = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            data = response.read()
            return response.status, json.loads(data) if data else None

    def ok(self, method, path, body=None, **options):
        status, payload = self.http(method, path, body, **options)
        if status != 200 or not isinstance(payload, dict) or payload.get('code') != 0:
            code = payload.get('msg') if isinstance(payload, dict) else 'HTTP_ERROR'
            raise RuntimeError('HTTP acceptance failed: ' + method + ' ' + path.split('?')[0] + ' status=' + str(status) + ' code=' + str(code))
        return payload['data']

    def login(self, session):
        self.subject = 'technical-' + self.schema[-12:]
        body = dict(external_subject=self.subject, source_session_id=session, source_expires_at=str(int(time.time()) + 3600),
                    display_name='隔离技术验收', tenant=dict(type='company', id='9001'),
                    scope=dict(project_ids=[], device_ids=[]))
        issued = self.ok('POST', '/api/v1/external/identity/issue', body, signed=True)
        return self.ok('POST', '/api/v1/external/identity/exchange', dict(ticket=issued['ticket']))['token']

    def enqueue(self, token, text):
        conversation = self.ok('POST', '/api/v1/ai/conversations', dict(title='Agent 工具隔离技术验收'), token=token)['id']
        route = '/api/v1/ai/conversations/' + conversation
        self.ok('POST', route + '/messages', dict(client_message_id=secrets.token_hex(8), parts=[dict(type='text', text=text)]), token=token)
        task = self.ok('POST', '/internal/ai-worker/claim', dict(worker_id='technical-http-acceptance'), worker=True)
        if not task or task['conversation_id'] != conversation:
            raise RuntimeError('Claim did not return this isolated test conversation')
        self.proof.setdefault('claim_wire_types', {key: type(task.get(key)).__name__ for key in ('control_epoch', 'attempt', 'lease_seconds')})
        return route, task

    def tool_body(self, task, call_id):
        return dict(lease_token=task['lease_token'], attempt=int(task['attempt']), control_epoch=int(task['control_epoch']), round=1,
                    calls=[dict(provider_call_id=call_id, name='server_capabilities', arguments={})])

    def denied_callback(self, task, action, body):
        status, payload = self.http('POST', '/internal/ai-worker/runs/' + task['run_id'] + '/' + action, body, worker=True)
        if status not in (401, 403, 409):
            raise RuntimeError('Stale/revoked callback was not fenced: ' + action + ' status=' + str(status))

    def protocol_acceptance(self):
        token = self.login('renewal')
        capabilities = self.ok('GET', '/api/v1/app/capabilities', token=token)
        flags = capabilities.get('features', {})
        if flags.get('agent_text_tools') is not True or flags.get('external_token_renewal') is not True:
            raise RuntimeError('External Bearer app capability contract did not expose enabled flags')
        for options in ({}, {'worker': True}):
            status, _ = self.http('GET', '/api/v1/app/capabilities', **options)
            if status != 401:
                raise RuntimeError('App capabilities accepted non-user credentials')
        self.checks.append('http_app_capabilities_accepts_external_bearer_and_rejects_anonymous_worker_key')
        route, task = self.enqueue(token, '仅用于技术验收，暂不请求模型。')
        endpoint = '/internal/ai-worker/runs/' + task['run_id']
        body = self.tool_body(task, 'same-bearer-renewal')
        invalid = json.loads(json.dumps(body))
        invalid['calls'][0]['arguments'] = {'url': 'https://must-not-dispatch.invalid', 'user_id': 'invented'}
        status, _ = self.http('POST', endpoint + '/tools', invalid, worker=True)
        if status != 400:
            raise RuntimeError('Invalid/trusted-actor arguments were not rejected as HTTP400')
        raw = json.dumps(body, separators=(',', ':')).replace('"arguments":{}', '"arguments":{"x":1,"x":2}').encode()
        status, _ = self.http('POST', endpoint + '/tools', raw, worker=True)
        if status != 400:
            raise RuntimeError('Duplicate raw JSON was not rejected as HTTP400')
        status, _ = self.http('POST', endpoint + '/tools', b' ' * 32769, worker=True)
        if status != 413:
            raise RuntimeError('Oversized raw tool body was not rejected as HTTP413')
        if self.sql("SELECT COUNT(*) FROM agent_tool_call WHERE run_id='" + task['run_id'] + "'") != '0':
            raise RuntimeError('Rejected proposals were dispatched')
        self.checks.append('http_raw_duplicates_invalid_actor_args_and_oversized_body_rejected_before_dispatch')
        first = self.ok('POST', endpoint + '/tools', body, worker=True)
        if first['results'][0]['status'] != 'succeeded':
            raise RuntimeError('Technical local tool did not execute')
        me = self.ok('GET', '/api/v1/external/identity/me', token=token)
        original_hash = self.sql("SELECT token_hash FROM external_access_token WHERE id=" + str(int(me['token_id'])))
        old_expiry = self.sql("SELECT UNIX_TIMESTAMP(expires_at) FROM external_access_token WHERE id=" + str(int(me['token_id'])))
        time.sleep(1.1)
        unsigned = dict(external_subject=self.subject, source_session_id='renewal', token_id=str(me['token_id']),
                        tenant=me['tenant'], scope=me['scope'], requested_lifetime_seconds=300)
        status, _ = self.http('POST', '/api/v1/external/identity/renew', unsigned)
        if status != 401:
            raise RuntimeError('Renewal accepted missing producer signature')
        renewed = self.ok('POST', '/api/v1/external/identity/renew', dict(external_subject=self.subject, source_session_id='renewal',
                          token_id=str(me['token_id']), tenant=me['tenant'], scope=me['scope'], requested_lifetime_seconds=300), signed=True)
        new_hash = self.sql("SELECT token_hash FROM external_access_token WHERE id=" + str(int(me['token_id'])))
        new_expiry = self.sql("SELECT UNIX_TIMESTAMP(expires_at) FROM external_access_token WHERE id=" + str(int(me['token_id'])))
        if original_hash != new_hash or float(new_expiry) <= float(old_expiry) or renewed['token_id'] != me['token_id']:
            raise RuntimeError('Renewal replaced the bound Bearer or failed to extend expiry')
        replay = self.ok('POST', endpoint + '/tools', body, worker=True)
        if replay != first:
            raise RuntimeError('Renewed same-Bearer replay differed')
        self.checks.append('http_same_exact_bearer_renewal_keeps_existing_run_and_cached_result')
        missing = dict(lease_token=task['lease_token'], attempt=int(task['attempt']), control_epoch=int(task['control_epoch']), round=2,
                       calls=[dict(provider_call_id='business-not-configured', name='get_device', arguments=dict(device_id='unconfigured'))])
        failure = self.ok('POST', endpoint + '/tools', missing, worker=True)['results'][0]
        if failure['error_code'] != 'BUSINESS_NOT_CONFIGURED' or failure['status'] != 'failed' or 'data' in failure['output']:
            raise RuntimeError('Unconfigured source returned fabricated business data')
        self.checks.append('http_unconfigured_business_tool_returns_explicit_error_without_fake_records')
        # Source revocation blocks the exact old context at tool replay and output publication.
        status, _ = self.http('POST', '/api/v1/external/identity/revoke', dict(external_subject=self.subject, source_session_id='renewal'), signed=True)
        if status != 204:
            raise RuntimeError('Source revocation failed')
        self.denied_callback(task, 'tools', body)
        self.denied_callback(task, 'delta', dict(lease_token=task['lease_token'], sequence=1, text='must never publish'))
        if self.sql("SELECT COUNT(*) FROM ai_run WHERE id='" + task['run_id'] + "' AND output_text=''") != '1':
            raise RuntimeError('Revoked source released late model text')
        self.checks.append('http_source_revocation_fences_cached_tool_result_and_delta')
        self.sql("UPDATE ai_run SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id='" + task['run_id'] + "'")
        self.ok('POST', '/internal/ai-worker/claim', dict(worker_id='technical-http-cleanup'), worker=True)
        token = self.login('logout')
        route, task = self.enqueue(token, '仅用于 Bearer 撤销验收。')
        original = self.ok('GET', '/api/v1/external/identity/me', token=token)
        self.ok('POST', '/api/v1/external/identity/logout', {}, token=token)
        replacement = self.login('logout')
        replacement_view = self.ok('GET', '/api/v1/external/identity/me', token=replacement)
        if replacement_view['token_id'] == original['token_id'] or replacement_view['user_id'] != original['user_id']:
            raise RuntimeError('Replacement-token isolation fixture is invalid')
        self.denied_callback(task, 'tools', self.tool_body(task, 'revoked-token'))
        self.denied_callback(task, 'heartbeat', dict(lease_token=task['lease_token']))
        self.checks.append('http_token_logout_fences_tools_and_heartbeat_without_owner_or_new_token_fallback')
        self.sql("UPDATE ai_run SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id='" + task['run_id'] + "'")
        self.ok('POST', '/internal/ai-worker/claim', dict(worker_id='technical-http-cleanup'), worker=True)
        token = self.login('lease')
        route, task = self.enqueue(token, '仅用于旧 lease 重放验收。')
        old_body = self.tool_body(task, 'old-lease')
        self.ok('POST', '/internal/ai-worker/runs/' + task['run_id'] + '/tools', old_body, worker=True)
        self.sql("UPDATE ai_run SET lease_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id='" + task['run_id'] + "'")
        current = self.ok('POST', '/internal/ai-worker/claim', dict(worker_id='technical-http-reclaim'), worker=True)
        if current['run_id'] != task['run_id'] or current['lease_token'] == task['lease_token']:
            raise RuntimeError('Lease reclaim did not fence previous attempt')
        self.denied_callback(task, 'tools', old_body)
        self.checks.append('http_old_lease_cannot_execute_or_replay_tool')
        status, _ = self.http('POST', '/internal/ai-worker/runs/' + current['run_id'] + '/complete',
                              dict(lease_token=current['lease_token'], last_sequence=0, finish_reason='tool_calls'), worker=True)
        if status != 400:
            raise RuntimeError('complete(tool_calls) was not rejected')
        self.checks.append('http_complete_tool_calls_is_not_terminal_answer')
        self.ok('POST', route + '/runs/' + current['run_id'] + '/cancel', {}, token=token)

    async def actual_provider(self):
        sys.path.insert(0, str(self.repo / 'main/xiaozhi-server'))
        from ai_worker.config import Config
        from ai_worker.provider import Bailian
        from ai_worker.transport import Manager
        from ai_worker.worker import Worker
        trace = {'provider_rounds': [], 'provider_chunks': [], 'tool_batches': [], 'completion': None}
        self.proof['trace'] = trace
        acceptance = self
        class RecordingManager(Manager):
            async def tools(self, task, round_number, calls, maximum):
                result = await super().tools(task, round_number, calls, maximum)
                trace['tool_batches'].append(dict(round=round_number, names=[call['name'] for call in calls],
                    statuses=[item['status'] for item in result['results']]))
                return result
            async def callback(self, task, action, **body):
                result = await super().callback(task, action, **body)
                if action == 'complete':
                    trace['completion'] = {key: value for key, value in body.items() if key != 'lease_token'}
                return result
        class RecordingProvider(Bailian):
            @contextlib.asynccontextmanager
            async def stream(self, task):
                trace['provider_rounds'].append(dict(model=task['model'], roles=[message['role'] for message in task['messages']]))
                async with super().stream(task) as stream:
                    async def observed():
                        async for chunk in stream:
                            choices = []
                            for choice in chunk.choices or []:
                                delta = choice.delta
                                fragments = []
                                for fragment in getattr(delta, 'tool_calls', None) or []:
                                    function = getattr(fragment, 'function', None)
                                    fragments.append(dict(index=getattr(fragment, 'index', None), id=getattr(fragment, 'id', None),
                                        type=getattr(fragment, 'type', None), name=getattr(function, 'name', None),
                                        arguments=getattr(function, 'arguments', None)))
                                choices.append(dict(index=choice.index, finish_reason=choice.finish_reason,
                                    legacy_function_call_present=bool(getattr(delta, 'function_call', None)),
                                    content_chars=len(getattr(delta, 'content', None) or ''), tool_calls=fragments))
                            trace['provider_chunks'].append(dict(choices=choices, usage_present=getattr(chunk, 'usage', None) is not None))
                            yield chunk
                    yield observed()
        token = self.login('provider')
        route, task = self.enqueue(token, '为了技术验收，请先且只调用一次 server_capabilities 工具读取服务端能力，然后根据真实工具结果用一句中文说明 protocol_version、read_only、business_records_included。不要猜测，不查询设备、工单或知识。')
        names = [item['function']['name'] for item in task.get('tools', [])]
        if names != ['server_capabilities']:
            raise RuntimeError('Acceptance task must expose only harmless technical tool')
        cfg = Config(**self.provider_config(), manager_url=self.base, worker_key=self.worker_key,
                     worker_id='actual-bailian-tool-acceptance', max_tokens=500, provider_timeout_seconds=25)
        worker = Worker(cfg, RecordingManager(cfg), RecordingProvider(cfg))
        try:
            await worker.execute(task)
        finally:
            await worker.close()
        run = self.ok('GET', route + '/runs/' + task['run_id'], token=token)
        if run['status'] != 'completed' or not trace['tool_batches'] or trace['completion'] is None:
            raise RuntimeError('Real provider did not complete actual tool loop; status=' + str(run['status']) + ' error=' + str(run.get('error_code')))
        requests = trace['completion'].get('provider_requests', [])
        if len(requests) < 2 or not all(item.get('usage', {}).get('total_tokens', 0) > 0 for item in requests):
            raise RuntimeError('Real provider multi-round usage evidence missing')
        if not any('tool' in item['roles'] for item in trace['provider_rounds'][1:]):
            raise RuntimeError('Confirmed tool result did not enter provider tool role')
        aggregate = {key: sum(item['usage'][key] for item in requests) for key in ('input_tokens', 'output_tokens', 'total_tokens')}
        if any(int(run['usage'][key]) != value for key, value in aggregate.items()):
            raise RuntimeError('Persisted aggregate usage differs from real provider requests')
        count = self.sql("SELECT COUNT(*) FROM ai_provider_request_usage WHERE run_id='" + task['run_id'] + "' AND total_tokens>0")
        if int(count) != len(requests):
            raise RuntimeError('Per-request real usage ledger missing')
        history = self.ok('GET', route + '/tools?limit=1', token=token)
        if not isinstance(history['next_cursor'], str) or not isinstance(history['items'][0]['sequence'], str) or not history['has_more']:
            raise RuntimeError('Tool history exact string cursor contract failed')
        self.proof.update(actual_provider=True, provider='authorized Bailian', model=cfg.model, run_id=task['run_id'],
                          conversation_id=task['conversation_id'], trace=trace, aggregate_usage=aggregate)
        self.checks.extend(['actual_bailian_function_call_java_execution_tool_role_continuation',
                            'actual_provider_usage_per_round_and_aggregate_persisted', 'http_tool_history_immutable_exact_string_cursor'])

    def stop(self):
        for process in reversed(self.processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--mysql-info', type=Path, default=Path('/Users/lengwang/code/esp32/work/b3-native-test.json'))
    parser.add_argument('--mysql-credential-source', type=Path, default=Path('/Users/lengwang/code/esp32/work/run-integrated-20261031.py'))
    parser.add_argument('--provider-config', type=Path)
    parser.add_argument('--compiled-runtime', type=Path, help='Reuse an earlier private compiled classes/libraries snapshot while Maven is busy')
    parser.add_argument('--phase', choices=('all', 'protocol', 'provider'), default='all')
    args = parser.parse_args()
    args.provider_config = args.provider_config or args.repo / 'dev/.runtime/ai-worker.json'
    acceptance = Acceptance(args)
    result = 1
    try:
        acceptance.start()
        if args.phase in ('all', 'protocol'):
            acceptance.protocol_acceptance()
        if args.phase in ('all', 'provider'):
            asyncio.run(acceptance.actual_provider())
        acceptance.proof['phase'] = args.phase
        acceptance.proof['result'] = 'passed'
        result = 0
    except Exception as failure:
        # Never serialize exception repr/tracebacks, HTTP body, config, Bearer, headers or keys.
        acceptance.proof.update(result='failed', failure_type=type(failure).__name__, failure=str(failure) if isinstance(failure, RuntimeError) else 'sanitized acceptance failure')
    finally:
        acceptance.stop()
        private_json(acceptance.folder / 'proof.json', acceptance.proof)
        print(json.dumps(dict(result=acceptance.proof['result'], schema=acceptance.schema, proof=str(acceptance.folder / 'proof.json'), checks=len(acceptance.checks), retained=True)))
    return result


if __name__ == '__main__':
    raise SystemExit(main())
