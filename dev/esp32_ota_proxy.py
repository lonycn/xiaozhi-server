#!/usr/bin/env python3
"""Development-only LAN device ingress. Never exposes Manager/admin/config routes."""
import argparse
import asyncio
import ipaddress
import json
import re
from urllib.parse import urlsplit
from aiohttp import web, ClientSession, ClientTimeout


def local_host(value):
    try:
        ip=ipaddress.IPv4Address(value)
        return any(ip in ipaddress.IPv4Network(n) for n in ['127.0.0.0/8','10.0.0.0/8','172.16.0.0/12','192.168.0.0/16'])
    except ValueError:
        return False


def manager_url(value):
    p=urlsplit(value)
    if p.scheme!='http' or p.hostname not in ['127.0.0.1','localhost'] or p.username or p.password or p.query or p.fragment or p.path.rstrip('/')!='/xiaozhi':
        raise ValueError('Dedicated Manager must be loopback HTTP with /xiaozhi context')
    if p.port is None or not 1<=p.port<=65535:
        raise ValueError('Explicit Manager port required')
    return value.rstrip('/')


def app_for(base):
    base=manager_url(base)
    app=web.Application(client_max_size=65536)

    async def resources(app):
        app['http']=ClientSession(timeout=ClientTimeout(total=90),trust_env=False,auto_decompress=False)
        yield
        await app['http'].close()
    app.cleanup_ctx.append(resources)

    async def relay(request):
        if not local_host(request.remote):
            raise web.HTTPForbidden(text='private_network_only')
        path=request.path
        download=bool(re.fullmatch('/xiaozhi/ota/esp32/releases/[a-f0-9]{32}/(?:app|assets)',path))
        ota=path=='/xiaozhi/ota/' and request.method=='POST' and not request.query
        receipt=path=='/xiaozhi/ota/esp32/receipts' and request.method=='POST' and not request.query
        if download:
            if request.method!='GET' or list(request.query)!=['ticket'] or len(request.query['ticket'])>2048:
                raise web.HTTPBadRequest(text='invalid_download_request')
        elif not (ota or receipt):
            raise web.HTTPNotFound(text='device_route_not_found')
        maximum=16384 if receipt else 65536
        if request.content_length is not None and request.content_length>maximum:
            raise web.HTTPRequestEntityTooLarge(max_size=maximum,actual_size=request.content_length)
        body=bytearray()
        async for data in request.content.iter_chunked(8192):
            body.extend(data)
            if len(body)>maximum:
                raise web.HTTPRequestEntityTooLarge(max_size=maximum,actual_size=len(body))
        headers={}
        for name in ['Device-Id','Client-Id','Authorization','Content-Type','User-Agent','Activation-Version','Accept-Language']:
            values=request.headers.getall(name,[])
            if len(values)>1 or values and len(values[0])>2048:
                raise web.HTTPBadRequest(text='invalid_device_header')
            if values:headers[name]=values[0]
        if request.headers.get('Range'):
            raise web.HTTPBadRequest(text='range_not_supported')
        target=base+path.removeprefix('/xiaozhi')
        response=None
        try:
            async with app['http'].request(request.method,target,params=dict(request.query),data=bytes(body) if body else None,headers=headers,allow_redirects=False) as upstream:
                if 300<=upstream.status<400:
                    raise web.HTTPBadGateway(text='manager_redirect_rejected')
                if upstream.headers.get('Content-Encoding','identity')!='identity':
                    raise web.HTTPBadGateway(text='encoded_manager_response_rejected')
                if receipt:
                    # Status only: never log credentials, download tickets or device payloads.
                    reply=await upstream.content.read(65537)
                    if len(reply)>65536 or not upstream.content.at_eof():
                        raise web.HTTPBadGateway(text='manager_response_limit')
                    try:
                        event=json.loads(body).get('status','unknown')
                        code=json.loads(reply).get('msg','') if reply else ''
                    except (ValueError,AttributeError):event,code='unknown','unparseable'
                    if not isinstance(event,str) or not re.fullmatch('[a-z_]{1,24}',event):event='unknown'
                    if not isinstance(code,str) or not re.fullmatch('[A-Za-z0-9_ ]{0,100}',code):code='request_rejected'
                    print('esp32_receipt='+event+' http='+str(upstream.status)+' code='+code,flush=True)
                    return web.Response(status=upstream.status,body=reply,headers={'Content-Type':upstream.headers.get('Content-Type','application/json'),'Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff'})
                limit=8*1024*1024 if download else 65536
                if upstream.content_length is not None and upstream.content_length>limit:
                    raise web.HTTPBadGateway(text='manager_response_limit')
                response=web.StreamResponse(status=upstream.status,headers={'Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff'})
                for name in ['Content-Type','Content-Length','Content-Disposition']:
                    if name in upstream.headers:response.headers[name]=upstream.headers[name]
                # Download is streamed only after all route and authorization checks.
                await response.prepare(request)
                size=0
                async for chunk in upstream.content.iter_chunked(8192):
                    size+=len(chunk)
                    if size>limit:
                        request.transport.close()
                        return response
                    await response.write(chunk)
                await response.write_eof()
                return response
        except (web.HTTPException,asyncio.CancelledError):
            raise
        except Exception:
            if response is not None and response.prepared:
                if request.transport:request.transport.close()
                return response
            raise web.HTTPServiceUnavailable(text='manager_unavailable') from None

    app.router.add_route('*','/{tail:.*}',relay)
    return app


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manager',required=True);parser.add_argument('--bind',default='127.0.0.1');parser.add_argument('--port',type=int,default=19183)
    args=parser.parse_args()
    if not local_host(args.bind) or args.port in [18880,18882,18883,18893,18894] or not 1024<=args.port<=65535:
        parser.error('Private IPv4 and a dedicated unprivileged port required')
    # Access logging is disabled so download tickets and device identities stay out of logs.
    web.run_app(app_for(args.manager),host=args.bind,port=args.port,access_log=None,print=None)


if __name__=='__main__':main()
