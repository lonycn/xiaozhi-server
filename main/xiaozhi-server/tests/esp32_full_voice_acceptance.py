"""Real WS/Opus/ASR/AI/MCP/TTS acceptance with a synthetic ESP32 and a fresh retained DB.
Never connects to existing Dev, native device services, serial ports or business APIs.
"""
import argparse, asyncio, contextlib, hashlib, importlib.util, json, os, queue, sys, threading, time, uuid, wave, zipfile
from pathlib import Path
from types import SimpleNamespace as NS
import websockets
import yaml

REPO=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('esp32_java_fixture',REPO/'main/manager-api/src/test/esp32/http_acceptance.py');fixture_module=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture_module)


def providers():
    # Read the user's already-authorized workspace settings. Never print secrets.
    from dotenv import dotenv_values
    root=REPO.parent
    spec=importlib.util.spec_from_file_location('authorized_workspace_settings',root/'provider_settings.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    env={k:v for k,v in dotenv_values(root/'.env').items() if v};settings=module.resolve_provider_settings(env)
    worker=json.loads((REPO/'dev/.runtime/ai-worker.json').read_text())
    for key in ['asr_key','tts_key']:
        if not settings[key]:settings[key]=worker['api_key']
    if not settings.get('asr_base_url'):settings['asr_base_url']=worker['base_url']
    return settings


def extract_runtime(jar,folder):
    digest=hashlib.sha256(jar.read_bytes()).hexdigest()
    folder=folder.parent/('runtime-'+digest[:16])
    if (folder/'artifact.sha256').exists():
        if (folder/'artifact.sha256').read_text()!=digest:raise RuntimeError('runtime artifact hash mismatch')
        return folder
    folder.mkdir(parents=True,mode=0o700)
    with zipfile.ZipFile(jar) as archive:
        for member in archive.infolist():
            if member.filename.startswith('BOOT-INF/classes/') and not member.is_dir():
                path=folder/'classes'/member.filename[len('BOOT-INF/classes/'):];path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(archive.read(member))
            elif member.filename.startswith('BOOT-INF/lib/') and member.filename.endswith('.jar'):
                path=folder/'libraries'/Path(member.filename).name;path.parent.mkdir(exist_ok=True);path.write_bytes(archive.read(member))
    (folder/'artifact.sha256').write_text(digest)
    return folder


async def execute(a,settings,model_dir,scenario="status",repeat=1,launcher=False):
    from config.config_loader import merge_configs
    from core.websocket_server import WebSocketServer
    from core.providers.tts.alibl_stream import TTSProvider
    from core.utils.opus_encoder_utils import OpusEncoderUtils
    from ai_worker.config import Config
    from ai_worker.transport import Manager
    from ai_worker.worker import Worker
    from ai_worker.provider import Bailian
    from opuslib_next import Decoder
    phrase='请把音量调到30。' if scenario=='volume' else '请调用设备状态工具，告诉我当前音量是多少，不要调整音量。'
    if scenario!='volume':a.sql("UPDATE ai_agent SET system_prompt='Only synthetic fixture device data. Call esp32_get_status exactly once when asked. If the result is unknown, never retry: explain that the device acknowledgement is unknown. Never change volume.' WHERE id='fixture-agent'")
    if scenario=='volume':a.sql("UPDATE ai_agent SET system_prompt='Only the synthetic fixture device is controlled. Execute exactly one esp32_set_volume when the accepted user explicitly requests that exact value. Never claim physical loudness verification.' WHERE id='fixture-agent'")
    tts_config={'type':'alibl_stream','api_key':settings['tts_key'],'ws_url':settings['tts_ws_url'],'model':settings['tts_model'],'voice':settings['tts_voice'],'format':'pcm','output_dir':str(a.folder/'tts-output')}
    seed=TTSProvider(tts_config,True);seed.conn=NS(sample_rate=16000);seed.opus_encoder=OpusEncoderUtils(16000,1,60)
    packets=await asyncio.wait_for(asyncio.to_thread(seed.to_tts,phrase),45)
    if not packets:raise RuntimeError('authorized seed TTS returned no real Opus frames')
    # Keep generated test audio as reviewable synthetic evidence.
    decoder=Decoder(16000,1);pcm=b''.join(decoder.decode(frame,960) for frame in packets if frame)
    with wave.open(str(a.folder/'synthetic-input.wav'),'wb') as audio:audio.setnchannels(1);audio.setsampwidth(2);audio.setframerate(16000);audio.writeframes(pcm)
    cfg=merge_configs(yaml.safe_load((REPO/'main/xiaozhi-server/config.yaml').read_text()),{
      'read_config_from_api':False,'enable_greeting':False,'enable_wakeup_words_response_cache':False,
      'server':{'ip':'127.0.0.1','port':0,'auth_key':a.device_secret,'auth':{'enabled':True,'allowed_devices':[]},'esp32':{'enabled':True,'base_url':a.base,'gateway_key':a.gateway_key,'gateway_id':'full-voice-'+uuid.uuid4().hex[:12],'execution_mode':'ai_worker','allowed_devices':[a.mac]}},
      'xiaozhi':{'audio_params':{'format':'opus','sample_rate':16000,'channels':1,'frame_duration':60}},
      'selected_module':{'VAD':'SileroVAD','ASR':'Workspace','LLM':'UnusedNative','TTS':'WorkspaceTTS','Memory':'nomem','Intent':'nointent'},
      'VAD':{'SileroVAD':{'type':'silero','model_dir':str(model_dir),'threshold':.5,'threshold_low':.2,'min_silence_duration_ms':500}},
      'ASR':{'Workspace':{'type':'qwen_workspace','api_key':settings['asr_key'],'base_url':settings['asr_base_url'],'model_name':settings['asr_model'],'output_dir':str(a.folder/'asr-output'),'language':'zh'}},
      'LLM':{'UnusedNative':{'type':'openai','api_key':a.provider_config()['api_key'],'base_url':a.provider_config()['base_url'],'model_name':a.provider_config()['model']}},
      'TTS':{'WorkspaceTTS':tts_config},'log':{'log_dir':str(a.folder/'gateway-logs'),'log_level':'WARNING'},'enable_stop_tts_notify':False})
    server=None if launcher else WebSocketServer(cfg);done=asyncio.Event();hello=asyncio.Event();software_volume=20
    result={'scenario':scenario,'input_opus_frames':len(packets),'input_pcm_bytes':len(pcm),'received_opus_frames':0,'mcp_status_calls':0,'tts_events':[]};output=[]
    ai_cfg=Config(**a.provider_config(),manager_url=a.base,worker_key=a.worker_key,worker_id='full-voice-real-worker',max_tokens=180,provider_timeout_seconds=35)
    manager=Manager(ai_cfg);worker=Worker(ai_cfg,manager,Bailian(ai_cfg));stop=asyncio.Event()
    async def worker_loop():
        while not stop.is_set():
            task=await manager.claim()
            if task:await worker.execute(task)
            else:await asyncio.sleep(.1)
    async def device(ws):
        nonlocal software_volume
        async for frame in ws:
            if isinstance(frame,bytes):result['received_opus_frames']+=1;output.append(frame);continue
            message=json.loads(frame)
            if message.get('type')=='hello':hello.set()
            elif message.get('type')=='mcp':
                request=message['payload'];method=request.get('method')
                if method=='initialize':value={'protocolVersion':'2024-11-05','capabilities':{'tools':{}},'serverInfo':{'name':'synthetic-esp32','version':'acceptance'}}
                elif method=='tools/list':value={'tools':[{'name':'self.get_device_status','description':'Read software speaker volume','inputSchema':{'type':'object','properties':{}}},{'name':'self.audio_speaker.set_volume','description':'Set software volume','inputSchema':{'type':'object','properties':{'volume':{'type':'integer','minimum':0,'maximum':100}},'required':['volume']}}]}
                elif method=='tools/call':
                    tool=request['params']['name']
                    if tool=='self.audio_speaker.set_volume':
                        if scenario!='volume' or request['params']['arguments']!={'volume':30}:raise RuntimeError('unexpected mutable command')
                        software_volume=30;result['mcp_volume_calls']=result.get('mcp_volume_calls',0)+1;payload='true'
                    elif tool=='self.get_device_status':
                        result['mcp_status_calls']+=1
                        if scenario=='unknown':continue  # Deliberately lose the reply after receipt.
                        payload=json.dumps({'audio_speaker':{'volume':software_volume}})
                    else:raise RuntimeError('unapproved fixture tool')
                    value={'content':[{'type':'text','text':payload}],'isError':False}
                else:continue
                await ws.send(json.dumps({'type':'mcp','payload':{'jsonrpc':'2.0','id':request['id'],'result':value}}))
            elif message.get('type')=='stt':result['asr_text']=message.get('text','')
            elif message.get('type')=='tts':
                if message['state'] in ('start','sentence_start','stop'):
                    if len(message.get('turn_id',''))!=32 or type(message.get('control_version')) is not int:raise RuntimeError('missing canonical TTS identity')
                    result['tts_events'].append({key:message[key] for key in ['state','turn_id','control_version']})
                if message['state']=='stop':done.set()
    @contextlib.asynccontextmanager
    async def listener_context():
        if launcher:
            spec=importlib.util.spec_from_file_location('isolated_candidate_launcher',REPO/'dev/esp32_gateway.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
            cfg['server']['port']=fixture_module.base.available_port();cfg['server']['http_port']=fixture_module.base.available_port()
            cfg['server']['esp32'].update(local_providers=True,deployment_profile='lan_candidate',vad_model_file=str(model_dir/'src/silero_vad/data/silero_vad.onnx'))
            cfg['manager-api']={'url':a.base,'secret':a.device_secret}
            private=a.folder/'launcher.private.yaml';private.write_text(yaml.safe_dump(cfg));private.chmod(0o600)
            native_alive=False
            try:os.kill(52583,0);native_alive=True
            except ProcessLookupError:pass
            launched=await asyncio.to_thread(module.start,private,a.folder/'candidate-runtime',sys.executable)
            result['launcher_start']=launched
            try:yield launched['ws_port']
            finally:
                stopped=await asyncio.to_thread(module.stop,a.folder/'candidate-runtime');result['launcher_stop']=stopped
                result['manager_alive_after_gateway_stop']=all(process.poll() is None for process in a.processes)
                result['native_pid_52583_preserved']=False
                if native_alive:
                    try:os.kill(52583,0);result['native_pid_52583_preserved']=True
                    except ProcessLookupError:pass
                if not stopped.get('stopped') or not result['manager_alive_after_gateway_stop'] or (native_alive and not result['native_pid_52583_preserved']):raise RuntimeError('launcher stop affected an unowned process or did not finish')
        else:
            async with websockets.serve(server._handle_connection,'127.0.0.1',0,max_size=65536,max_queue=16) as listener:
                yield listener.sockets[0].getsockname()[1]
    task=asyncio.create_task(worker_loop())
    try:
        async with listener_context() as port:
            async with websockets.connect(f'ws://127.0.0.1:{port}/xiaozhi/v1/',additional_headers={'Device-Id':a.mac,'Client-Id':a.client_id,'Authorization':'Bearer '+a.device_token,'Protocol-Version':'1'}) as ws:
                reader=asyncio.create_task(device(ws))
                try:
                    await ws.send(json.dumps({'type':'hello','version':1,'transport':'websocket','audio_params':{'format':'opus','sample_rate':16000,'channels':1,'frame_duration':60},'features':{'mcp':True}}));await asyncio.wait_for(hello.wait(),15)
                    for _ in range(100):
                        state=await asyncio.to_thread(a.raw,'GET','/api/v1/esp32/devices/'+a.device_id+'/status',token=a.owner_token)
                        if state.get('state')=='online':break
                        if reader.done():await reader
                        await asyncio.sleep(.1)
                    else:raise RuntimeError('real gateway never registered')
                    for cycle in range(repeat):
                        done.clear()
                        await ws.send(json.dumps({'type':'listen','state':'start','mode':'manual'}))
                        for packet in packets:
                            if packet:await ws.send(packet)
                            await asyncio.sleep(.06)
                        await ws.send(json.dumps({'type':'listen','state':'stop','mode':'manual'}))
                        waiter=asyncio.create_task(done.wait());finished,_=await asyncio.wait([reader,waiter],timeout=100,return_when=asyncio.FIRST_COMPLETED)
                        if reader in finished:await reader
                        if not done.is_set():raise RuntimeError('full voice turn did not reach tagged TTS stop')
                        waiter.cancel()
                finally:reader.cancel();await asyncio.gather(reader,return_exceptions=True)
    finally:
        stop.set();task.cancel();await asyncio.gather(task,return_exceptions=True);await worker.close()
    if not result.get('asr_text') or not output:raise RuntimeError('missing real voice stage')
    if scenario=='volume' and result.get('mcp_volume_calls')!=repeat:raise RuntimeError('volume setter was not executed exactly once')
    if scenario!='volume' and result.get('mcp_status_calls')!=repeat:raise RuntimeError('status was not called exactly once')
    result['durable_tool_states']=a.sql("SELECT state FROM esp32_ai_tool_call ORDER BY created_at").splitlines()
    if scenario=='unknown' and result['durable_tool_states']!=['unknown']*repeat:raise RuntimeError('lost MCP reply was not durably unknown')
    usage=int(a.sql("SELECT COUNT(*) FROM ai_provider_request_usage WHERE total_tokens>0"))
    if usage<1:raise RuntimeError('actual model usage missing')
    a.proof.update(actual_provider=True,provider_usage_requests=usage,asr='actual workspace Qwen ASR over real uploaded Opus',tts='actual CosyVoice synthesis and Opus websocket output',mcp='synthetic ESP32 JSON-RPC device over actual websocket')
    result['provider_usage_requests']=usage
    decoder=Decoder(16000,1);pcm=b''.join(decoder.decode(frame,960) for frame in output)
    with wave.open(str(a.folder/'actual-reply.wav'),'wb') as audio:audio.setnchannels(1);audio.setsampwidth(2);audio.setframerate(16000);audio.writeframes(pcm)
    # After the real WS turn finishes, exercise the Java-side race atomically.
    # Worker is stopped, so these two new queued fixture runs make no provider calls.
    a.connect();old=a.input('old conditional abort fixture');new=a.input('new conditional abort fixture')
    before=a.sql("SELECT status FROM ai_run WHERE input_message_id='"+new['input_message_id']+"'")
    status,payload=a.request('POST','/internal/esp32/sessions/abort-turn',dict(fence=a.fence,sessionId=a.sid,turnId=old['id'],controlVersion=int(old['control_version'])),gateway=True)
    if status!=409 or not isinstance(payload,dict) or payload.get('msg')!='STALE_ABORT':raise RuntimeError('conditional old abort did not return typed HTTP409')
    after=a.sql("SELECT status FROM ai_run WHERE input_message_id='"+new['input_message_id']+"'")
    if before!='queued' or after!=before or a.sql("SELECT status FROM esp32_voice_turn WHERE id='"+new['id']+"'")!='accepted':raise RuntimeError('old abort canceled a newer run')
    result['conditional_abort_http']={'status':status,'error':'STALE_ABORT','new_run_before':before,'new_run_after':after,'new_turn_status':'accepted'}
    result.update(output_pcm_bytes=len(pcm),hardware=False,actual_asr=True,actual_tts=True,actual_model=True,physical_loudness_verified=False)
    return result


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--work',type=Path,required=True);parser.add_argument('--vad-model-dir',type=Path,required=True);parser.add_argument('--scenario',choices=['status','unknown','volume'],default='status');parser.add_argument('--repeat',type=int,default=1);parser.add_argument('--launcher',action='store_true');args=parser.parse_args()
    if not 1<=args.repeat<=3:raise SystemExit('bounded repeat requires 1..3')
    runtime=extract_runtime(args.jar,args.work/('runtime-'+uuid.uuid4().hex[:10]));fixture_args=NS(repo=REPO,work=args.work,mysql_info=Path('/Users/lengwang/code/esp32/work/b3-native-test.json'),mysql_credential_source=Path('/Users/lengwang/code/esp32/work/run-integrated-20261031.py'),provider_config=REPO/'dev/.runtime/ai-worker.json',compiled_runtime=runtime)
    # Native providers set up their synchronous configuration/logger on import.
    # Preload before entering asyncio.run, exactly as app.py does.
    import importlib
    for module in ['core.websocket_server','core.providers.tts.alibl_stream','core.utils.opus_encoder_utils']:
        importlib.import_module(module)
    a=fixture_module.Acceptance(fixture_args);result=1
    try:
        # This extracted runtime is immutable and identified by the reviewed JAR
        # hash. Reuse it read-only to avoid duplicating hundreds of MB per fixture.
        import shutil
        from unittest.mock import patch
        original_copy=shutil.copytree
        def runtime_copy(source,destination,*args,**kwargs):
            if Path(source) in (runtime/'classes',runtime/'libraries'):
                os.symlink(source,destination,target_is_directory=True);return str(destination)
            return original_copy(source,destination,*args,**kwargs)
        with patch.object(shutil,'copytree',side_effect=runtime_copy):a.start()
        a.fixture();proof=asyncio.run(execute(a,providers(),args.vad_model_dir,args.scenario,args.repeat,args.launcher));a.proof.update(result='passed',full_voice=proof,artifact_sha256=hashlib.sha256(args.jar.read_bytes()).hexdigest());result=0
    except Exception as error:
        import traceback
        fixture_module.base.private_json(a.folder/'failure.private.json',{'type':type(error).__name__,'traceback':traceback.format_exc()});a.proof.update(result='failed',safe_error=type(error).__name__)
    finally:
        a.stop();a.proof.update(schema_retained=True,own_processes_stopped=all(process.poll() is not None for process in a.processes));fixture_module.base.private_json(a.folder/'full-voice-proof.json',a.proof);print(json.dumps({'result':a.proof['result'],'proof':str(a.folder/'full-voice-proof.json'),'schema':a.schema,'retained':True}))
    raise SystemExit(result)
if __name__=='__main__':main()
