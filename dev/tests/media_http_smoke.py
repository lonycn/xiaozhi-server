#!/usr/bin/env python3
"""Real HTTP checks against an explicitly isolated B3 database; never resets data.

Run protocol first without a worker; then decoder with the real media worker.
Decoder tests use repo assets and a separately supplied technical video fixture.
No model provider is called by this script (transcription must be disabled).
"""
import argparse
import hashlib
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
    parser.add_argument('phase', choices=['protocol', 'decoder', 'annotation'])
    parser.add_argument('--url', default='http://127.0.0.1:19893/xiaozhi')
    parser.add_argument('--container', default='xiaozhi-media-b3-test')
    parser.add_argument('--database', default='xiaozhi_media_test_full_r2')
    parser.add_argument('--mysql-socket', help='An explicitly disposable native test instance socket')
    parser.add_argument('--fixture-file', required=True)
    parser.add_argument('--video')
    parser.add_argument('--pdf')
    parser.add_argument('--render-output', help='Optional local JPEG evidence from the annotation phase')
    args = parser.parse_args()
    if urllib.parse.urlsplit(args.url).hostname != '127.0.0.1' or not re.fullmatch(r'xiaozhi_media_test_[a-z0-9_]+', args.database):
        raise SystemExit('Requires disposable loopback xiaozhi_media_test_* database')
    if not re.fullmatch(r'xiaozhi-media-[a-z0-9-]+-test', args.container):
        raise SystemExit('Requires a dedicated xiaozhi-media-*-test container')
    if args.mysql_socket:
        socket = Path(args.mysql_socket).resolve()
        if socket.name != 'mysql.sock' or not socket.parent.name.startswith('b3-native-test-') or socket.parent.parent != Path('/Users/lengwang/code/esp32/work'):
            raise SystemExit('Requires an explicitly disposable b3-native-test-* socket')
    fixture = Path(args.fixture_file)
    media_key = os.environ.get('MEDIA_TEST_WORKER_KEY', 'b3-isolated-media-worker-secret-at-least-32-chars')
    checks = 0

    def sql(statement):
        if args.mysql_socket:
            env = os.environ.copy()
            env['MYSQL_PWD'] = os.environ.get('MEDIA_TEST_DB_PASSWORD', 'b3-disposable-test-only')
            return subprocess.run(['mysql', '--no-defaults', '--socket=' + args.mysql_socket, '-uroot',
                                   '--default-character-set=utf8mb4', '-N', args.database],
                                  input=statement, env=env, text=True, capture_output=True, check=True).stdout.strip()
        return subprocess.run(['docker', 'exec', '-i', args.container, 'sh', '-c',
                               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 -N ' + args.database],
                              input=statement, text=True, capture_output=True, check=True).stdout.strip()

    if args.phase == 'protocol':
        accounts = {}
        base = 8_300_000_000_000_000_000 + int(time.time() * 1000) * 10
        for i, name in enumerate(('admin', 'owner', 'staff', 'stranger')):
            actor, token = base + i, secrets.token_hex(24)
            sql(f"INSERT INTO sys_user(id,username,status,super_admin) VALUES({actor},'b3-media-{name}-{actor}',1,{int(name == 'admin')});"
                f"INSERT INTO sys_user_token(id,user_id,token,expire_date) VALUES({actor},{actor},'{token}',DATE_ADD(NOW(),INTERVAL 1 DAY));")
            accounts[name] = {'id': str(actor), 'token': token}
        data = {'accounts': accounts, 'attachments': {}}
    else:
        data = json.loads(fixture.read_text())
        accounts = data['accounts']

    def save():
        fd = os.open(fixture, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'w') as output: json.dump(data, output)

    def request(method, path, actor=None, body=None, expected=200, raw=None, mime=None, worker=False, key=None, extra=None, binary=False):
        nonlocal checks
        headers = {'Content-Type': mime or 'application/json'}
        if actor: headers['Authorization'] = 'Bearer ' + accounts[actor]['token']
        if worker: headers['X-Media-Worker-Key'] = key or media_key
        headers.update(extra or {})
        content = raw if raw is not None else None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(args.url + path, method=method, data=content, headers=headers)
        try: response = urllib.request.urlopen(req, timeout=25)
        except urllib.error.HTTPError as error: response = error
        payload = response.read()
        assert response.status == expected, (method, path.split('?')[0], response.status, payload[:200])
        checks += 1
        if binary: return payload, response.headers
        value = json.loads(payload)
        assert value['code'] == (0 if expected == 200 else expected), value
        return value.get('data')

    def worker(path, body=None, **kwargs):
        return request('POST', '/internal/media-worker' + path, body=body, worker=True, **kwargs)

    def upload(name, mime, content, actor='owner', conversation=None):
        payload = {'conversation_id': conversation or data['conversation'], 'client_upload_id': 'b3-' + secrets.token_hex(10),
                   'filename': name, 'mime_type': mime, 'size': len(content), 'sha256': hashlib.sha256(content).hexdigest()}
        item = request('POST', '/api/v1/ai/uploads/init', actor, payload)
        assert request('POST', '/api/v1/ai/uploads/init', actor, payload)['id'] == item['id']
        assert not any('storage' in key or 'lease' in key for key in item)
        request('PUT', '/api/v1/ai/uploads/' + item['id'] + '/content', actor, raw=content, mime=mime)
        request('POST', '/api/v1/ai/uploads/' + item['id'] + '/complete', actor, {})
        return item['id']

    def meta(id, actor='owner'):
        return request('GET', '/api/v1/ai/attachments/' + id, actor)

    def wait(id, expected='ready'):
        for _ in range(80):
            item = meta(id)
            if item['status'] in ('ready', 'failed'):
                assert item['status'] == expected, (item['status'], item['error_code'])
                return item
            time.sleep(.5)
        raise AssertionError('media worker did not reach a terminal state')

    if args.phase == 'protocol':
        save()
        request('GET', '/api/v1/ai/attachments/1', expected=401)
        worker('/claim', {'worker_id': 'probe'}, key='wrong', expected=401)
        request('POST', '/internal/media-worker/claim', 'owner', {'worker_id': 'probe'}, expected=401)
        request('GET', '/api/v1/ai/conversations', worker=True, expected=401)
        data['conversation'] = request('POST', '/api/v1/ai/conversations', 'owner', {'title': 'B3 isolated HTTP'})['id']
        route = '/api/v1/ai/conversations/' + data['conversation']
        save()
        assert sql("SELECT COUNT(*) FROM ai_conversation WHERE id='" + data['conversation'] + "';") == '1'
        payload = {'conversation_id': data['conversation'], 'client_upload_id': 'invalid', 'filename': '../bad.png',
                   'mime_type': 'image/png', 'size': 1, 'sha256': '0' * 64}
        request('POST', '/api/v1/ai/uploads/init', 'owner', payload, expected=400)
        request('POST', '/api/v1/ai/uploads/init', 'owner', {**payload, 'filename': 'huge.png', 'size': 20 * 1024 * 1024 + 1}, expected=413)
        content = '独立协议检查，不是业务消息。'.encode()
        attachment = upload('protocol.txt', 'text/plain', content)
        request('GET', '/api/v1/ai/attachments/' + attachment, 'stranger', expected=404)
        request('GET', '/api/v1/ai/attachments/' + attachment + '/access?variant=text', 'owner', expected=409)
        task = worker('/claim', {'worker_id': 'protocol-worker'})
        assert task['attachment_id'] == attachment
        job = '/jobs/' + task['job_id']
        lease = {'lease_token': task['lease_token']}
        request('GET', '/internal/media-worker' + job + '/input', worker=True,
                extra={'X-Media-Lease': '0' * 64}, expected=409)
        raw, _ = request('GET', '/internal/media-worker' + job + '/input', worker=True,
                         extra={'X-Media-Lease': task['lease_token']}, binary=True)
        assert raw == content
        for _ in range(2):
            request('PUT', '/internal/media-worker' + job + '/artifacts/text', worker=True, raw=content,
                    mime='text/plain', extra={'X-Media-Lease': task['lease_token']})
        request('PUT', '/internal/media-worker' + job + '/artifacts/text', worker=True, raw=b'changed', mime='text/plain',
                extra={'X-Media-Lease': task['lease_token']}, expected=409)
        worker(job + '/heartbeat', lease)
        worker(job + '/complete', {**lease, 'metadata': {'mime_type': 'text/plain', 'text_available': True, 'text_truncated': False}})
        worker(job + '/heartbeat', lease, expected=409)
        ticket = request('GET', '/api/v1/ai/attachments/' + attachment + '/access?variant=text', 'owner')
        path = ticket['url'].removeprefix('/xiaozhi')
        raw, headers = request('GET', path, binary=True)
        assert raw == content and 'no-store' in headers['Cache-Control'] and headers['X-Content-Type-Options'] == 'nosniff'
        request('GET', path + 'tampered', expected=401)
        message = {'client_message_id': 'protocol-mixed', 'parts': [{'type': 'text', 'text': '检查附件'}, {'type': 'file', 'attachment_id': attachment}]}
        sent = request('POST', route + '/messages', 'owner', message)
        assert request('POST', route + '/messages', 'owner', message)['id'] == sent['id']
        runs = request('GET', route + '/runs', 'owner')['items']
        for run in runs: request('POST', route + '/runs/' + run['run_id'] + '/cancel', 'owner', {})
        request('POST', route + '/messages', 'owner', {**message, 'client_message_id': 'reuse'}, expected=409)
        other = request('POST', '/api/v1/ai/conversations', 'owner', {'title': 'B3 other isolated'})['id']
        request('POST', '/api/v1/ai/conversations/' + other + '/messages', 'owner', message, expected=404)
        handoff = request('POST', route + '/handoff', 'owner', {'client_request_id': 'b3-human', 'requested_mode': 'text'})
        data['support_request'] = handoff['request_id']
        data['attachments']['protocol'] = attachment
        save()
    elif args.phase == 'decoder':
        repo = Path(__file__).resolve().parents[2]
        sources = [('image', 'logo.png', 'image/png', (repo / 'main/manager-web-next/src/assets/xiaozhi-logo.png').read_bytes()),
                   ('audio', 'repository.wav', 'audio/wav', (repo / 'main/xiaozhi-server/config/assets/max_output_size.wav').read_bytes()),
                   ('text', 'protocol-note.txt', 'text/plain', '设备测试编号TEST-B3，附件处理。'.encode()),
                   ('invalid', 'invalid.png', 'image/png', b'not a PNG file')]
        if args.video: sources.append(('video', 'technical.mp4', 'video/mp4', Path(args.video).read_bytes()))
        if args.pdf: sources.append(('pdf', 'technical-empty.pdf', 'application/pdf', Path(args.pdf).read_bytes()))
        for name, filename, mime, content in sources:
            attachment = upload(filename, mime, content)
            data['attachments'][name] = attachment
            save()
            item = wait(attachment, 'failed' if name == 'invalid' else 'ready')
            if name == 'invalid': continue
            variant = {'image': 'display', 'audio': 'audio', 'text': 'text', 'video': 'original', 'pdf': 'original'}[name]
            ticket = request('GET', '/api/v1/ai/attachments/' + attachment + '/access?variant=' + variant, 'owner')
            raw, _ = request('GET', ticket['url'].removeprefix('/xiaozhi'), binary=True)
            assert raw
            if name in ('audio', 'video'):
                part, headers = request('GET', ticket['url'].removeprefix('/xiaozhi'), binary=True, expected=206, extra={'Range': 'bytes=0-15'})
                assert part == raw[:16] and headers['Content-Range'].startswith('bytes 0-15/')
            if name == 'image': assert item['metadata']['width'] > 0 and raw.startswith(b'\xff\xd8')
            if name == 'video': assert item['metadata']['frames'] and item['metadata']['duration_ms'] > 0
            if name == 'pdf':
                assert item['metadata']['text_available'] is False
                ticket = request('GET', '/api/v1/ai/attachments/' + attachment + '/access?variant=text', 'owner')
                empty, _ = request('GET', ticket['url'].removeprefix('/xiaozhi'), binary=True)
                assert empty == b''
        parts = [{'type': 'text', 'text': '技术验收：混合媒体仅进入人工对话。'}]
        for name, kind in [('image', 'image'), ('audio', 'audio'), ('video', 'video'), ('text', 'file'), ('pdf', 'file')]:
            if name in data['attachments']: parts.append({'type': kind, 'attachment_id': data['attachments'][name]})
        parts.append({'type': 'location', 'latitude': 31.2, 'longitude': 121.4, 'coordinate_system': 'WGS84',
                      'accuracy_m': 10, 'source': 'user_shared', 'authorized_at': '2026-10-30T00:00:00Z'})
        route = '/api/v1/ai/conversations/' + data['conversation']
        message = {'client_message_id': 'decoder-mixed', 'parts': parts}
        sent = request('POST', route + '/messages', 'owner', message)
        assert request('POST', route + '/messages', 'owner', message)['id'] == sent['id']
        assert len(sent['parts']) == len(parts)
        assert len(request('GET', route + '/runs', 'owner')['items']) == 1
        save()
    else:
        # Only append synthetic test records; old or failed attempts are preserved.
        staff = accounts['staff']['id']
        request('PUT', '/api/v1/support/agents/' + staff, 'admin',
                {'role': 'expert', 'enabled': True, 'capacity_text': 3})
        request('POST', '/api/v1/support/presence', 'staff', {'accepting': True})
        support = '/api/v1/support/requests/' + data['support_request']
        request('POST', support + '/claim', 'staff', {})
        source = data['attachments']['video']
        original = meta(source)
        frame_body = {'conversation_id': data['conversation'], 'client_frame_id': 'b4-' + secrets.token_hex(8),
                      'source_attachment_id': source, 'time_ms': 501}
        frame = request('POST', '/api/v1/support/frames', 'staff', frame_body)
        assert request('POST', '/api/v1/support/frames', 'staff', frame_body)['id'] == frame['id']
        request('POST', '/api/v1/support/frames', 'staff', {**frame_body, 'time_ms': 502}, expected=409)
        request('GET', '/api/v1/support/frames/' + frame['id'], 'owner', expected=404)
        request('GET', '/api/v1/support/frames/' + frame['id'], 'stranger', expected=404)

        def wait_snapshot(kind, id):
            for _ in range(80):
                snapshot = request('GET', '/api/v1/support/' + kind + '/' + id, 'staff')
                if snapshot['status'] in ('ready', 'failed'):
                    assert snapshot['status'] == 'ready', snapshot['error_code']
                    return snapshot
                time.sleep(.5)
            raise AssertionError('snapshot worker did not reach a terminal state')

        frame = wait_snapshot('frames', frame['id'])
        assert type(frame['actual_time_ms']) is int and 501 <= frame['actual_time_ms'] < 2000
        assert frame['actual_time_ms'] != 501 and frame['capture_policy'] == 'first_at_or_after'
        scene = {'format': 'annotation-v1', 'frame_id': frame['id'], 'width': frame['width'], 'height': frame['height'],
                 'elements': [
                     {'id': 'arrow', 'type': 'arrow', 'color': '#FF0000', 'stroke_width': .008,
                      'points': [{'x': .1, 'y': .15}, {'x': .4, 'y': .35}]},
                     {'id': 'circle', 'type': 'circle', 'color': '#00AA00', 'stroke_width': .008,
                      'cx': .65, 'cy': .3, 'radius': .1},
                     {'id': 'rect', 'type': 'rect', 'color': '#0000FF', 'stroke_width': .008,
                      'x': .1, 'y': .45, 'width': .25, 'height': .2},
                     {'id': 'freehand', 'type': 'freehand', 'color': '#A000A0', 'stroke_width': .008,
                      'points': [{'x': .5, 'y': .6}, {'x': .6, 'y': .5}, {'x': .7, 'y': .6}]},
                     {'id': 'text', 'type': 'text', 'color': '#000000', 'x': .1, 'y': .8,
                      'text': '检查阀门', 'font_size': .06}]}
        body = {'conversation_id': data['conversation'], 'client_annotation_id': 'b4-' + secrets.token_hex(8),
                'frame_id': frame['id'], 'label': '独立标注验收', 'scene': scene}
        request('POST', '/api/v1/support/annotations', 'staff', {**body, 'scene': {**scene, 'external_url': 'https://example.invalid/'}}, expected=400)
        annotation = request('POST', '/api/v1/support/annotations', 'staff', body)
        assert request('POST', '/api/v1/support/annotations', 'staff', body)['id'] == annotation['id']
        request('POST', '/api/v1/support/annotations', 'staff', {**body, 'label': 'changed'}, expected=409)
        request('GET', '/api/v1/support/annotations/' + annotation['id'], 'owner', expected=404)
        annotation = wait_snapshot('annotations', annotation['id'])
        ticket = request('GET', '/api/v1/ai/attachments/' + annotation['render_attachment_id'] + '/access?variant=display', 'staff')
        raw, _ = request('GET', ticket['url'].removeprefix('/xiaozhi'), binary=True)
        assert raw.startswith(b'\xff\xd8')
        if args.render_output: Path(args.render_output).write_bytes(raw)
        message = {'client_message_id': 'b4-' + secrets.token_hex(8),
                   'parts': [{'type': 'text', 'text': '技术验收：不可变标注回复'},
                             {'type': 'image', 'attachment_id': annotation['render_attachment_id']}]}
        sent = request('POST', support + '/messages', 'staff', message)
        assert request('POST', support + '/messages', 'staff', message)['id'] == sent['id']
        assert request('GET', '/api/v1/support/annotations/' + annotation['id'], 'owner')['scene'] == annotation['scene']
        assert request('GET', '/api/v1/support/frames/' + frame['id'], 'owner')['id'] == frame['id']
        evidence = meta(frame['frame_attachment_id'])
        assert evidence['metadata']['source_attachment_id'] == source
        assert meta(source)['sha256'] == original['sha256']
        count = sql("SELECT COUNT(*) FROM ai_attachment WHERE id IN (" + frame['frame_attachment_id'] + ',' + annotation['render_attachment_id'] + ") AND linked_message_id='" + sent['id'] + "';")
        assert count == '2'
        data['annotation'] = {'frame_id': frame['id'], 'annotation_id': annotation['id'], 'reply_id': sent['id'],
                              'requested_time_ms': 501, 'actual_time_ms': frame['actual_time_ms']}
        save()
    print(json.dumps({'result': 'passed', 'phase': args.phase, 'http_checks': checks,
                      'database': args.database, 'provider_calls': 0, 'business_rows_deleted': 0}))


if __name__ == '__main__': main()
