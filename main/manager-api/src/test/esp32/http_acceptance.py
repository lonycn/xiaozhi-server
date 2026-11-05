#!/usr/bin/env python3
"""ESP32 real HTTP acceptance in a new retained MySQL schema; synthetic hardware only.
Uses the existing authorized Bailian configuration only for the opt-in provider phase.
No existing schema, service, device, business adapter or deployment is modified.
"""
import argparse, asyncio, base64, contextlib, hashlib, hmac, importlib.util, json, os
from pathlib import Path
import secrets, subprocess, sys, time, urllib.request, urllib.error
from types import SimpleNamespace as NS

BASE = Path(__file__).resolve().parents[1] / 'agenttool/http_provider_acceptance.py'
spec = importlib.util.spec_from_file_location('safe_http_acceptance', BASE)
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

class Acceptance(base.Acceptance):
    def __init__(self,args):
        super().__init__(args)
        self.gateway_key=secrets.token_urlsafe(48)
        self.media_key=secrets.token_urlsafe(48);self.realtime_key=secrets.token_urlsafe(48)
        self.device_secret=secrets.token_urlsafe(48)
        self.owner_token=secrets.token_hex(32);self.other_token=secrets.token_hex(32)
        self.device_id='fixture-device';self.agent_id='fixture-agent'
        self.mac='02:00:00:00:32:01';self.client_id='isolated-fixture-client'
        self.proof.update(hardware=False,tts='synthetic callback only',mcp='synthetic fixture only',fixture_owner=70001)
        self.proof['python_source_hashes']={name:hashlib.sha256((self.repo/name).read_bytes()).hexdigest() for name in ('main/xiaozhi-server/esp32_gateway/runtime.py','main/xiaozhi-server/esp32_gateway/client.py','main/xiaozhi-server/tests/esp32_http_acceptance.py')}
    def launch(self,name,arguments):
        if name=='api':
            self.proof['esp32_compiled_class_hashes']={name:hashlib.sha256((self.folder/'classes'/name).read_bytes()).hexdigest() for name in ('xiaozhi/modules/esp32conversation/Esp32ConversationService.class','xiaozhi/modules/esp32gateway/Esp32GatewayService.class','xiaozhi/modules/esp32ai/Esp32CanonicalAiBridge.class','xiaozhi/modules/esp32integration/Esp32BoundaryConfiguration.class')}
            p=self.folder/'application-isolated.properties'
            with p.open('a') as out:
                out.write('\nfeatures.esp32-enabled=true\nesp32.gateway.key='+self.gateway_key+'\nmedia.worker.key='+self.media_key+'\nrealtime.worker.key='+self.realtime_key+'\nspring.data.redis.password=\nfeatures.support-handoff-enabled=true\nfeatures.realtime-ai-enabled=true\n')
        super().launch(name,arguments)
    def request(self,method,path,body=None,token=None,gateway=False,key=None,worker_key=None):
        raw=body if isinstance(body,bytes) else None if body is None else json.dumps(body,separators=(',',':')).encode()
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        if worker_key:headers['X-AI-Worker-Key']=worker_key
        if gateway:headers['X-ESP32-Gateway-Key']=self.gateway_key if key is None else key
        req=urllib.request.Request(self.base+path,data=raw,headers=headers,method=method)
        try:r=urllib.request.urlopen(req,timeout=35)
        except urllib.error.HTTPError as e:r=e
        with r:
            data=r.read()
            try:payload=json.loads(data) if data else None
            except ValueError:payload=None
            return r.status,payload
    def raw(self,method,path,body=None,**opts):
        status,payload=self.request(method,path,body,**opts)
        if status!=200:raise RuntimeError('HTTP fixture failure '+method+' '+path.split('?')[0]+' status='+str(status))
        return payload
    def denied(self,method,path,body=None,statuses=(401,403,409),**opts):
        status,_=self.request(method,path,body,**opts)
        if status not in statuses:raise RuntimeError('Expected denial '+method+' '+path.split('?')[0]+' status='+str(status))
    def fixture(self):
        # All interpolation below is generated fixture data; never accepts user SQL or schema names.
        self.sql("INSERT INTO sys_user(id,username,super_admin,status,create_date,update_date) VALUES(70001,'esp32-fixture-owner',0,1,NOW(),NOW()),(70002,'esp32-fixture-other',0,1,NOW(),NOW());")
        self.sql("INSERT INTO sys_user_token(id,user_id,token,expire_date,create_date,update_date) VALUES(70001,70001,'"+self.owner_token+"',DATE_ADD(NOW(),INTERVAL 1 HOUR),NOW(),NOW()),(70002,70002,'"+self.other_token+"',DATE_ADD(NOW(),INTERVAL 1 HOUR),NOW(),NOW());")
        self.sql("INSERT INTO ai_agent(id,user_id,agent_code,agent_name,llm_model_id,system_prompt,created_at,updated_at) VALUES('fixture-agent',70001,'fixture-agent','Synthetic ESP32 acceptance','LLM_AliLLM','Only synthetic fixture device data; no business records. Use esp32_get_status when asked for status, never change volume.',NOW(),NOW());")
        self.sql("INSERT INTO ai_agent(id,user_id,agent_code,agent_name,llm_model_id,system_prompt,created_at,updated_at) VALUES('fixture-agent-rebound',70001,'fixture-agent-rebound','Synthetic rebound fixture','LLM_AliLLM','Only synthetic fixture data; no business records.',NOW(),NOW());")
        self.sql("INSERT INTO ai_device(id,user_id,mac_address,agent_id,alias,create_date,update_date) VALUES('fixture-device',70001,'"+self.mac+"','fixture-agent','Synthetic fixture only',NOW(),NOW());")
        self.sql("UPDATE sys_params SET param_value='"+self.device_secret+"' WHERE param_code='server.secret';")
        if self.sql("SELECT COUNT(*) FROM sys_params WHERE param_code='server.secret'")=='0':
            self.sql("INSERT INTO sys_params(id,param_code,param_value,param_type) VALUES(70001,'server.secret','"+self.device_secret+"',0);")
        self.refresh_device_token()
        self.checks.append('fresh_schema_explicit_synthetic_owner_agent_device_fixture')
    def refresh_device_token(self):
        ts=str(int(time.time()));sig=hmac.new(self.device_secret.encode(),(self.client_id+'|'+self.mac+'|'+ts).encode(),hashlib.sha256).digest()
        self.device_token=base64.urlsafe_b64encode(sig).decode().rstrip('=')+'.'+ts
    def provider_fixture(self):
        # A second fresh synthetic device keeps real-provider context free of mocked replies.
        self.device_id='fixture-device-provider';self.mac='02:00:00:00:32:02'
        self.sql("INSERT INTO ai_device(id,user_id,mac_address,agent_id,alias,create_date,update_date) VALUES('fixture-device-provider',70001,'"+self.mac+"','fixture-agent','Synthetic provider fixture only',NOW(),NOW());")
        self.refresh_device_token();self.connect()
    def metadata_stable(self):
        session,conversation,binding=self.sid,self.cid,self.fence['bindingVersion']
        self.sql("UPDATE ai_device SET alias='Synthetic renamed fixture',last_connected_at=NOW(),update_date=DATE_ADD(update_date,INTERVAL 2 SECOND) WHERE id='"+self.device_id+"'")
        self.sql("UPDATE ai_agent SET updated_at=DATE_ADD(updated_at,INTERVAL 2 SECOND) WHERE id='fixture-agent';UPDATE sys_user SET update_date=DATE_ADD(update_date,INTERVAL 2 SECOND) WHERE id=70001;")
        self.connect()
        if self.sid!=session or self.cid!=conversation or self.fence['bindingVersion']!=binding:raise RuntimeError('ordinary metadata update revoked trusted conversation history')
        history=self.ok('GET','/api/v1/ai/conversations/'+self.cid+'/messages',token=self.owner_token)
        if len(history.get('items',[]))<2:raise RuntimeError('metadata update lost canonical history')
        self.checks.append('ordinary_alias_last_seen_and_metadata_timestamps_preserve_binding_session_history')
    def register(self):
        context=self.raw('POST','/internal/esp32/register',dict(deviceId=None,deviceMac=self.mac,clientId=self.client_id,token=self.device_token,gatewayId='fixture-http',capabilities=['self.get_device_status']),gateway=True)
        return {key:context[key] for key in ('deviceId','connectionId','generation','gatewayId','bindingVersion')}
    def session(self,action,**fields):
        return self.raw('POST','/internal/esp32/sessions/'+action,dict(fence=self.fence,**fields),gateway=True)
    def connect(self):
        self.fence=self.register()
        self.s=self.session('connect',executionMode='ai_worker');self.sid=self.s['id'];self.cid=self.s['conversation_id']
    def input(self,text,key=None):
        return self.session('input',sessionId=self.sid,inputKey=key or secrets.token_hex(12),text=text)
    def boundary(self):
        reg=dict(deviceMac=self.mac,clientId=self.client_id,token=self.device_token,gatewayId='fixture-http',capabilities=[])
        self.denied('POST','/internal/esp32/register',reg)
        self.denied('POST','/internal/esp32/register',reg,gateway=True,key=self.worker_key)
        self.denied('POST','/internal/esp32/register',reg,token=self.owner_token)
        self.denied('POST','/internal/esp32/sessions/input',b' '*32769,gateway=True,statuses=(413,))
        self.denied('POST','/internal/esp32/sessions/connect',b'{"fence":{},"fence":{},"executionMode":"ai_worker"}',gateway=True,statuses=(400,))
        self.denied('GET','/api/v1/esp32/devices/'+self.device_id+'/status',gateway=True,statuses=(401,))
        self.denied('POST','/internal/ai-worker/claim',dict(worker_id='wrong-realm'),worker_key=self.gateway_key)
        self.checks.append('real_http_gateway_worker_owner_keys_isolated_duplicate_and_body_caps')
        self.connect()
        self.denied('POST','/internal/esp32/sessions/connect',dict(fence=self.fence,executionMode='ai_worker',ownerId=70002),gateway=True,statuses=(400,))
        route='/api/v1/ai/conversations/'+self.cid
        self.denied('POST',route+'/handoff',dict(client_request_id='fixture',requested_mode='text'),token=self.owner_token,statuses=(409,))
        self.denied('POST',route+'/messages',dict(client_message_id='fixture-user-bypass',parts=[dict(type='text',text='must deny')]),token=self.owner_token,statuses=(409,))
        self.denied('POST','/api/v1/ai/realtime/sessions',dict(conversation_id=self.cid,client_session_id='fixture-realtime',consent=dict(microphone=True,camera=False)),token=self.owner_token,statuses=(409,))
        self.denied('GET','/api/v1/esp32/sessions/'+self.sid+'/turns',token=self.other_token,statuses=(404,))
        self.checks.append('real_http_ai_only_handoff_normal_message_realtime_and_cross_owner_denied')
    async def poller(self,stop):
        while not stop.is_set():
            try:
                await asyncio.to_thread(self.raw,'POST','/internal/esp32/heartbeat',self.fence,gateway=True)
                rows=await asyncio.to_thread(self.raw,'POST','/internal/esp32/poll',self.fence,gateway=True)
                for row in rows:
                    if row['tool']!='self.get_device_status':raise RuntimeError('fixture forbids mutable commands')
                    await asyncio.to_thread(self.raw,'POST','/internal/esp32/result',dict(fence=self.fence,commandId=row['id'],success=True,result=dict(synthetic=True,status='ok')),gateway=True)
                await asyncio.sleep(.2)
            except Exception:
                if not stop.is_set():raise
    async def execute(self,actual=False,existing_turn=None):
        sys.path.insert(0,str(self.repo/'main/xiaozhi-server'))
        from ai_worker.config import Config
        from ai_worker.transport import Manager
        from ai_worker.worker import Worker
        from ai_worker.provider import Bailian
        text='Synthetic fixture only: call esp32_get_status exactly once, then describe the returned synthetic status in one short sentence. Do not change volume or access any business data.'
        turn=existing_turn or await asyncio.to_thread(self.input,text)
        if actual:self.proof['actual_provider_attempted']=True
        cfg=Config(**self.provider_config(),manager_url=self.base,worker_key=self.worker_key,worker_id='fixture-http-worker',max_tokens=250,provider_timeout_seconds=30)
        manager=Manager(cfg);task=await manager.claim()
        if not task or task['conversation_id']!=self.cid:raise RuntimeError('canonical claim source mismatch')
        rounds=[]
        class SyntheticProvider:
            @contextlib.asynccontextmanager
            async def stream(self,request):
                rounds.append([m['role'] for m in request['messages']])
                usage=NS(prompt_tokens=4,completion_tokens=4,total_tokens=8)
                if len(rounds)==1:
                    delta=NS(content=None,tool_calls=[NS(index=0,id='fixture_status',type='function',function=NS(name='esp32_get_status',arguments='{}'))])
                    chunks=[NS(id='synthetic-1',choices=[NS(index=0,delta=delta,finish_reason='tool_calls')],usage=usage)]
                else:chunks=[NS(id='synthetic-2',choices=[NS(index=0,delta=NS(content='Synthetic fixture status is ok.',tool_calls=[]),finish_reason='stop')],usage=usage)]
                async def output():
                    for chunk in chunks:yield chunk
                yield output()
            async def close(self):pass
        provider=Bailian(cfg) if actual else SyntheticProvider();worker=Worker(cfg,manager,provider)
        stop=asyncio.Event();poll=asyncio.create_task(self.poller(stop))
        try:await worker.execute(task)
        finally:
            stop.set();await poll;await worker.close()
        reply=await asyncio.to_thread(self.session,'reply',sessionId=self.sid,turnId=turn['id'])
        if reply.get('status')!='completed' or not reply.get('text'):raise RuntimeError('canonical actual worker did not publish completed reply')
        count=self.sql("SELECT COUNT(*) FROM esp32_ai_tool_call WHERE run_id='"+task['run_id']+"' AND state='succeeded'")
        if int(count)<1:raise RuntimeError('actual worker did not execute real HTTP synthetic gateway status command')
        self.checks.append(('authorized_bailian' if actual else 'synthetic_provider')+'_actual_worker_java_gateway_tool_loop_completed')
        self.proof.setdefault('runs',[]).append(dict(run_id=task['run_id'],actual_provider=actual,tool_calls=int(count),reply_chars=len(reply['text']),synthetic_hardware=True))
        if actual:
            usage=self.sql("SELECT COUNT(*) FROM ai_provider_request_usage WHERE run_id='"+task['run_id']+"' AND total_tokens>0")
            if int(usage)<1:raise RuntimeError('authorized provider actual usage ledger missing')
            self.proof['actual_provider']=True;self.proof['provider_usage_requests']=int(usage);self.proof['provider']='authorized Bailian';self.proof['model']=cfg.model
        return task
    async def worker_phase(self,actual=False):
        key=secrets.token_hex(12);text='本轮是隔离技术验收，历史设备状态一律过期。请先且只调用一次 esp32_get_status 工具获取本次连接真实返回的 synthetic fixture 状态，收到工具结果后再用一句中文说明 synthetic 与 status 字段。禁止直接复述历史回复，禁止调用音量设置和业务工具。'
        turn=self.input(text,key);again=self.session('input',sessionId=self.sid,inputKey=key,text=text)
        if turn['id']!=again['id']:raise RuntimeError('duplicate input made another turn')
        count=self.sql("SELECT COUNT(*) FROM ai_run WHERE input_message_id='"+turn['input_message_id']+"'")
        if count!='1':raise RuntimeError('duplicate canonical input made duplicate run')
        await self.execute(actual,turn);self.checks.append('real_http_canonical_input_duplicate_one_message_one_run')
    async def python_gateway_acceptance(self):
        from ai_worker.config import Config
        from ai_worker.transport import Manager
        from ai_worker.worker import Worker
        cfg=Config(**self.provider_config(),manager_url=self.base,worker_key=self.worker_key,worker_id='fixture-python-gateway-worker',max_tokens=100)
        class PlainProvider:
            @contextlib.asynccontextmanager
            async def stream(self,request):
                async def chunks():
                    yield NS(id='synthetic-tts',choices=[NS(index=0,delta=NS(content='Synthetic gateway acceptance reply.',tool_calls=[]),finish_reason='stop')],usage=NS(prompt_tokens=2,completion_tokens=3,total_tokens=5))
                yield chunks()
            async def close(self):pass
        manager=Manager(cfg);worker=Worker(cfg,manager,PlainProvider())
        env=dict(os.environ)
        for key,value in dict(BASE_URL=self.base,GATEWAY_KEY=self.gateway_key,MAC=self.mac,CLIENT_ID=self.client_id,DEVICE_TOKEN=self.device_token,OWNER_TOKEN=self.owner_token).items():env['ESP32_ACCEPTANCE_'+key]=value
        process=await asyncio.create_subprocess_exec(sys.executable,str(self.repo/'main/xiaozhi-server/tests/esp32_http_acceptance.py'),env=env,cwd=str(self.folder),stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE)
        deadline=time.monotonic()+90
        try:
            while process.returncode is None and time.monotonic()<deadline:
                task=await manager.claim()
                if task:await worker.execute(task)
                await asyncio.sleep(.2)
            if process.returncode is None:process.terminate()
            output,error=await asyncio.wait_for(process.communicate(),10)
            # These child outputs intentionally redact tokens, headers and HTTP bodies.
            base.private_json(self.folder/'python-gateway-proof.json',dict(exit=process.returncode,stdout=output.decode(errors='replace'),stderr=error.decode(errors='replace')))
            if process.returncode!=0:raise RuntimeError('Python synthetic gateway HTTP acceptance failed; see private child proof')
            self.checks.append('real_python_gateway_runtime_synthetic_tts_once_status_mcp_once_old_generation_denied')
        finally:
            if process.returncode is None:process.kill();await process.wait()
            await worker.close()
    async def stale(self):
        from ai_worker.config import Config
        from ai_worker.transport import Manager
        turn=self.input('stale callback fence fixture');cfg=Config(**self.provider_config(),manager_url=self.base,worker_key=self.worker_key,worker_id='fixture-stale-worker');manager=Manager(cfg)
        try:task=await manager.claim()
        finally:await manager.close()
        old=self.fence;self.fence=self.register()
        reconnected=self.session('connect',executionMode='ai_worker')
        if reconnected['id']!=self.sid or reconnected['conversation_id']!=self.cid:raise RuntimeError('reconnect created unrelated conversation mapping')
        self.denied('POST','/internal/esp32/heartbeat',old,gateway=True,statuses=(409,))
        self.denied_callback(task,'delta',dict(lease_token=task['lease_token'],sequence=1,text='must never publish'))
        # Isolate binding revocation from the already-tested old-generation revocation.
        rebound_turn=self.input('current generation true binding revocation fixture')
        current_manager=Manager(cfg)
        try:rebound_task=await current_manager.claim()
        finally:await current_manager.close()
        if not rebound_task or rebound_task['input_message_id']!=rebound_turn['input_message_id']:raise RuntimeError('true rebind claim fixture mismatch')
        self.sql("UPDATE ai_device SET agent_id='fixture-agent-rebound',update_date=DATE_ADD(update_date,INTERVAL 2 SECOND) WHERE id='"+self.device_id+"'")
        self.denied_callback(rebound_task,'heartbeat',dict(lease_token=rebound_task['lease_token']))
        self.denied('GET','/api/v1/esp32/sessions/'+self.sid+'/turns',token=self.owner_token,statuses=(403,404,409))
        self.denied('GET','/api/v1/ai/conversations/'+self.cid+'/messages',token=self.owner_token,statuses=(404,))
        self.checks.append('real_http_reconnect_rebind_reject_old_callbacks_and_old_owner_history')


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--repo',type=Path,required=True);p.add_argument('--work',type=Path,required=True)
    p.add_argument('--mysql-info',type=Path,default=Path('/Users/lengwang/code/esp32/work/b3-native-test.json'));p.add_argument('--mysql-credential-source',type=Path,default=Path('/Users/lengwang/code/esp32/work/run-integrated-20261031.py'))
    p.add_argument('--provider-config',type=Path);p.add_argument('--compiled-runtime',type=Path);p.add_argument('--phase',choices=('all','protocol','provider'),default='all')
    args=p.parse_args();args.provider_config=args.provider_config or args.repo/'dev/.runtime/ai-worker.json';a=Acceptance(args)
    try:
        a.start();a.fixture();a.boundary();asyncio.run(a.worker_phase(False));a.metadata_stable()
        if args.phase in ('all','provider'):
            a.provider_fixture();asyncio.run(a.worker_phase(True))
        asyncio.run(a.python_gateway_acceptance());a.connect();asyncio.run(a.stale());a.proof['result']='passed'
    except Exception as e:a.proof.update(result='failed',failure_type=type(e).__name__,failure=str(e) if isinstance(e,RuntimeError) else 'sanitized acceptance failure')
    finally:
        a.stop();a.proof['own_api_redis_processes_stopped']=all(process.poll() is not None for process in a.processes);a.proof['schema_retained']=True
        base.private_json(a.folder/'proof.json',a.proof)
        print(json.dumps(dict(result=a.proof['result'],proof=str(a.folder/'proof.json'),schema=a.schema,retained=True,checks=len(a.checks))))
    return 0 if a.proof['result']=='passed' else 1
if __name__=='__main__':raise SystemExit(main())
