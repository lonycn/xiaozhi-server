#!/usr/bin/env python3
"""Dedicated ESP32 canary launcher. Never edits OTA, Dev config or native services."""
import argparse
import copy
import importlib.util
import ipaddress
import time
import json
import os
from pathlib import Path
import re
import runpy
import shutil
import signal
import socket
import subprocess
import sys
from urllib.parse import urlsplit

RESERVED = {18880, 18882, 18883, 18893, 18894}
PROJECT = Path(__file__).resolve().parents[1] / 'main/xiaozhi-server'


def read(path):
    import yaml
    path = Path(path).resolve()
    if path.stat().st_mode & 0o077:
        raise ValueError('private configuration must have mode 600')
    data = yaml.safe_load(path.read_text())
    if not isinstance(data, dict):
        raise ValueError('invalid gateway configuration')
    return data


def endpoint(value, profile="formal"):
    url = urlsplit(value or '')
    if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError('invalid trusted manager endpoint')
    if url.scheme == 'http' and url.hostname not in ('127.0.0.1', 'localhost', '::1'):
        try:
            address=ipaddress.ip_address(url.hostname)
            lan=any(address in ipaddress.ip_network(net) for net in ['10.0.0.0/8','172.16.0.0/12','192.168.0.0/16'])
        except ValueError:
            lan=False
        if profile!='lan_candidate' or not lan:
            raise ValueError('formal remote manager endpoint requires HTTPS; LAN candidate must use RFC1918')
    return value.rstrip('/')


def preflight(config):
    server = config.get('server', {}); gateway = server.get('esp32', {})
    if gateway.get('enabled') is not True or gateway.get('execution_mode', 'ai_worker') != 'ai_worker':
        raise ValueError('canonical gateway must be explicitly enabled')
    if not isinstance(gateway.get('gateway_key'), str) or not 32 <= len(gateway['gateway_key']) <= 512:
        raise ValueError('independent gateway key required')
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', gateway.get('gateway_id', '')):
        raise ValueError('unique gateway instance id required')
    permitted = gateway.get('allowed_devices')
    if not isinstance(permitted, list) or not 1 <= len(permitted) <= 32 or any(not isinstance(x, str) or not re.fullmatch(r'[A-Za-z0-9:_-]{1,50}', x) for x in permitted):
        raise ValueError('explicit bounded canary device list required')
    for field in ('port', 'http_port'):
        port = server.get(field)
        if type(port) is not int or not 1024 <= port <= 65535 or port in RESERVED:
            raise ValueError('dedicated unreserved listener ports required')
    if server['port'] == server['http_port']:
        raise ValueError('listener ports must differ')
    auth = server.get('auth', {})
    if auth.get('enabled') is not True or auth.get('allowed_devices'):
        raise ValueError('authenticated socket required; legacy auth-bypass list must be empty')
    if not isinstance(server.get('auth_key'), str) or len(server['auth_key']) < 16:
        raise ValueError('existing OTA HMAC key required; never generate a replacement')
    local_providers=gateway.get('local_providers') is True
    if local_providers:
        for module in ('VAD','ASR','TTS'):
            selected=config.get('selected_module',{}).get(module)
            if not selected or not isinstance(config.get(module,{}).get(selected),dict):
                raise ValueError('explicit local VAD/ASR/TTS configuration required')
        model=gateway.get('vad_model_file')
        if not model or not Path(model).is_file():raise ValueError('local VAD model file required')
    profile=gateway.get('deployment_profile','formal')
    if profile not in ('formal','lan_candidate'):raise ValueError('invalid deployment profile')
    base = endpoint(gateway.get('base_url'),profile)
    if endpoint(config.get('manager-api', {}).get('url'),profile) != base or not config.get('manager-api', {}).get('secret'):
        raise ValueError('provider configuration and gateway must use the same trusted manager')
    if server['auth_key'] == gateway['gateway_key']:
        raise ValueError('device and internal gateway keys must be distinct')
    return {'configuration_valid': True, 'readiness_scope': 'configuration_only',
            'deployment_profile': profile, 'provider_configuration_source': 'local' if local_providers else 'api', 'production_transport_verified': False,
            'gateway_id': gateway['gateway_id'], 'device_count': len(permitted),
            'ws_port': server['port'], 'http_port': server['http_port'],
            'manager_endpoint': base, 'automatic_ota_changes': False,
            'credential_recovery': 'after revocation wait at least 31 seconds then obtain a fresh OTA credential'}


def dependencies(python):
    code="import importlib,json; names=['yaml','aiohttp','websockets','numpy','onnxruntime','openai','opuslib_next','loguru']; broken=[]\nfor name in names:\n try: importlib.import_module(name)\n except Exception: broken.append(name)\nprint(json.dumps({'dependencies_ready':not broken,'missing_or_broken':broken}))"
    result=subprocess.run([os.path.abspath(python),'-c',code],capture_output=True,text=True,timeout=20)
    try:report=json.loads(result.stdout)
    except (ValueError,TypeError):raise ValueError('prepared interpreter dependency probe failed')
    if result.returncode or not report.get('dependencies_ready'):raise ValueError('prepared interpreter has missing or broken gateway dependencies')
    return report


