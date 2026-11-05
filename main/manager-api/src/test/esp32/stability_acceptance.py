#!/usr/bin/env python3
"""Bounded ESP32 synthetic stability/fault acceptance, never a physical-device test.
Creates one fresh retained schema and its own API/Redis only. No provider calls.
Tests two gateway generations, natural lease expiry, API-only restart, lost ACK,
turn supersession and exact run/command uniqueness. Separate from App soak tests.
"""
import argparse, asyncio, importlib.util, json, secrets, time
from pathlib import Path

spec=importlib.util.spec_from_file_location('esp32_http_acceptance',Path(__file__).with_name('http_acceptance.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class Stability(module.Acceptance):
    def __init__(self,args):
        super().__init__(args);self.api_arguments=None
        self.proof.update(test_kind='ESP32 synthetic fault stability',physical_device=False,provider_calls=0,app_30min_acceptance=False)
    def launch(self,name,arguments):
        if name=='api':self.api_arguments=list(arguments)
        # Base launch writes the private config once. API restarts reuse it unchanged.
        if name=='api' and getattr(self,'restarting',False):
            module.base.Acceptance.launch(self,'api-restart',arguments)
        else:super().launch(name,arguments)
    def registration(self,gateway):
        context=self.raw('POST','/internal/esp32/register',dict(deviceId=None,deviceMac=self.mac,clientId=self.client_id,token=self.device_token,gatewayId=gateway,capabilities=['self.get_device_status']),gateway=True)
        return {key:context[key] for key in ('deviceId','connectionId','generation','gatewayId','bindingVersion')}
    def reconnect(self,gateway):
        self.fence=self.registration(gateway)
        s=self.session('connect',executionMode='ai_worker')
        if s['id']!=self.sid or s['conversation_id']!=self.cid:raise RuntimeError('trusted reconnect changed conversation mapping')
    def command(self,key=None):
        key=key or secrets.token_hex(12)
        result=self.raw('POST','/api/v1/esp32/devices/'+self.device_id+'/commands',dict(tool='self.get_device_status',arguments={},idempotencyKey=key,timeoutSeconds=60),token=self.owner_token)
        if self.sql("SELECT COUNT(*) FROM esp32_command WHERE idempotency_key='"+key+"'")!='1':raise RuntimeError('command key duplicated ledger row')
        return result,key
    def command_state(self,id):
        return self.raw('GET','/api/v1/esp32/commands/'+id,token=self.owner_token)['state']
    def result(self,fence,id,**result):
        return dict(fence=fence,commandId=id,success=True,result=dict(synthetic=True,status='ok',**result))
    def competition(self):
        a=self.fence;first,key=self.command()
        polled=self.raw('POST','/internal/esp32/poll',a,gateway=True)
        if [r['id']for r in polled]!=[first['id']]:raise RuntimeError('current gateway did not exclusively claim command')
        self.reconnect('synthetic-gateway-b')
        self.denied('POST','/internal/esp32/result',self.result(a,first['id']),gateway=True,statuses=(409,))
        self.denied('POST','/internal/esp32/poll',a,gateway=True,statuses=(409,))
        if self.command_state(first['id'])!='unknown':raise RuntimeError('lost dispatched command was not unknown')
        replay,_=self.command(key)
        if replay['id']!=first['id']:raise RuntimeError('idempotent retry dispatched command again')
        pending,_=self.command();b=self.fence;self.reconnect('synthetic-gateway-a')
        if self.command_state(pending['id'])!='canceled':raise RuntimeError('pending old-generation command was not canceled')
        self.denied('POST','/internal/esp32/heartbeat',b,gateway=True,statuses=(409,))
        self.checks.append('two_gateway_competition_old_poll_and_receipt_denied_dispatched_unknown_pending_canceled_one_command_per_key')
    def short_disconnect(self):
        old=self.fence
        self.raw('POST','/internal/esp32/disconnect',old,gateway=True)
        self.denied('POST','/internal/esp32/heartbeat',old,gateway=True,statuses=(409,))
        self.reconnect('synthetic-reconnect')
        self.checks.append('short_disconnect_reconnect_preserves_trusted_session_and_fences_old_generation')
    async def natural_expiry(self):
        # No artificial DB clock mutation: gateway lease is 30 seconds.
        old=self.fence;started=time.monotonic()
        while time.monotonic()-started<31.2:await asyncio.sleep(min(1,31.2-(time.monotonic()-started)))
        self.denied('POST','/internal/esp32/heartbeat',old,gateway=True,statuses=(409,))
        self.reconnect('synthetic-after-natural-expiry')
        self.proof['natural_lease_wait_seconds']=round(time.monotonic()-started,2)
        self.checks.append('actual_wall_clock_lease_expiry_rejects_old_fence_then_reconnects_same_session')
    def api_restart(self):
        args=self.api_arguments
        if not args:raise RuntimeError('own API arguments missing')
        process=self.processes[-1];process.terminate();process.wait(timeout=15)
        self.restarting=True;self.launch('api',args)
        for _ in range(120):
            if self.processes[-1].poll() is not None:raise RuntimeError('own restarted API exited')
            try:
                if self.http('GET','/user/pub-config')[0]==200:return
            except Exception:pass
            time.sleep(.5)
        raise RuntimeError('own restarted API unavailable after 60 seconds')
    def lost_receipt_restart(self):
        command,key=self.command();fence=self.fence
        polled=self.raw('POST','/internal/esp32/poll',fence,gateway=True)
        if [r['id']for r in polled]!=[command['id']]:raise RuntimeError('receipt-loss fixture poll mismatch')
        receipt=self.result(fence,command['id'])
        # Server commits success, then the synthetic client deliberately discards its ACK.
        self.raw('POST','/internal/esp32/result',receipt,gateway=True)
        self.api_restart()
        self.raw('POST','/internal/esp32/result',receipt,gateway=True)
        same,_=self.command(key)
        if same['id']!=command['id'] or self.command_state(command['id'])!='succeeded':raise RuntimeError('lost ACK replay changed command outcome')
        if self.sql("SELECT COUNT(*) FROM esp32_gateway_audit WHERE command_id='"+command['id']+"' AND event='succeeded'")!='1':raise RuntimeError('lost ACK replay duplicated terminal audit')
        self.denied('POST','/internal/esp32/result',self.result(fence,command['id'],different=True),gateway=True,statuses=(409,))
        self.checks.append('own_api_restart_retains_committed_receipt_exact_retry_one_command_one_terminal_audit')
    async def interrupted_turn(self):
        import sys
        sys.path.insert(0,str(self.repo/'main/xiaozhi-server'))
        from ai_worker.config import Config
        from ai_worker.transport import Manager
        cfg=Config(**self.provider_config(),manager_url=self.base,worker_key=self.worker_key,worker_id='synthetic-fault-worker')
        manager=Manager(cfg)
        first=self.input('synthetic interrupted turn',secrets.token_hex(12));task=await manager.claim()
        if not task or task['input_message_id']!=first['input_message_id']:raise RuntimeError('interrupt fixture claim mismatch')
        second_key=secrets.token_hex(12);second=self.input('synthetic replacement input',second_key)
        if self.session('input',sessionId=self.sid,inputKey=second_key,text='synthetic replacement input')['id']!=second['id']:raise RuntimeError('replacement input replay duplicated turn')
        self.denied_callback(task,'delta',dict(lease_token=task['lease_token'],sequence=1,text='must never publish'))
        self.denied_callback(task,'complete',dict(lease_token=task['lease_token'],last_sequence=0,finish_reason='stop'))
        for turn in(first,second):
            if self.sql("SELECT COUNT(*) FROM ai_run WHERE input_message_id='"+turn['input_message_id']+"'")!='1':raise RuntimeError('turn identity produced multiple runs')
        if self.sql("SELECT LENGTH(output_text) FROM ai_run WHERE id='"+task['run_id']+"'")!='0':raise RuntimeError('interrupted old run published text')
        await manager.close();self.session('abort',sessionId=self.sid)
        self.checks.append('new_input_interrupts_old_run_delta_complete_denied_exact_one_run_per_input')
    async def cycles(self,seconds):
        stop=time.monotonic()+seconds;count=0
        while time.monotonic()<stop:
            self.raw('POST','/internal/esp32/heartbeat',self.fence,gateway=True)
            key=secrets.token_hex(12);turn=self.input('synthetic bounded stability cycle',key)
            replay=self.session('input',sessionId=self.sid,inputKey=key,text='synthetic bounded stability cycle')
            if replay['id']!=turn['id'] or self.sql("SELECT COUNT(*) FROM ai_run WHERE input_message_id='"+turn['input_message_id']+"'")!='1':raise RuntimeError('stability cycle run uniqueness failed')
            self.session('abort',sessionId=self.sid);count+=1
            await asyncio.sleep(min(2,max(0,stop-time.monotonic())))
        self.proof['synthetic_cycle_count']=count;self.proof['synthetic_cycle_seconds']=seconds
        self.checks.append('bounded_synthetic_heartbeat_turn_abort_cycles_unique_runs')

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--repo',type=Path,required=True);p.add_argument('--work',type=Path,required=True);p.add_argument('--compiled-runtime',type=Path,required=True)
    p.add_argument('--mysql-info',type=Path,default=Path('/Users/lengwang/code/esp32/work/b3-native-test.json'));p.add_argument('--mysql-credential-source',type=Path,default=Path('/Users/lengwang/code/esp32/work/run-integrated-20261031.py'));p.add_argument('--provider-config',type=Path);p.add_argument('--cycle-seconds',type=int,default=30)
    args=p.parse_args();args.provider_config=args.provider_config or args.repo/'dev/.runtime/ai-worker.json'
    if not 0<=args.cycle_seconds<=1800:raise SystemExit('cycle-seconds must be 0..1800')
    a=Stability(args);started=time.monotonic()
    try:
        a.start();a.fixture();a.connect();a.competition();a.short_disconnect();asyncio.run(a.natural_expiry());a.lost_receipt_restart();asyncio.run(a.interrupted_turn());asyncio.run(a.cycles(args.cycle_seconds));a.proof['result']='passed'
    except Exception as e:a.proof.update(result='failed',failure_type=type(e).__name__,failure=str(e)if isinstance(e,RuntimeError)else'sanitized acceptance failure')
    finally:
        a.stop();a.proof.update(own_api_redis_processes_stopped=all(p.poll() is not None for p in a.processes),schema_retained=True,elapsed_seconds=round(time.monotonic()-started,2));module.base.private_json(a.folder/'stability-proof.json',a.proof)
        print(json.dumps(dict(result=a.proof['result'],schema=a.schema,proof=str(a.folder/'stability-proof.json'),checks=len(a.checks),retained=True,physical_device=False,provider_calls=0)))
    return 0 if a.proof['result']=='passed'else 1
if __name__=='__main__':raise SystemExit(main())
