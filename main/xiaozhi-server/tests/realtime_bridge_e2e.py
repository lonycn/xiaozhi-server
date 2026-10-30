#!/usr/bin/env python3
"""Opt-in REAL provider + isolated Spring/Python bridge acceptance; never a unit test.

Creates a brand-new loopback MySQL schema, private runtime config and owned API/worker
processes. Uses only the supplied repository audio sample. Existing schemas and
Redis data are never reset. Requires explicit --allow-real-provider.
"""
import argparse
import asyncio
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import time
from urllib.parse import urlsplit
import aiohttp
from aiohttp import web
import httpx


TRACE_BOOTSTRAP = r"""
import json,os,sys,hashlib
from aiohttp import web
from realtime_worker.config import Config
from realtime_worker.provider import Provider
from realtime_worker.server import create_app
config=Config.load(sys.argv[1])
fd=os.open(sys.argv[2],os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
output=os.fdopen(fd,'w',buffering=1)
def record(direction,raw):
 try:
  value=json.loads(raw)
  item=value.get('item') or {}
  response=value.get('response') or {}
  error=value.get('error') or {}
  data=dict(direction=direction,type=value.get('type'),event_id=value.get('event_id'),
   keys=sorted(value),item_keys=sorted(item),item_id=item.get('id'),top_item_id=value.get('item_id'),
   item_type=item.get('type'),role=item.get('role'),response_id=value.get('response_id') or response.get('id'),
   previous_item_id=value.get('previous_item_id'),text_hashes=[hashlib.sha256(part['text'].encode()).hexdigest() for part in item.get('content',[]) if isinstance(part,dict) and isinstance(part.get('text'),str)],
   status=response.get('status'),content_types=[part.get('type') for part in item.get('content',[]) if isinstance(part,dict)],
   error_code=error.get('code') or value.get('code'))
  output.write(json.dumps(data)+'\n')
 except Exception: pass
class Wire:
 def __init__(self,ws):self.ws=ws
 def __aiter__(self):return self
 async def __anext__(self):
  raw=await self.ws.recv();record('receive',raw);return raw
 async def send(self,raw):record('send',raw);return await self.ws.send(raw)
 async def close(self):return await self.ws.close()
class Traced(Provider):
 async def connect(self):return Wire(await super().connect())
web.run_app(create_app(config,provider=Traced(config)),host=config.host,port=config.port,access_log=None,print=None,shutdown_timeout=1)
"""


class CheckFailure(Exception):
    pass


def private_json(path, data):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, 'w') as output: json.dump(data, output, ensure_ascii=False, indent=2)
    Path(path).chmod(0o600)


