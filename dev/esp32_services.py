#!/usr/bin/env python3
"""Manage the unified Dev device ingress without creating another Manager API."""
import argparse
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / 'dev/.runtime'
MARKER = RUNTIME / 'esp32-services-process.json'


def owned(record):
    result = subprocess.run(['ps', '-p', str(record['pid']), '-o', 'command='], capture_output=True, text=True)
    return bool(result.stdout.strip()) and all(str(arg) in result.stdout for arg in record['command'][1:])


def run(action):
    settings_path = RUNTIME / 'esp32-device-settings.json'
    if not settings_path.exists() or json.loads(settings_path.read_text()).get('enabled') is not True:
        return {'enabled': False}
    records = json.loads(MARKER.read_text()) if MARKER.exists() else {}
    if action == 'status':
        return {name: {'pid': record['pid'], 'running': owned(record)} for name, record in records.items()}
    if action == 'stop':
        for record in records.values():
            if owned(record):
                os.kill(record['pid'], signal.SIGTERM)
        deadline = time.monotonic() + 45
        while any(owned(record) for record in records.values()) and time.monotonic() < deadline:
            time.sleep(.2)
        if any(owned(record) for record in records.values()):
            raise RuntimeError('Device services have not finished stopping')
        return {'stopped': True, 'data_retained': True}
    import yaml
    from esp32_gateway import preflight
    config_path = RUNTIME / 'esp32-gateway.private.yaml'
    if config_path.stat().st_mode & 0o077:
        raise ValueError('Gateway configuration must be private')
    config = yaml.safe_load(config_path.read_text())
    preflight(config)
    host = config['server'].get('ip', '127.0.0.1')
    python = ROOT.parent / '.venv/bin/python'
    commands = {
        'ota': (19183, [str(python), str(ROOT/'dev/esp32_ota_proxy.py'), '--manager',
                       'http://127.0.0.1:18893/xiaozhi', '--bind', host, '--port', '19183']),
        'gateway': (config['server']['port'], [str(python), str(ROOT/'dev/esp32_gateway.py'), '_serve',
                    '--config', str(config_path), '--source', str(ROOT/'main/xiaozhi-server')]),
    }
    for name, (port, command) in commands.items():
        if name in records and owned(records[name]):
            continue
        with socket.socket() as probe:
            probe.bind((host, port))
        log_path = RUNTIME / ('esp32-' + name + '.log')
        with log_path.open('a') as log:
            log_path.chmod(0o600)
            process = subprocess.Popen(command, cwd=ROOT/'main/xiaozhi-server', stdin=subprocess.DEVNULL,
                                       stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        records[name] = {'pid': process.pid, 'command': command}
        MARKER.write_text(json.dumps(records, indent=2) + '\n')
        MARKER.chmod(0o600)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(name + ' exited; inspect its private log')
            with socket.socket() as probe:
                probe.settimeout(.2)
                if probe.connect_ex((host, port)) == 0:
                    break
            time.sleep(.2)
        else:
            raise RuntimeError(name + ' listener did not start')
    return run('status')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'stop', 'status'])
    print(json.dumps(run(parser.parse_args().action)))
