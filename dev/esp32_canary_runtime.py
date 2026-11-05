#!/usr/bin/env python3
"""Start/status/stop an already prepared, private first-board canary runtime.

Never creates/imports databases, changes firmware, or stops the original services.
The runtime and all command markers must have been created by the reviewed setup.
"""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import signal
import shlex
import socket
import subprocess
import time


def alive(record):
    result = subprocess.run(['ps', '-p', str(record['pid']), '-o', 'command='], capture_output=True, text=True)
    command = result.stdout.strip()
    if not command:
        return False
    if Path(record['command'][0]).name == 'redis-server':
        return 'redis-server' in command and '127.0.0.1:19192' in command
    actual = shlex.split(command)
    return actual[1:] == record['command'][1:] and Path(actual[0]).resolve() == Path(record['command'][0]).resolve()


def update(path, value):
    temporary = path.with_suffix('.new')
    temporary.write_text(json.dumps(value, indent=2)); temporary.chmod(0o600); temporary.replace(path)


def run(action, folder):
    folder = Path(folder).resolve()
    marker = folder/'processes.json'
    config = folder/'application-canary.properties'
    if action == 'start' and (folder/'retired-to-unified-dev.json').exists():
        raise ValueError('This canary was consolidated into Dev; use dev/manage.py up')
    if not config.is_file() or config.stat().st_mode & 0o077:
        raise ValueError('prepared private canary configuration required')
    records = json.loads(marker.read_text())
    gateway_marker = folder/'gateway-runtime/process.json'
    gateway = json.loads(gateway_marker.read_text())
    if set(records) != {'redis', 'manager-api', 'ai-worker', 'ota-proxy'}:
        raise ValueError('unexpected process ownership markers')
    all_records = {**records, 'gateway': gateway}
    for name, record in all_records.items():
        proxy_owned = name == 'ota-proxy' and '--manager' in record['command'] and 'http://127.0.0.1:19193/xiaozhi' in record['command'] and '19183' in record['command']
        if not proxy_owned and not any(str(folder) in argument for argument in record['command']):
            raise ValueError('unowned command marker rejected')
    if action == 'status':
        return {name: {'pid': r['pid'], 'running': alive(r)} for name, r in all_records.items()}
    if action == 'stop':
        for name in ['gateway', 'ota-proxy', 'ai-worker', 'manager-api', 'redis']:
            record = all_records[name]
            if alive(record):
                os.kill(record['pid'], signal.SIGTERM)
        return {'termination_requested': True, 'database_and_private_files_retained': True, 'device_firmware_unchanged': True}
    ports = {'redis': 19192, 'manager-api': 19193, 'ota-proxy': 19183, 'gateway': 19180}
    proxy_command = records['ota-proxy']['command']
    lan_host = str(ipaddress.IPv4Address(proxy_command[proxy_command.index('--bind') + 1]))
    for name in ['redis', 'manager-api', 'ai-worker', 'ota-proxy', 'gateway']:
        record = all_records[name]
        if alive(record):
            continue
        host = lan_host if name in ('ota-proxy', 'gateway') else '127.0.0.1'
        if name in ports:
            with socket.socket() as probe:
                probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
                probe.bind((host, ports[name]))
        log = (folder/(name+'.log')).open('a'); os.chmod(log.name, 0o600)
        cwd = Path(record['command'][-1]).parent if name == 'gateway' else folder
        if name == 'ai-worker':
            cwd = Path(__file__).resolve().parents[1]/'main/xiaozhi-server'
        process = subprocess.Popen(record['command'], cwd=cwd, stdin=subprocess.DEVNULL,
                                   stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        record['pid'] = process.pid
        if name == 'gateway': update(gateway_marker, record)
        else: update(marker, records)
        if name in ports:
            for _ in range(100):
                if process.poll() is not None: raise RuntimeError(name+' exited; inspect private log')
                with socket.socket() as probe:
                    probe.settimeout(.2)
                    if probe.connect_ex((host, ports[name])) == 0: break
                time.sleep(.2)
            else: raise RuntimeError(name+' listener timeout')
    return run('status', folder)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'status', 'stop'])
    parser.add_argument('--runtime', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(run(args.action, args.runtime), indent=2))