class Acceptance:
    def __init__(self, args):
        self.args = args
        self.native = json.loads(Path(args.native_config).read_text())
        self.database_password = os.environ['REALTIME_E2E_DB_PASSWORD']
        self.stamp = time.strftime('%Y%m%d_%H%M%S')+'_'+secrets.token_hex(3)
        self.database = 'xiaozhi_ai_test_rt_http_'+self.stamp
        self.runtime = Path(args.runtime_root)/self.stamp
        self.runtime.mkdir(parents=True, mode=0o700)
        self.worker_key = secrets.token_urlsafe(48)
        self.base = f'http://127.0.0.1:{args.api_port}/xiaozhi'
        self.accounts = {}; self.processes=[]; self.files=[]
        self.http = httpx.AsyncClient(timeout=15,trust_env=False)
        self.observer = None; self.captures=[]; self.claims=[]; self.ws_events=[]
        self.proof = dict(result='running',sample='repository technical WAV normalized to mono PCM16/16k; no microphone/browser',
            database=self.database,api_url=self.base,worker_port=args.worker_port,
            model='qwen3.8-omni-flash-realtime',existing_business_data_modified=False,
            real_provider=True,checks={},sessions=[],usage=[],runtime_path=str(self.runtime))

    def sql(self, query, database=True):
        if not re.fullmatch(r'xiaozhi_ai_test_rt_http_[a-z0-9_]+',self.database): raise CheckFailure('UNSAFE_DATABASE')
        # No database name/statement is taken from a provider/browser.
        cmd=['mysql','-h127.0.0.1','-P'+str(self.native['port']),'-uroot','--default-character-set=utf8mb4','-N','-B']
        if database: cmd.append(self.database)
        result=subprocess.run(cmd,input="SET time_zone='+08:00';\n"+query,text=True,capture_output=True,
            env={**os.environ,'MYSQL_PWD':self.database_password},timeout=30)
        if result.returncode:
            code=re.search(r'ERROR ([0-9]+)',result.stderr)
            raise CheckFailure('DISPOSABLE_SQL_FAILED_'+(code.group(1) if code else 'UNKNOWN'))
        return result.stdout.strip()

    def check(self, name, passed=True):
        if not passed: raise CheckFailure(name)
        self.proof['checks'][name]=True
        print('PASS '+name,flush=True)

    def start_process(self, name, command, cwd):
        log=self.runtime/(name+'.log')
        fd=os.open(log,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        output=os.fdopen(fd,'w');self.files.append(output)
        child=subprocess.Popen(command,cwd=cwd,stdout=output,stderr=subprocess.STDOUT,start_new_session=True,
            env={**os.environ,'PYTHONPATH':str(Path(self.args.checkout)/'main/xiaozhi-server')})
        self.processes.append((name,child))
        private_json(self.runtime/'processes.json',{n:p.pid for n,p in self.processes})
        return child

    async def call(self, method, path, body=None, actor='owner', expected=200):
        headers={}
        if actor: headers['Authorization']='Bearer '+self.accounts[actor]['token']
        response=await self.http.request(method,self.base+path,json=body,headers=headers)
        try: data=response.json()
        except ValueError: raise CheckFailure('NON_JSON_HTTP_'+str(response.status_code)) from None
        if response.status_code!=expected or data.get('code')!=(0 if expected==200 else expected):
            message=data.get('msg','')
            safe=message if isinstance(message,str) and re.fullmatch(r'[A-Z0-9_]{1,100}',message) else 'HTTP_FAILED'
            raise CheckFailure(safe+'_'+str(response.status_code))
        return data.get('data')

    async def forward(self, request):
        path=request.path
        if (not path.startswith('/xiaozhi/internal/realtime-worker/')
                or request.headers.get('X-Realtime-Worker-Key')!=self.worker_key):
            return web.Response(status=403)
        body=await request.json()
        response=await self.http.post('http://127.0.0.1:'+str(self.args.api_port)+path,json=body,
            headers={'X-Realtime-Worker-Key':self.worker_key})
        payload=response.json()
        if path.endswith('/consume') and response.status_code==200 and payload.get('code')==0:
            self.claims.append(payload['data']) # Private transient memory; never included in public proof.
        if path.endswith('/events'):
            self.captures.append(dict(path=path,body=body,status=response.status_code,ack=payload.get('data')))
        return web.Response(status=response.status_code,body=response.content,content_type='application/json')

    async def setup(self):
        if shutil.disk_usage(self.runtime).free < Path(self.args.jar).stat().st_size + (128 << 20):
            raise CheckFailure('INSUFFICIENT_TEST_RUNTIME_DISK')
        self.sql('CREATE DATABASE '+self.database+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;',False)
        # Use a verified empty Redis logical DB and preseed the existing JSON version key,
        # avoiding the legacy SystemInitConfig FLUSHDB branch altogether.
        count=subprocess.run(['redis-cli','-p',str(self.args.redis_port),'-n',str(self.args.redis_database),'DBSIZE'],capture_output=True,text=True,check=True).stdout.strip()
        if count!='0': raise CheckFailure('REDIS_TEST_DATABASE_NOT_EMPTY')
        version=re.search(r'public static final String VERSION = "([^"]+)"',
            (Path(self.args.checkout)/'main/manager-api/src/main/java/xiaozhi/common/constant/Constant.java').read_text()).group(1)
        result=subprocess.run(['redis-cli','-p',str(self.args.redis_port),'-n',str(self.args.redis_database),
            'SET','sys:version',json.dumps(version),'NX'],capture_output=True,text=True,check=True)
        if result.stdout.strip()!='OK': raise CheckFailure('REDIS_VERSION_SETUP_FAILED')
        props={
            'server.address':'127.0.0.1','server.port':self.args.api_port,'server.tomcat.threads.max':32,'server.tomcat.threads.min-spare':2,
            'spring.datasource.druid.url':f'jdbc:mysql://127.0.0.1:{self.native["port"]}/{self.database}?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true&socketTimeout=30000&connectTimeout=5000',
            'spring.datasource.druid.username':'root','spring.datasource.druid.password':self.database_password,
            'spring.datasource.druid.initial-size':1,'spring.datasource.druid.min-idle':1,'spring.datasource.druid.max-active':8,
            'spring.data.redis.host':'127.0.0.1','spring.data.redis.port':self.args.redis_port,'spring.data.redis.database':self.args.redis_database,
            'features.conversation-enabled':'true','features.support-handoff-enabled':'true','features.ai-text-enabled':'true',
            'features.attachments-enabled':'false','features.annotations-enabled':'false','features.support-media-enabled':'false',
            'features.realtime-ai-enabled':'true','ai.worker.key':secrets.token_urlsafe(48),'ai.execution.model':'qwen-flash',
            'realtime.worker.key':self.worker_key,'realtime.worker.public-url':f'ws://127.0.0.1:{self.args.worker_port}/realtime',
            'logging.level.xiaozhi':'WARN','logging.level.liquibase':'WARN'}
        properties=self.runtime/'application.properties'
        fd=os.open(properties,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'w') as output: output.write('\n'.join(f'{key}={value}' for key,value in props.items())+'\n')
        # An independent inode prevents a concurrent build from mutating this running JAR.
        jar=self.runtime/'manager.jar';shutil.copy2(self.args.jar,jar)
        self.proof['jar_sha256']=hashlib.sha256(jar.read_bytes()).hexdigest()
        java=self.args.java
        child=self.start_process('api',[java,'-Duser.timezone=Asia/Shanghai','-jar',str(jar),
            '--spring.config.additional-location=file:'+str(properties)],self.args.checkout)
        for _ in range(240):
            if child.poll() is not None: raise CheckFailure('API_STARTUP_FAILED')
            try:
                result=await self.http.get(self.base+'/api/v1/ai/conversations')
                if result.status_code==401: break
            except httpx.TransportError: pass
            await asyncio.sleep(.5)
        else: raise CheckFailure('API_STARTUP_TIMEOUT')
        self.check('latest_spring_api_started_isolated')
        self.proof['changelog_rows']=int(self.sql('SELECT COUNT(*) FROM DATABASECHANGELOG;'))
        base=8_100_000_000_000_000_000+int(time.time()*1000)*10
        for n,name in enumerate(('owner','outsider')):
            actor=base+n;token=secrets.token_hex(32)
            self.sql(f"INSERT INTO sys_user(id,username,status,super_admin) VALUES({actor},'rt-http-{name}-{self.stamp}',1,0);"
                     f"INSERT INTO sys_user_token(id,user_id,token,expire_date) VALUES({actor},{actor},'{token}',DATE_ADD(NOW(),INTERVAL 1 DAY));")
            self.accounts[name]=dict(id=str(actor),token=token)
        private_json(self.runtime/'accounts.json',self.accounts)
        observer_app=web.Application(client_max_size=1<<20)
        observer_app.router.add_post('/xiaozhi/internal/realtime-worker/{tail:.*}',self.forward)
        self.observer=web.AppRunner(observer_app,access_log=None);await self.observer.setup()
        site=web.TCPSite(self.observer,'127.0.0.1',0);await site.start()
        observer_port=site._server.sockets[0].getsockname()[1]
        provider=json.loads(Path(self.args.provider_config).read_text())
        endpoint=urlsplit(provider.get('vision_base_url') or provider['base_url'])
        worker=dict(enabled=True,manager_url=f'http://127.0.0.1:{observer_port}/xiaozhi',worker_key=self.worker_key,
            provider_url='wss://'+endpoint.netloc+'/api-ws/v1/realtime',
            api_key=provider.get('vision_api_key') or provider['api_key'],host='127.0.0.1',port=self.args.worker_port,
            worker_id='realtime-e2e',server_id='realtime-e2e-'+self.stamp,
            origins=['http://127.0.0.1:18894'])
        worker_path=self.runtime/'worker.json';private_json(worker_path,worker)
        # Transparent metadata-only observer: bytes/events remain the real provider stream.
        bootstrap=self.runtime/'worker_trace.py'
        bootstrap.write_text(TRACE_BOOTSTRAP)
        bootstrap.chmod(0o600)
        child=self.start_process('worker',[self.args.worker_python,str(bootstrap),str(worker_path),str(self.runtime/'provider-metadata.ndjson')],
            str(Path(self.args.checkout)/'main/xiaozhi-server'))
        for _ in range(80):
            if child.poll() is not None: raise CheckFailure('WORKER_STARTUP_FAILED')
            try:
                reader,writer=await asyncio.open_connection('127.0.0.1',self.args.worker_port)
                writer.close();await writer.wait_closed();break
            except OSError: await asyncio.sleep(.1)
        else: raise CheckFailure('WORKER_STARTUP_TIMEOUT')
        self.check('python_worker_started_loopback')

    async def receive(self, ws, wanted, timeout=40):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            frame=await asyncio.wait_for(ws.receive(),deadline-time.monotonic())
            if frame.type!=aiohttp.WSMsgType.TEXT: raise CheckFailure('BRIDGE_CLOSED_BEFORE_'+wanted.upper().replace('.','_'))
            event=json.loads(frame.data)
            safe={key:value for key,value in event.items() if key not in ('audio','text','delta')}
            if event['type']=='audio.delta':
                raw=base64.b64decode(event['audio'],validate=True)
                self.proof.setdefault('audio_reply_bytes',0);self.proof['audio_reply_bytes']+=len(raw)
                safe['audio_bytes']=len(raw)
            self.ws_events.append(safe)
            if event['type']=='error': raise CheckFailure(event.get('code','BRIDGE_ERROR'))
            if event['type']==wanted: return event
        raise CheckFailure('BRIDGE_EVENT_TIMEOUT')

    async def session(self, client, cid, label):
        session=await self.call('POST','/api/v1/ai/realtime/sessions',dict(conversation_id=cid,
            client_session_id=label+'-'+secrets.token_hex(4),consent=dict(microphone=True,camera=False)))
        self.proof['sessions'].append(session['id'])
        ticket=await self.call('POST','/api/v1/ai/realtime/sessions/'+session['id']+'/connect-ticket',dict(expected_epoch=session['control_epoch']))
        ws=await client.ws_connect(ticket['websocket_url'],origin='http://127.0.0.1:18894')
        await ws.send_json(dict(type='connect',ticket=ticket['ticket']))
        await self.receive(ws,'ready')
        self.check(label+'_real_provider_ready')
        return session,ws,ticket

    async def inject(self, sid, epoch, text, client_id):
        return await self.call('POST','/api/v1/ai/realtime/sessions/'+sid+'/messages',
            dict(expected_epoch=epoch,client_message_id=client_id,text=text))

    async def exercise(self):
        cid=(await self.call('POST','/api/v1/ai/conversations',dict(title='P3 isolated real bridge technical audio')))['id']
        self.proof['conversation_id']=cid
        async with aiohttp.ClientSession() as client:
            session,ws,ticket=await self.session(client,cid,'initial')
            sid=session['id'];epoch=session['control_epoch']
            await self.call('GET','/api/v1/ai/realtime/sessions/'+sid,actor='outsider',expected=404)
            self.check('owner_isolation')
            # Reuse the genuine ticket; no fabricated provider/model events are sent.
            second=await client.ws_connect(ticket['websocket_url'],origin='http://127.0.0.1:18894')
            await second.send_json(dict(type='connect',ticket=ticket['ticket']))
            denied=await second.receive_json();await second.close()
            self.check('ticket_single_use',denied.get('type')=='error')
            result=subprocess.run(['ffmpeg','-nostdin','-v','error','-protocol_whitelist','file,pipe','-i',self.args.audio,
                '-ac','1','-ar','16000','-f','s16le','pipe:1'],capture_output=True,check=True,timeout=15)
            pcm=result.stdout
            if not pcm or len(pcm)>1920000 or len(pcm)%2: raise CheckFailure('TECHNICAL_SAMPLE_FORMAT_LIMIT')
            self.proof['input_pcm_bytes']=len(pcm)
            self.proof['sample_sha256']=hashlib.sha256(Path(self.args.audio).read_bytes()).hexdigest()
            for offset in range(0,len(pcm),3200):
                await ws.send_json(dict(type='audio.append',audio=base64.b64encode(pcm[offset:offset+3200]).decode()))
                await asyncio.sleep(.03)
            await ws.send_json(dict(type='audio.commit'))
            completed=await self.receive(ws,'response.completed',timeout=60)
            self.proof['usage'].append(completed.get('usage'))
            self.check('real_audio_reply_received',self.proof.get('audio_reply_bytes',0)>0)
            self.check('real_audio_completed_actual_usage',completed.get('usage') is not None)
            history=(await self.call('GET','/api/v1/ai/conversations/'+cid+'/messages?limit=100'))['items']
            user=[item for item in history if item['sender_type']=='user' and item['status']=='completed']
            answer=[item for item in history if item['id']==completed['message_id']]
            self.check('actual_input_transcript_and_assistant_durable',bool(user and answer and answer[0]['status']=='completed'))
            self.check('user_before_assistant_sequence',int(user[0]['seq'])<int(answer[0]['seq']))
            self.proof['initial_history']=[dict(id=item['id'],seq=item['seq'],sender_type=item['sender_type'],status=item['status']) for item in history]
            event_order=self.sql(f"SELECT event_type FROM ai_realtime_event WHERE session_id='{sid}' ORDER BY sequence;").splitlines()
            self.check('java_committed_before_response',event_order.index('input.committed')<event_order.index('response.started'))
            short=await self.inject(sid,epoch,'技术验收：请只回复“文本注入已收到”。','short-'+secrets.token_hex(4))
            ack=await self.receive(ws,'injection.status')
            self.check('persisted_text_real_provider_item_ack',ack['injection_id']==short['injection']['id'] and ack['status']=='acknowledged')
            done=await self.receive(ws,'response.completed');self.proof['usage'].append(done.get('usage'))
            self.check('text_injection_reply_completed',bool(done.get('text')))
            long_text='技术验收：请用中文列出30条通用设备巡检注意事项，每条一句。不要作确定故障诊断。'
            long=await self.inject(sid,epoch,long_text,'interrupt-'+secrets.token_hex(4))
            await self.receive(ws,'injection.status');started=await self.receive(ws,'response.started')
            await self.receive(ws,'transcript.delta')
            command=await self.call('POST','/api/v1/ai/realtime/sessions/'+sid+'/interrupt',
                dict(expected_epoch=epoch,client_command_id='cancel-'+secrets.token_hex(4),response_id=started['response_id']))
            canceled=await self.receive(ws,'response.canceled')
            if canceled.get('status')!='canceled': canceled=await self.receive(ws,'response.canceled')
            self.check('real_interrupt_confirmed_and_audio_flush',canceled.get('clear_audio') is True)
            command_state=self.sql(f"SELECT status FROM ai_realtime_command WHERE id='{command['id']}';")
            self.check('interrupt_command_durable_ack',command_state=='acknowledged')
            canceled_state=self.sql(f"SELECT status FROM ai_message WHERE id='{started['message_id']}';")
            self.check('interrupted_partial_kept_canceled',canceled_state=='canceled')
            await self.call('POST','/api/v1/ai/realtime/sessions/'+sid+'/end',dict(expected_epoch=epoch))
            await ws.close()
            ordinary=await self.call('POST','/api/v1/ai/conversations/'+cid+'/messages',dict(client_message_id='ordinary-'+secrets.token_hex(4),
                parts=[dict(type='text',text='技术验收：实时结束后回到普通文字。')]))
            count=self.sql(f"SELECT COUNT(*) FROM ai_run WHERE conversation_id='{cid}' AND input_message_id='{ordinary['id']}' AND status='queued';")
            self.check('normal_text_b2_queue_resumes_after_end',count=='1')
            next_session,next_ws,_=await self.session(client,cid,'reconnect_new_session_context')
            sid2=next_session['id'];epoch2=next_session['control_epoch']
            self.check('new_session_cancels_pending_b2',self.sql(f"SELECT status FROM ai_run WHERE input_message_id='{ordinary['id']}';")=='canceled')
            await self.inject(sid2,epoch2,long_text,'handoff-'+secrets.token_hex(4))
            await self.receive(next_ws,'injection.status');await self.receive(next_ws,'response.started');await self.receive(next_ws,'transcript.delta')
            genuine=[value for value in self.captures if value['status']==200 and value['path'].endswith('/'+sid2+'/events')][-1]
            before=int(self.sql(f"SELECT COUNT(*) FROM ai_realtime_event WHERE session_id='{sid2}';"))
            handoff_time=time.monotonic()
            request=await self.call('POST','/api/v1/ai/conversations/'+cid+'/handoff',dict(
                client_request_id='human-'+secrets.token_hex(4),requested_mode='text'))
            self.proof['support_request_id']=request['request_id']
            # Already-authorized bytes can be queued in a TCP/browser buffer before
            # handoff. Drain them without calling receipt time a new authorized write.
            buffered=0
            while time.monotonic()-handoff_time<6:
                frame=await asyncio.wait_for(next_ws.receive(),6-(time.monotonic()-handoff_time))
                if frame.type!=aiohttp.WSMsgType.TEXT: break
                event=json.loads(frame.data)
                if event.get('type') in ('audio.delta','transcript.delta','response.completed'): buffered+=1
            else: raise CheckFailure('HANDOFF_SOCKET_DID_NOT_CLOSE')
            self.proof['handoff_buffered_frames_drained']=buffered
            self.proof['handoff_close_seconds']=round(time.monotonic()-handoff_time,3)
            self.check('human_handoff_closes_bridge_bounded',self.proof['handoff_close_seconds']<=6)
            current=await self.call('GET','/api/v1/ai/realtime/sessions/'+sid2)
            self.check('human_handoff_cancels_realtime',current['status']=='canceled')
            after=int(self.sql(f"SELECT COUNT(*) FROM ai_realtime_event WHERE session_id='{sid2}';"))
            replay=await self.http.post('http://127.0.0.1:'+str(self.args.api_port)+genuine['path'],
                json=genuine['body'],headers={'X-Realtime-Worker-Key':self.worker_key})
            self.check('genuine_old_lease_callback_replay_rejected',replay.status_code==409)
            await asyncio.sleep(2.2)
            self.check('no_late_event_rows',int(self.sql(f"SELECT COUNT(*) FROM ai_realtime_event WHERE session_id='{sid2}';"))==after)
            self.proof['handoff_event_counts']=dict(before=before,after=after)
            await next_ws.close()
            self.proof['session_summary']=[await self.call('GET','/api/v1/ai/realtime/sessions/'+value) for value in self.proof['sessions']]
            self.proof['response_usage_rows']=self.sql(f"SELECT session_id,status,usage_json FROM ai_realtime_turn WHERE session_id IN ('{sid}','{sid2}') AND usage_json IS NOT NULL ORDER BY created_at;").splitlines()
            self.check('provider_usage_persisted_without_estimates',bool(self.proof['response_usage_rows']))

    async def close(self):
        for name,process in reversed(self.processes):
            if process.poll() is None:
                os.killpg(process.pid,signal.SIGTERM)
                try: await asyncio.to_thread(process.wait,10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid,signal.SIGKILL);await asyncio.to_thread(process.wait,5)
        for output in self.files: output.close()
        if self.observer: await self.observer.cleanup()
        await self.http.aclose()
        self.proof['owned_processes_stopped']=all(process.poll() is not None for name,process in self.processes)
        self.proof['canonical_callback_types']=[dict(type=c['body']['type'],sequence=c['body']['sequence'],http_status=c['status']) for c in self.captures]
        self.proof['browser_events']=self.ws_events
        private_json(Path(self.args.proof),self.proof)


async def run(args):
    check=Acceptance(args)
    try:
        await check.setup();await check.exercise();check.proof['result']='passed'
    except Exception as error:
        check.proof['result']='failed';check.proof['error_type']=type(error).__name__
        if isinstance(error,CheckFailure): check.proof['safe_error']=str(error)
        print('FAIL '+check.proof.get('safe_error',type(error).__name__),flush=True)
    finally: await check.close()
    print(json.dumps(dict(result=check.proof['result'],database=check.database,checks=len(check.proof['checks']),proof=args.proof)),flush=True)
    return 0 if check.proof['result']=='passed' else 1


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-real-provider',action='store_true')
    for name in ('java','checkout','jar','native-config','provider-config','worker-python','audio','runtime-root','proof'):
        parser.add_argument('--'+name,required=True)
    parser.add_argument('--api-port',type=int,default=19899)
    parser.add_argument('--worker-port',type=int,default=18900)
    parser.add_argument('--redis-port',type=int,default=34777)
    parser.add_argument('--redis-database',type=int,default=15)
    args=parser.parse_args()
    if not args.allow_real_provider: parser.error('Explicit --allow-real-provider is required')
    if not os.environ.get('REALTIME_E2E_DB_PASSWORD'): parser.error('REALTIME_E2E_DB_PASSWORD must be supplied privately')
    for port in (args.api_port,args.worker_port):
        with socket.socket() as check:
            if check.connect_ex(('127.0.0.1',port))==0: parser.error('Requested isolated port is already in use')
    raise SystemExit(asyncio.run(run(args)))


if __name__=='__main__': main()