def start(config_path, runtime, python):
    config = read(config_path); report = preflight(config); report.update(dependencies(python))
    runtime = Path(runtime).resolve()
    runtime.mkdir(parents=True, mode=0o700, exist_ok=False)
    for port in (report['ws_port'], report['http_port']):
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            probe.bind((config['server'].get('ip', '127.0.0.1'), port))
    source = runtime / 'server'
    shutil.copytree(PROJECT, source, ignore=shutil.ignore_patterns('data','tmp','logs','__pycache__','.git','tests'))
    effective = copy.deepcopy(config)
    model=config['server']['esp32'].get('vad_model_file')
    if model:
        target=source/'models/snakers4_silero-vad/src/silero_vad/data/silero_vad.onnx';target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(model,target)
        if config['server']['esp32'].get('local_providers') is True:
            selected=config['selected_module']['VAD'];effective['VAD'][selected]['model_dir']=str(source/'models/snakers4_silero-vad')
    effective.setdefault('log', {})['log_dir'] = str(runtime / 'logs')
    effective['log']['log_file'] = 'gateway.log'
    import yaml
    cfg = runtime / 'gateway.private.yaml';cfg.write_text(yaml.safe_dump(effective));cfg.chmod(0o600)
    log = (runtime / 'process.log').open('w');os.chmod(log.name, 0o600)
    command = [os.path.abspath(python), str(Path(__file__).resolve()), '_serve', '--config', str(cfg), '--source', str(source)]
    process = subprocess.Popen(command, cwd=runtime, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    for _ in range(150):
        if process.poll() is not None:raise RuntimeError('dedicated gateway exited; inspect its private process.log')
        try:
            with socket.create_connection(('127.0.0.1' if config['server'].get('ip') in (None,'0.0.0.0') else config['server']['ip'],report['ws_port']),timeout=.2):break
        except OSError:time.sleep(.2)
    else:
        process.terminate();raise RuntimeError('dedicated listener did not become ready')
    record = {'pid': process.pid, 'command': command, 'configuration': report}
    marker = runtime / 'process.json';marker.write_text(json.dumps(record, indent=2));marker.chmod(0o600)
    return {'pid': process.pid, 'runtime': str(runtime), **report, 'listener_verified': True, 'speech_path_verified': False}


def stop(runtime):
    marker = Path(runtime).resolve() / 'process.json';record = json.loads(marker.read_text());pid = record['pid']
    command = subprocess.run(['ps','-p',str(pid),'-o','command='], capture_output=True, text=True).stdout
    # Refuse reused PIDs and processes not created by this dedicated launcher.
    if str(marker.parent / 'gateway.private.yaml') not in command or '_serve' not in command:
        raise ValueError('refusing to stop an unowned process')
    os.kill(pid, signal.SIGTERM)
    stopped=False
    for _ in range(50):
        state=subprocess.run(['ps','-p',str(pid),'-o','stat='],capture_output=True,text=True).stdout.strip()
        if not state or state.startswith('Z'):
            stopped=True;break
        time.sleep(.2)
    return {'termination_requested': pid, 'stopped': stopped, 'configuration_and_data_retained': True, 'ota_unchanged': True}


def main():
    parser = argparse.ArgumentParser();parser.add_argument('action', choices=['preflight','start','stop','_serve']);parser.add_argument('--config');parser.add_argument('--runtime');parser.add_argument('--source');parser.add_argument('--python', default=sys.executable);args=parser.parse_args()
    if args.action == '_serve':
        os.environ['XIAOZHI_CONFIG_FILE'] = args.config;sys.path.insert(0,args.source);os.chdir(args.source)
        # This candidate deliberately serves only WS and a health endpoint;
        # OTA/provisioning/vision routes remain with the reviewed existing owner.
        import asyncio
        from config.config_loader import load_config
        from core.websocket_server import WebSocketServer
        from aiohttp import web
        config=asyncio.run(load_config())
        async def serve():
            stopped=asyncio.Event();loop=asyncio.get_running_loop()
            for sig in (signal.SIGINT,signal.SIGTERM):loop.add_signal_handler(sig,stopped.set)
            server=WebSocketServer(config)
            async def health(request):return web.json_response({'status':'running','readiness_scope':'listener_only','ota_served':False})
            app=web.Application();app.router.add_get('/health',health);runner=web.AppRunner(app);await runner.setup()
            site=web.TCPSite(runner,config['server'].get('ip','127.0.0.1'),config['server']['http_port']);await site.start()
            task=asyncio.create_task(server.start());waiter=asyncio.create_task(stopped.wait())
            try:
                completed,_=await asyncio.wait([task,waiter],return_when=asyncio.FIRST_COMPLETED)
                if task in completed:await task
            finally:
                task.cancel();waiter.cancel();await asyncio.gather(task,waiter,return_exceptions=True);await runner.cleanup()
        asyncio.run(serve());return
    try:
        result = {**preflight(read(args.config)),**dependencies(args.python)} if args.action == 'preflight' else start(args.config,args.runtime,args.python) if args.action == 'start' else stop(args.runtime)
        print(json.dumps(result))
    except Exception as error:
        # Never print configuration values, HTTP bodies or credential-bearing exceptions.
        print(json.dumps({'error': type(error).__name__, 'detail': str(error) if isinstance(error, ValueError) else 'gateway operation failed'}));raise SystemExit(1)

if __name__ == '__main__':main()
