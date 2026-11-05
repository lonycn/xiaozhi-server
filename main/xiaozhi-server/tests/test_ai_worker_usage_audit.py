"""Opt-in finalized provider fee reports cannot publish output or be canceled with IO."""
import asyncio
import copy
import json
import unittest
import httpx

from ai_worker.transport import Manager, ControlError
from ai_worker.worker import Worker
from test_ai_worker import config, task, chunk, FakeStream, FakeProvider
from test_ai_worker_tools import ToolManager, RoundProvider, tool_task, call_stream, usage


class AuditManager(ToolManager):
    def __init__(self):
        super().__init__()
        self.reports=[]
        self.audit_entered=asyncio.Event()
        self.audit_release=asyncio.Event();self.audit_release.set()
        self.audit_canceled=False
        self.audit_failure=False

    async def usage(self,current,records):
        self.reports.append(copy.deepcopy(records));self.audit_entered.set()
        try:await self.audit_release.wait()
        except asyncio.CancelledError:self.audit_canceled=True;raise
        if self.audit_failure:raise ControlError()
        return {'recorded':True}


class UsageAuditTests(unittest.IsolatedAsyncioTestCase):
    async def test_plain_opt_in_reports_one_finalized_actual_round(self):
        manager=AuditManager();provider=FakeProvider(FakeStream([chunk('answer'),chunk(finish='stop',usage=usage())]))
        worker=Worker(config(),manager,provider)
        await worker.execute(task(usage_audit=True))
        self.assertEqual(manager.reports,[[dict(round=1,provider_request_id='request-1',usage=dict(input_tokens=2,output_tokens=3,total_tokens=5),finish_reason='stop')]])
        self.assertEqual(manager.events[-1][0],'complete')

    async def test_legacy_task_makes_no_new_audit_callback(self):
        manager=AuditManager();provider=FakeProvider(FakeStream([chunk(finish='stop')]))
        await Worker(config(),manager,provider).execute(task())
        self.assertFalse(manager.reports)
        self.assertNotIn('provider_requests',manager.events[-1][1])

    async def test_tool_prefix_extends_only_after_each_finalized_request(self):
        manager=AuditManager();provider=RoundProvider(call_stream(),FakeStream([chunk('answer'),chunk(finish='stop',usage=usage(3,4))]))
        await Worker(config(),manager,provider).execute(tool_task(usage_audit=True))
        self.assertEqual([len(report) for report in manager.reports],[1,2])
        self.assertEqual(manager.reports[0][0],manager.reports[1][0])
        self.assertEqual(manager.reports[1][1]['usage']['total_tokens'],7)

    async def test_cancel_in_tool_wait_has_already_preserved_actual_first_request(self):
        manager=AuditManager();manager.block=True;provider=RoundProvider(call_stream())
        worker=Worker(config(),manager,provider);running=asyncio.create_task(worker.execute(tool_task(usage_audit=True)))
        await asyncio.wait_for(manager.entered.wait(),1)
        self.assertEqual(manager.reports[0][0]['usage']['total_tokens'],5)
        running.cancel()
        with self.assertRaises(asyncio.CancelledError):await running
        self.assertFalse(any(action=='complete' for action,_ in manager.events))

    async def test_cancel_during_provider_io_finalizes_unknown_without_filling_zero(self):
        manager=AuditManager();provider=FakeProvider(FakeStream([chunk('partial')],block=True))
        worker=Worker(config(),manager,provider);running=asyncio.create_task(worker.execute(task(usage_audit=True)))
        for _ in range(100):
            if manager.events:break
            await asyncio.sleep(.001)
        running.cancel()
        with self.assertRaises(asyncio.CancelledError):await running
        self.assertEqual(manager.reports[0][0]['usage'],None)
        self.assertEqual(manager.reports[0][0]['finish_reason'],None)
        self.assertTrue(provider.output.closed)

    async def test_cancel_during_audit_does_not_cancel_fee_only_transport(self):
        manager=AuditManager();manager.audit_release.clear();provider=FakeProvider(FakeStream([chunk(finish='stop',usage=usage())]))
        worker=Worker(config(),manager,provider);running=asyncio.create_task(worker.execute(task(usage_audit=True)))
        await asyncio.wait_for(manager.audit_entered.wait(),1);running.cancel()
        with self.assertRaises(asyncio.CancelledError):await running
        self.assertFalse(manager.audit_canceled)
        self.assertFalse(any(action=='complete' for action,_ in manager.events))
        self.assertEqual(len(worker.audit_jobs),1)
        manager.audit_release.set();await asyncio.gather(*worker.audit_jobs)
        self.assertFalse(manager.audit_canceled)

    async def test_failed_audit_stops_before_tool_execution_or_more_provider_cost(self):
        manager=AuditManager();manager.audit_failure=True
        provider=RoundProvider(call_stream(),FakeStream([chunk(finish='stop')]))
        await Worker(config(),manager,provider).execute(tool_task(usage_audit=True))
        self.assertEqual(len(provider.inputs),1)
        self.assertFalse(manager.batches)
        self.assertEqual(manager.events[-1][0],'fail')
        self.assertEqual(manager.events[-1][1]['error_code'],'PROVIDER_UNAVAILABLE')

    async def test_invalid_input_does_not_report_an_unstarted_request(self):
        manager=AuditManager();provider=FakeProvider()
        await Worker(config(),manager,provider).execute(task(usage_audit=True,messages=None))
        self.assertFalse(manager.reports)

    async def test_audit_transport_uses_immutable_attempt_and_no_state_or_text_fields(self):
        calls=[]
        async def handle(request):
            calls.append(request)
            return httpx.Response(200,json={'code':0,'data':{'recorded':True}})
        client=httpx.AsyncClient(base_url='http://127.0.0.1/xiaozhi/',transport=httpx.MockTransport(handle))
        manager=Manager(config(),client)
        current=task(run_id='a'*32,attempt=2,lease_token='b'*64,usage_audit=True)
        records=[dict(round=1,provider_request_id=None,usage=None,finish_reason=None)]
        try:
            await manager.usage(current,records)
            body=json.loads(calls[0].content)
            self.assertEqual(set(body),{'lease_token','attempt','provider_requests'})
            self.assertIs(type(body['attempt']),int)
            self.assertEqual(calls[0].url.path,'/xiaozhi/internal/ai-worker/runs/'+'a'*32+'/usage')
            with self.assertRaises(ControlError):await manager.usage(current|{'run_id':'https://arbitrary'},records)
            with self.assertRaises(ControlError):await manager.usage(current|{'attempt':'2'},records)
            self.assertEqual(len(calls),1)
        finally:await manager.close()
