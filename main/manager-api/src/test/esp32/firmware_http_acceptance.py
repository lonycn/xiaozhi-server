#!/usr/bin/env python3
"""Real isolated OTA HTTP acceptance; synthetic device, no physical flash.

Requires an explicitly selected current executable JAR and expected SHA-256.
Creates one fresh retained MySQL schema and private artifact directory; starts and
stops only its own loopback Manager and Redis. No provider/model calls are made.
Optionally supply an already-built LAN candidate app/assets; otherwise both are
synthetic fixtures and the proof says so. No key or ticket is printed or saved.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import socket
import struct
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('esp32_existing_acceptance', HERE / 'http_acceptance.py')
existing = importlib.util.module_from_spec(spec); spec.loader.exec_module(existing)
base = existing.base
VARIANT = 'bread-compact-wifi-128x32-ai-lan'
BOARD = 'bread-compact-wifi'


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def file_sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(65536), b''):
            digest.update(chunk)
    return digest.hexdigest()


def image(version):
    """Only descriptor bytes for service contract; not an executable image."""
    data = bytearray(512); data[0] = 0xe9; struct.pack_into('<H', data, 12, 9)
    struct.pack_into('<I', data, 32, 0xabcd5432)
    data[48:48+len(version)] = version.encode(); data[80:87] = b'xiaozhi'
    return bytes(data)


def app_version(data):
    require(len(data) >= 288 and data[0] == 0xe9 and struct.unpack_from('<H', data, 12)[0] == 9
            and struct.unpack_from('<I', data, 32)[0] == 0xabcd5432, 'invalid candidate app descriptor')
    result = data[48:80].split(b'\0', 1)[0].decode('ascii')
    require(re.fullmatch(r'\d{1,5}\.\d{1,5}\.\d{1,5}', result) is not None, 'invalid candidate version')
    return result


class Acceptance(existing.Acceptance):
    def __init__(self, args):
        super().__init__(args)
        self.schema = self.schema.replace('xiaozhi_agent_tool_test_http_', 'xiaozhi_esp32_firmware_test_http_')
        self.proof['schema'] = self.schema
        self.proof.update(phase='esp32_firmware_http', hardware=False, model_calls=False,
                          artifact_kind='candidate_app_and_assets_plus_synthetic_second_app' if args.app else 'synthetic_descriptors_and_assets')
        self.download_key = secrets.token_urlsafe(48)
        self.device_base = self.base
        self.proxy_port = None
        self.admin_token = self.owner_token
        self.proof['jar_sha256'] = file_sha(args.jar)
        require(re.fullmatch(r'[0-9a-f]{64}', args.jar_sha256 or '') is not None
                and self.proof['jar_sha256'] == args.jar_sha256, 'JAR hash mismatch')
        require(bool(args.app) == bool(args.assets) == bool(args.candidate_manifest),
                'provide candidate app, assets and manifest together or none')
        self.proof['jar_name'] = args.jar.name
        if args.candidate_manifest:
            manifest = json.loads(args.candidate_manifest.read_text())
            ota = manifest.get('ota_url')
            require(ota == 'http://192.168.3.152:19183/xiaozhi/ota/', 'candidate OTA endpoint mismatch')
            self.device_base = ota.removesuffix('/ota/')
            self.proxy_port = 19183
            require(args.verifier is not None, 'candidate verifier path required')

    def provider_config(self):
        # The inherited isolated bootstrap only needs an AI model name, and no provider is invoked.
        return {'model': 'synthetic-fixture-model', 'base_url': 'https://fixture.invalid', 'api_key': 'fixture-only'}

    def sql(self, statement, database=True):
        if not re.fullmatch(r'xiaozhi_esp32_firmware_test_http_[a-z0-9_]+', self.schema):
            raise RuntimeError('Unsafe isolated schema')
        command = self.mysql + (['-D', self.schema] if database else [])
        result = subprocess.run(command, input=statement, env=self.sql_env, capture_output=True, text=True)
        if result.returncode: raise RuntimeError('Isolated MySQL operation failed')
        return result.stdout.strip()

    def unpack_jar(self):
        root = self.folder/'jar-runtime'; classes=root/'classes'; libraries=root/'libraries'
        classes.mkdir(parents=True, mode=0o700); libraries.mkdir(mode=0o700)
        with zipfile.ZipFile(self.args.jar) as jar:
            for member in jar.infolist():
                if member.is_dir(): continue
                if member.filename.startswith('BOOT-INF/classes/'):
                    relative = Path(member.filename.removeprefix('BOOT-INF/classes/'))
                    if relative.is_absolute() or '..' in relative.parts: raise RuntimeError('invalid JAR entry')
                    target = classes/relative; target.parent.mkdir(parents=True, exist_ok=True)
                elif member.filename.startswith('BOOT-INF/lib/') and member.filename.endswith('.jar'):
                    target = libraries/Path(member.filename).name
                else: continue
                if target.exists(): raise RuntimeError('duplicate JAR entry')
                with jar.open(member) as source, target.open('xb') as output:
                    import shutil; shutil.copyfileobj(source, output)
        require((classes/'xiaozhi/modules/esp32ota/Esp32FirmwareService.class').is_file()
                and (classes/'db/changelog/esp32-firmware.changelog.yaml').is_file(), 'JAR lacks firmware service or migration')
        self.args.compiled_runtime = root

    def launch(self, name, arguments):
        if name == 'api':
            config = self.folder/'application-isolated.properties'
            with config.open('a') as out:
                out.write('\nfeatures.esp32-ota-enabled=true\nesp32.ota.directory='+str(self.folder/'firmware-artifacts')
                          +'\nesp32.ota.public-base-url='+self.device_base+'\nesp32.ota.allow-loopback-http=true'
                          +'\nesp32.ota.allow-lan-http=true'
                          +'\nesp32.ota.download-key='+self.download_key+'\n')
        super().launch(name, arguments)

    def start_proxy(self):
        if self.proxy_port is None: return
        # Bind probe refuses another listener; proxy itself performs the final bind.
        with socket.socket() as probe:
            try: probe.bind(('192.168.3.152', self.proxy_port))
            except OSError: raise RuntimeError('dedicated OTA proxy port unavailable') from None
        python='/Users/lengwang/code/esp32/backend/xiaozhi-server/.venv/bin/python'
        self.launch('ota-proxy', [python,str(self.repo/'dev/esp32_ota_proxy.py'),
                    '--bind','192.168.3.152','--port',str(self.proxy_port),'--manager',self.base])
        import time
        for _ in range(50):
            if self.processes[-1].poll() is not None: raise RuntimeError('dedicated OTA proxy failed to start')
            with socket.socket() as probe:
                probe.settimeout(.2)
                if probe.connect_ex(('192.168.3.152',self.proxy_port)) == 0: break
            time.sleep(.1)
        else: raise RuntimeError('dedicated OTA proxy did not become ready')
        status,_=self.wire('GET','/api/v1/esp32/firmware/releases',device=True)
        require(status==404, 'OTA proxy exposed an administrative route')
        self.checks.append('lan_proxy_forwards_only_device_routes')

    def wire(self, method, path, body=None, token=None, headers=None, binary=False, device=False):
        raw = body if isinstance(body, bytes) else None if body is None else json.dumps(body,separators=(',',':')).encode()
        opts = {'Content-Type':'application/octet-stream' if binary else 'application/json'}
        if token: opts['Authorization']='Bearer '+token
        if headers: opts.update(headers)
        req=urllib.request.Request((self.device_base if device else self.base)+path,data=raw,headers=opts,method=method)
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try: response=opener.open(req,timeout=30)
        except urllib.error.HTTPError as failure: response=failure
        with response:
            data=response.read(9*1024*1024)
            if binary and method == 'GET' and response.status==200: return response.status,data
            try: payload=json.loads(data) if data else None
            except ValueError: payload=None
            return response.status,payload

    def result(self, method, path, body=None, token=None, **options):
        status,payload=self.wire(method,path,body,token,**options)
        require(status==200 and isinstance(payload,dict) and payload.get('code')==0,
                'unexpected API response at '+path.split('?')[0]+' HTTP '+str(status))
        return payload['data']

    def denied_wire(self, method, path, body=None, token=None, statuses=(401,403,409), **options):
        status,_=self.wire(method,path,body,token,**options)
        require(status in statuses, 'expected denial at '+path.split('?')[0]+' HTTP '+str(status))

    def device_headers(self, token=True):
        result={'Device-Id':self.mac,'Client-Id':self.client_id}
        if token:result['Authorization']='Bearer '+self.device_token
        return result

    def ota(self, version, name=VARIANT, flash=16*1024*1024):
        body={'board':{'type':BOARD,'name':name},'application':{'name':'xiaozhi','version':version},'flash_size':flash}
        status,data=self.wire('POST','/ota/',body,headers=self.device_headers(),device=True)
        require(status==200 and isinstance(data,dict), 'OTA report failed')
        return data.get('firmware')

    def download(self, url, kind='app', expected=200):
        parsed=urllib.parse.urlsplit(url); base_url=urllib.parse.urlsplit(self.device_base)
        require(parsed.scheme==base_url.scheme and parsed.netloc==base_url.netloc
                and parsed.path.startswith(base_url.path+'/ota/esp32/releases/'), 'untrusted fixture ticket URL')
        path=parsed.path[len(base_url.path):]
        if kind!='app': path=path.rsplit('/',1)[0]+'/'+kind
        status,data=self.wire('GET',path+'?'+parsed.query,binary=True,device=True)
        require(status==expected, 'download status mismatch HTTP '+str(status))
        return data

    def execute(self):
        self.fixture()
        self.sql("UPDATE sys_user SET super_admin=1 WHERE id=70001")
        # Seed a generic newer image and enable it; controlled devices must not receive it on denial.
        self.sql("UPDATE ai_device SET auto_update=1 WHERE id='fixture-device'")
        self.sql("INSERT INTO ai_ota(id,firmware_name,type,version,size,firmware_path,create_date,update_date) VALUES('fixture-generic-ota','fixture-generic','bread-compact-wifi','99.0.0',1,'fixture-only',NOW(),NOW())")
        endpoint='/api/v1/esp32/firmware'
        self.denied_wire('GET',endpoint+'/releases',statuses=(401,))
        self.denied_wire('GET',endpoint+'/releases',token=self.other_token,statuses=(403,))
        self.denied_wire('POST',endpoint+'/artifacts/app',b'x',token=self.other_token,statuses=(403,),binary=True)
        self.checks.append('admin_and_unauthenticated_http_denials')
        self.denied_wire('POST',endpoint+'/artifacts/app',b'x'*(0x3f0000+1),token=self.admin_token,statuses=(413,),binary=True)
        self.checks.append('bounded_upload_rejects_oversize')
        app=self.args.app.read_bytes() if self.args.app else image('2.5.3')
        assets=self.args.assets.read_bytes() if self.args.assets else b'synthetic-assets-not-a-firmware-image'
        version=app_version(app)
        source_sha='a'*40; idf_version='v6.0.1'; assets_version='1.0.0'
        if self.args.candidate_manifest:
            candidate=json.loads(self.args.candidate_manifest.read_text())
            require(candidate.get('development_only') is True and candidate.get('board_name')==VARIANT
                    and candidate.get('app_version')==version, 'candidate manifest identity mismatch')
            app_expected=candidate.get('artifacts',{}).get('app.bin',{})
            assets_expected=candidate.get('artifacts',{}).get('assets.bin',{})
            require(app_expected=={'size':len(app),'sha256':hashlib.sha256(app).hexdigest()}
                    and assets_expected=={'size':len(assets),'sha256':hashlib.sha256(assets).hexdigest()}, 'candidate manifest artifact mismatch')
            source_sha=candidate.get('source_sha'); idf_version=candidate.get('idf_version'); assets_version=candidate.get('assets_version')
            require(isinstance(source_sha,str) and re.fullmatch(r'[0-9a-f]{40}',source_sha) is not None
                    and isinstance(idf_version,str) and isinstance(assets_version,str), 'candidate provenance missing')
            self.proof['candidate_source_sha']=source_sha
        parts=[int(x) for x in version.split('.')]
        require(parts[2]<99999, 'candidate version cannot increment')
        next_version=f'{parts[0]}.{parts[1]}.{parts[2]+1}'
        next_app=image(next_version)
        self.proof['candidate_app_sha256']=hashlib.sha256(app).hexdigest()
        self.proof['candidate_assets_sha256']=hashlib.sha256(assets).hexdigest()
        app_meta=self.result('POST',endpoint+'/artifacts/app',app,token=self.admin_token,binary=True)
        assets_meta=self.result('POST',endpoint+'/artifacts/assets',assets,token=self.admin_token,binary=True)
        next_meta=self.result('POST',endpoint+'/artifacts/app',next_app,token=self.admin_token,binary=True)
        require(app_meta=={'sha256':hashlib.sha256(app).hexdigest(),'size':len(app)}
                and assets_meta=={'sha256':hashlib.sha256(assets).hexdigest(),'size':len(assets)}, 'artifact digest or numeric size mismatch')
        cli_id = None
        if self.args.candidate_manifest:
            token_file=self.folder/'fixture-admin-token'
            descriptor=os.open(token_file,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
            try:
                with os.fdopen(descriptor,'w') as stream: stream.write(self.admin_token)
                command=[sys.executable,str(self.repo/'dev/esp32_firmware.py'), '--base-url',self.base,
                    '--token-file',str(token_file),'--development-lan','import-package',
                    '--package',str(self.args.app.parent),'--verifier',str(self.args.verifier)]
                outputs=[]
                for _ in range(2):
                    result=subprocess.run(command,capture_output=True,text=True,timeout=90)
                    require(result.returncode==0, 'candidate CLI import failed')
                    outputs.append(json.loads(result.stdout))
                require(outputs[0]['id']==outputs[1]['id'], 'candidate CLI import was not idempotent')
                cli_id=outputs[0]['id']
                self.checks.append('cli_real_candidate_import_and_idempotency')
            finally:
                token_file.unlink(missing_ok=True)
        releases=[]
        for v,meta in ((version,app_meta),(next_version,next_meta)):
            key=('package-'+VARIANT+'-'+version) if cli_id and v==version else secrets.token_hex(16)
            body={'idempotencyKey':key,'version':v,'boardType':BOARD,'boardName':VARIANT,
                  'chip':'esp32s3','minFlashSize':16*1024*1024,'app':meta,'assets':assets_meta,
                  'assetsVersion':assets_version,'sourceSha':source_sha,'idfVersion':idf_version}
            data=self.result('POST',endpoint+'/releases',body,token=self.admin_token)
            repeat=self.result('POST',endpoint+'/releases',body,token=self.admin_token)
            if cli_id and v==version: require(data['id']==cli_id, 'CLI and API candidate differ')
            require(data['id']==repeat['id'] and data['environment']=='development', 'release idempotency or development marker failure')
            self.result('POST',endpoint+'/releases/'+data['id']+'/published',{},token=self.admin_token)
            releases.append(data['id'])
        self.checks.append('two_immutable_versions_and_replayed_release_id')
        def assign(release):
            return self.result('POST',endpoint+'/releases/'+release+'/assignments',
                {'deviceId':self.device_id,'updatePolicy':'automatic','allowDowngrade':False,'validSeconds':600},token=self.admin_token)['id']
        assignment=assign(releases[0])
        require(self.ota('0.0.1',name='bread-compact-wifi-128x32-ai-release') is None
                and self.ota('0.0.1',flash=8*1024*1024) is None, 'wrong variant or flash accepted generic firmware')
        firmware=self.ota('0.0.1')
        require(isinstance(firmware,dict) and firmware.get('version')==version and firmware.get('board_name')==VARIANT
                and type(firmware.get('schema_version')) is int and firmware['schema_version']==1
                and type(firmware.get('size')) is int and firmware['size']==len(app)
                and firmware.get('sha256')==app_meta['sha256'] and firmware.get('development_only') is True
                and firmware.get('update_policy')=='automatic', 'OTA firmware manifest invalid')
        asset_info=firmware.get('assets')
        require(isinstance(asset_info,dict) and type(asset_info.get('size')) is int and asset_info['size']==len(assets)
                and asset_info.get('sha256')==assets_meta['sha256'] and asset_info.get('required') is False
                and isinstance(asset_info.get('url'),str), 'OTA assets metadata invalid')
        require(self.ota(version).get('release_id')==releases[0], 'same-version health manifest missing')
        ticket=firmware['url']; require(self.download(ticket)==app, 'download bytes do not match app')
        self.download(ticket,'assets',expected=401)
        asset_url=asset_info['url']; require(self.download(asset_url,kind='assets')==assets, 'asset bytes mismatch')
        self.result('POST',endpoint+'/assignments/'+assignment+'/pause',{},token=self.admin_token)
        self.download(ticket,expected=403)
        require(self.ota('0.0.1') is None, 'paused controlled device fell back to generic OTA')
        self.checks.append('exact_board_flash_same_version_and_ticket_bytes_pause_wrong_kind')
        assign(releases[0]); old=self.ota('0.0.1')['url']; assign(releases[1]); self.download(old,expected=403)
        second=self.ota('0.0.1'); second_ticket=second['url']
        self.result('POST',endpoint+'/releases/'+releases[1]+'/paused',{},token=self.admin_token)
        self.download(second_ticket,expected=403)
        require(self.ota('0.0.1') is None, 'paused release fell back to generic OTA')
        self.result('POST',endpoint+'/releases/'+releases[1]+'/published',{},token=self.admin_token)
        self.ota('0.0.1')
        self.checks.append('reassignment_and_release_pause_revoke_existing_tickets')
        receipt_path='/ota/esp32/receipts'
        def receipt(key,state,token=True):
            return self.wire('POST',receipt_path,{'release_id':releases[1],'event_key':key,'status':state,'version':next_version},headers=self.device_headers(token),device=True)
        require(receipt('missing-auth','downloading',False)[0]==401, 'receipt accepted missing credential')
        require(receipt('bad-order','healthy')[0]==409, 'receipt accepted skipped states')
        for state in ('downloading','verified','booted','healthy'):
            require(receipt('fixture-'+state,state)[0]==204, 'receipt phase rejected: '+state)
        require(receipt('fixture-healthy','healthy')[0]==204, 'idempotent receipt retry failed')
        require(receipt('after-terminal','failed')[0]==409, 'terminal receipt was overwritten')
        require(self.ota(next_version) is None, 'healthy controlled device received generic OTA fallback')
        self.checks.append('authenticated_ordered_idempotent_receipts_and_terminal_generic_no_fallback')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repo',type=Path,required=True);p.add_argument('--work',type=Path,required=True)
    p.add_argument('--jar',type=Path,required=True);p.add_argument('--jar-sha256',required=True)
    p.add_argument('--app',type=Path);p.add_argument('--assets',type=Path);p.add_argument('--candidate-manifest',type=Path);p.add_argument('--verifier',type=Path)
    p.add_argument('--mysql-info',type=Path,default=Path('/Users/lengwang/code/esp32/work/b3-native-test.json'))
    p.add_argument('--mysql-credential-source',type=Path,default=Path('/Users/lengwang/code/esp32/work/run-integrated-20261031.py'))
    args=p.parse_args();args.compiled_runtime=None;args.provider_config=None
    acceptance=Acceptance(args)
    try:
        acceptance.unpack_jar();acceptance.start();acceptance.start_proxy();acceptance.execute();acceptance.proof['result']='passed'
    except Exception as failure:
        acceptance.proof.update(result='failed',failure_type=type(failure).__name__,failure=str(failure) if isinstance(failure,RuntimeError) else 'sanitized acceptance failure')
    finally:
        acceptance.stop()
        acceptance.proof['own_api_redis_processes_stopped']=all(p.poll() is not None for p in acceptance.processes)
        def closed(port):
            with socket.socket() as probe: return probe.connect_ex(('127.0.0.1',port)) != 0
        acceptance.proof['own_ports_closed']=closed(acceptance.port) and closed(acceptance.redis_port)
        if acceptance.proxy_port is not None:
            with socket.socket() as probe:
                acceptance.proof['ota_proxy_port_closed'] = probe.connect_ex(('192.168.3.152',acceptance.proxy_port)) != 0
            acceptance.proof['own_ports_closed'] &= acceptance.proof['ota_proxy_port_closed']
        acceptance.proof['schema_retained']=True
        base.private_json(acceptance.folder/'proof.json',acceptance.proof)
        print(json.dumps({'result':acceptance.proof['result'],'proof':str(acceptance.folder/'proof.json'),
                          'schema':acceptance.schema,'retained':True,'checks':len(acceptance.checks)}))
    return 0 if acceptance.proof['result']=='passed' and acceptance.proof['own_ports_closed'] else 1


if __name__=='__main__':raise SystemExit(main())
