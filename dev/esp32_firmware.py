#!/usr/bin/env python3
"""Review/import immutable ESP32 packages; publishing and assignment are explicit commands."""
import argparse
import importlib.util
import ipaddress
import json
import os
import re
import sys
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError('Manager redirect rejected')


def trusted_base(value, development=False):
    p=urlsplit(value)
    if p.username or p.password or p.query or p.fragment or not p.hostname:
        raise ValueError('Invalid Manager URL')
    if p.scheme=='https':
        return value.rstrip('/')
    if development and p.scheme=='http':
        try:
            ip=ipaddress.IPv4Address(p.hostname)
            if any(ip in ipaddress.IPv4Network(n) for n in ['127.0.0.0/8','10.0.0.0/8','172.16.0.0/12','192.168.0.0/16']):
                return value.rstrip('/')
        except ValueError:
            pass
    raise ValueError('HTTPS required; literal private IPv4 HTTP requires --development-lan')


class Client:
    def __init__(self, base, token_path, development=False):
        self.base=trusted_base(base,development)
        if token_path.stat().st_mode & 0o077:
            raise ValueError('Token file must be private (chmod 600)')
        token=token_path.read_text().strip()
        if token.startswith('{'):
            token=json.loads(token)['token']
        if not token or len(token)>4096 or '\n' in token or '\r' in token:
            raise ValueError('Invalid private Bearer token')
        self._token=token
        self.http=build_opener(ProxyHandler({}),NoRedirect())

    def call(self, method, path, data=None, binary=False):
        headers={'Authorization':'Bearer '+self._token}
        if data is not None:
            if binary:
                headers['Content-Type']='application/octet-stream'
            else:
                data=json.dumps(data).encode();headers['Content-Type']='application/json'
        request=Request(self.base+'/api/v1/esp32/firmware/'+path,data=data,headers=headers,method=method)
        try:
            with self.http.open(request,timeout=45) as response:
                raw=response.read(1024*1024+1)
        except HTTPError as error:
            body=error.read(4096)
            try:code=json.loads(body).get('msg','request_rejected')
            except (ValueError,AttributeError):code='request_rejected'
            if not isinstance(code,str) or not re.fullmatch('[A-Za-z0-9_ ]{1,100}',code):code='request_rejected'
            raise ValueError(f'Manager HTTP {error.code}: {code}') from None
        if len(raw)>1024*1024:
            raise ValueError('Manager response too large')
        value=json.loads(raw)
        if isinstance(value,dict) and 'code' in value:
            if value['code']!=0:raise ValueError('Manager application error')
            return value['data']
        return value


def import_package(client, package, verifier, development=False):
    spec=importlib.util.spec_from_file_location('esp32_package_verifier',verifier)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    manifest=module.verify(package,development_lan=development)
    # The verifier returns the decoded, hash-checked metadata, never a published URL.
    if manifest is None:manifest=json.loads((package/'manifest.json').read_text())
    settings=client.call('GET','settings')
    expected=settings['public_base_url'].rstrip('/')+'/ota/'
    if manifest['ota_url'].rstrip('/')!=expected.rstrip('/'):
        raise ValueError('Package OTA origin does not match this Manager deployment')
    if bool(manifest['development_only'])!=bool(settings['development_only']):
        raise ValueError('Development package/deployment mismatch')
    artifacts={}
    for kind in ['app','assets']:
        artifacts[kind]=client.call('POST','artifacts/'+kind,(package/(kind+'.bin')).read_bytes(),binary=True)
        expected=manifest['firmware']['sha256'] if kind=='app' else manifest['firmware']['assets']['sha256']
        if artifacts[kind]['sha256']!=expected:
            raise ValueError('Manager artifact digest mismatch')
    request={'idempotencyKey':'package-'+manifest['board_name']+'-'+manifest['app_version'],
             'version':manifest['app_version'],'boardType':manifest['board_type'],'boardName':manifest['board_name'],
             'chip':manifest['chip'],'minFlashSize':manifest['flash_bytes'],'app':artifacts['app'],'assets':artifacts['assets'],
             'assetsVersion':manifest['assets_version'],'sourceSha':manifest['source_sha'],'idfVersion':manifest['idf_version']}
    return client.call('POST','releases',request)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base-url',required=True);p.add_argument('--token-file',type=Path,required=True)
    p.add_argument('--development-lan',action='store_true')
    sub=p.add_subparsers(dest='command',required=True)
    sub.add_parser('list');sub.add_parser('settings')
    imp=sub.add_parser('import-package');imp.add_argument('--package',type=Path,required=True);imp.add_argument('--verifier',type=Path,required=True)
    for action in ['publish','pause']:
        command=sub.add_parser(action);command.add_argument('--release',required=True)
    command=sub.add_parser('assign');command.add_argument('--release',required=True);command.add_argument('--device-id',required=True)
    command.add_argument('--policy',choices=['automatic','manual'],default='manual');command.add_argument('--valid-seconds',type=int,default=86400);command.add_argument('--allow-downgrade',action='store_true')
    command=sub.add_parser('pause-assignment');command.add_argument('--assignment',required=True)
    command=sub.add_parser('history');command.add_argument('--device-id',required=True)
    command=sub.add_parser('usb-bootstrap');command.add_argument('--assignment',required=True);command.add_argument('--proof',type=Path,required=True)
    a=p.parse_args();client=Client(a.base_url,a.token_file,a.development_lan)
    for key in ['release','device_id','assignment']:
        value=getattr(a,key,None)
        pattern='[A-Za-z0-9._:-]{1,32}' if key=='device_id' else '[A-Za-z0-9._-]{1,32}'
        if value is not None and not re.fullmatch(pattern,value):raise ValueError('Invalid identifier')
    if a.command=='import-package':result=import_package(client,a.package,a.verifier,a.development_lan)
    elif a.command=='list':result=client.call('GET','releases')
    elif a.command=='settings':result=client.call('GET','settings')
    elif a.command in ['publish','pause']:result=client.call('POST','releases/'+a.release+('/published' if a.command=='publish' else '/paused'))
    elif a.command=='assign':result=client.call('POST','releases/'+a.release+'/assignments',{'deviceId':a.device_id,'updatePolicy':a.policy,'allowDowngrade':a.allow_downgrade,'validSeconds':a.valid_seconds})
    elif a.command=='history':result=client.call('GET','devices/'+a.device_id+'/assignments')
    elif a.command=='usb-bootstrap':result=client.call('POST','assignments/'+a.assignment+'/usb-bootstrap',json.loads(a.proof.read_text()))
    else:result=client.call('POST','assignments/'+a.assignment+'/pause')
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__=='__main__':
    try:main()
    except (ValueError,OSError) as error:
        print(str(error),file=sys.stderr);sys.exit(1)
