"""Wall-clock Runtime/HTTP simulator soak. No physical audio or real-provider claim."""
import argparse, asyncio, json, resource, time, tracemalloc, uuid
from pathlib import Path
from unittest.mock import AsyncMock, patch
from aiohttp import web
from tests.test_esp32_gateway import GatewayTests
from esp32_gateway.runtime import GatewayRuntime
class Fixture(GatewayTests):
    fault=False
    sequence=0
    async def api(self,request):
        if self.fault:return web.json_response({'code':'injected_outage'},status=503)
        response=await super().api(request)
        if request.match_info['path']=='sessions/input':
            self.sequence+=1
            return web.json_response({'id':f'{self.sequence:032x}','control_version':self.sequence})
        if len(self.calls)>512:self.calls=self.calls[-256:]
        return response
async def run(duration,output):
    fixture=Fixture();await fixture.asyncSetUp();fixture.status='completed';started=time.monotonic();tracemalloc.start();baseline=tracemalloc.get_traced_memory()[0]
    turns=commands=unknown=reconnects=peak_tasks=peak_queue=faults=0;runtime=None;next_fault=min(60,duration/3)
    try:
        while time.monotonic()-started<duration:
            if runtime is None:
                conn=fixture.conn();runtime=GatewayRuntime(conn,fixture.config());conn.esp32_gateway=runtime;await runtime.start()
                if not runtime.active:raise RuntimeError('registration failed')
            elapsed=time.monotonic()-started
            if elapsed>=next_fault and faults<6:
                fixture.fault=True
                try:await runtime._turn('during injected weak network')
                finally:fixture.fault=False
                if runtime.active:raise RuntimeError('outage did not fail closed')
                await runtime.close();runtime=None;fixture.generation+=1;reconnects+=1;faults+=1;next_fault+=max(10,duration/6);await asyncio.sleep(.2);continue
            await runtime._turn('synthetic bounded continuous turn');turns+=1
            if not runtime.active:raise RuntimeError('unexpected runtime failure')
            peak_queue=max(peak_queue,conn.tts.tts_text_queue.qsize())
            if conn.tts.tts_text_queue.qsize()!=3:raise RuntimeError('duplicate/missing synthetic playback')
            while not conn.tts.tts_text_queue.empty():conn.tts.tts_text_queue.get_nowait()
            if turns%15==0:
                command={'id':str(uuid.uuid4()),'tool':'self.get_device_status','arguments':'{}','deadline':int(time.time()*1000)+5000};timeout=turns%120==0
                call=AsyncMock(side_effect=TimeoutError()) if timeout else AsyncMock(return_value='{"audio_speaker":{"volume":30}}')
                with patch('core.providers.tools.device_mcp.mcp_handler.call_mcp_tool',new=call):await runtime._command(command)
                if call.await_count!=1:raise RuntimeError('blind MCP retry')
                result=next(body for path,body in reversed(fixture.calls) if path=='result')
                if result['status']!=('unknown' if timeout else 'succeeded'):raise RuntimeError('wrong MCP outcome')
                commands+=1;unknown+=int(timeout)
            peak_tasks=max(peak_tasks,len(asyncio.all_tasks()));conn.websocket.send.reset_mock()
            if turns%30==0:Path(output).write_text(json.dumps({'status':'running','elapsed_seconds':round(elapsed,2),'turns':turns,'reconnects':reconnects,'provider_calls':0,'hardware':False}))
            await asyncio.sleep(1)
        current,peak=tracemalloc.get_traced_memory()
        if current-baseline>64*1024*1024 or peak_queue>3 or peak_tasks>16:raise RuntimeError('resource bound exceeded')
        result={'status':'passed','elapsed_seconds':round(time.monotonic()-started,2),'turns':turns,'mcp_commands':commands,'unknown_acks':unknown,'reconnects':reconnects,'max_tts_queue':peak_queue,'max_async_tasks':peak_tasks,'traced_memory_growth_bytes':current-baseline,'peak_traced_bytes':peak,'max_rss':resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,'provider_calls':0,'hardware':False,'real_asr_tts':False,'scope':'GatewayRuntime plus real HTTP simulator; synthetic TTS callbacks and MCP'}
        Path(output).write_text(json.dumps(result,indent=2));print(json.dumps(result))
    finally:
        if runtime:await runtime.close()
        await fixture.asyncTearDown();tracemalloc.stop()
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--seconds',type=int,default=1800);parser.add_argument('--output',required=True);args=parser.parse_args()
    if not 3<=args.seconds<=7200:raise SystemExit('duration outside bounded range')
    asyncio.run(run(args.seconds,args.output))
