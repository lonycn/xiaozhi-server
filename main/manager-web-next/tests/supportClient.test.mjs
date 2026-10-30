import { describe, expect, it, vi } from 'vitest';
import { createSupportClient } from '../src/apis/module/supportClient.mjs';
import { supportErrorKey } from '../src/utils/supportErrors.mjs';
import { allPages, isSupportAdmin } from '../src/utils/support.mjs';
describe('support API and precision', () => {
  it('preserves long cursors and IDs', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ code: 0, data: {} }) });
    const client = createSupportClient({ baseUrl: '/xiaozhi/', getToken: () => 'token', onUnauthorized: vi.fn(), fetchImpl });
    await client.agents('9223372036854775806'); expect(fetchImpl.mock.calls[0][0]).toContain('before=9223372036854775806');
    await client.profile('9223372036854775806', { enabled: true, role: 'expert', capacity_text: 20 }); expect(fetchImpl.mock.calls[1][0]).toContain('/agents/9223372036854775806'); expect(fetchImpl.mock.calls[1][1].headers.Authorization).toBe('Bearer token');
  });
  it('propagates auth and permission failures', async () => {
    const unauthorized = vi.fn(); const client = createSupportClient({ baseUrl: '', getToken: () => '', onUnauthorized: unauthorized, fetchImpl: vi.fn().mockResolvedValue({ ok: false, status: 401, json: async () => ({ code: 401, msg: 'expired' }) }) });
    await expect(client.me()).rejects.toMatchObject({ status: 401 }); expect(unauthorized).toHaveBeenCalledOnce();
  });
  it('rejects unsafe or nonadvancing ledger cursors', async () => {
    await expect(allPages(async () => ({ items: [], next_cursor: '0', has_more: true }))).rejects.toThrow('游标异常');
    await expect(allPages(async () => ({ items: [], next_cursor: 9223372036854775807, has_more: true }))).rejects.toThrow('游标异常');
  });
  it('does not treat string zero as an administrator', () => { expect(isSupportAdmin({ superAdmin: '0' })).toBe(false); expect(isSupportAdmin({ superAdmin: '1' })).toBe(true); });
  it('uses scoped assistance endpoints with precise IDs and abort signals', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ok:true,status:200,json:async()=>({code:0,data:{}})});
    const client = createSupportClient({baseUrl:'',getToken:()=>'',onUnauthorized:vi.fn(),fetchImpl}); const signal = new AbortController().signal;
    await client.summaryAssistance('c/1',signal); await client.createSummaryAssistance('c/1',{client_request_id:'id',base_version:'1'},signal);
    await client.summaryAssistanceJob('c/1','job/1',signal); await client.retrySummaryAssistance('c/1','job/1',signal);
    expect(fetchImpl.mock.calls.map(call=>[call[0],call[1].method])).toEqual([
      ['/api/v1/support/conversations/c%2F1/summary/assistance','GET'], ['/api/v1/support/conversations/c%2F1/summary/assistance','POST'],
      ['/api/v1/support/conversations/c%2F1/summary/assistance/job%2F1','GET'], ['/api/v1/support/conversations/c%2F1/summary/assistance/job%2F1/retry','POST']]);
    expect(JSON.parse(fetchImpl.mock.calls[1][1].body)).toEqual({client_request_id:'id',base_version:'1'}); expect(JSON.parse(fetchImpl.mock.calls[3][1].body)).toEqual({});
  });
  it('maps capacity, changed state and unknown errors while preserving their codes', async () => {
    const client = createSupportClient({ baseUrl: '', getToken: () => '', onUnauthorized: vi.fn(), errorMessage: supportErrorKey,
      fetchImpl: vi.fn().mockResolvedValue({ ok: false, status: 409, json: async () => ({ code: 409, msg: 'AGENT_AT_CAPACITY' }) }) });
    await expect(client.action('r', 'claim')).rejects.toMatchObject({ message: 'support.errorCapacity', code: 'AGENT_AT_CAPACITY', status: 409 });
    expect(supportErrorKey('REQUEST_NOT_QUEUED', 409)).toBe('support.errorChanged'); expect(supportErrorKey('NEW_INTERNAL_ERROR', 500)).toBe('support.errorGeneric');
  });

});
