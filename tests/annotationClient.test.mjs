import { afterEach, describe, expect, it, vi } from 'vitest';
import { createAnnotationClient, privateAnnotationBlob, decodeAnnotationBlob } from '../src/apis/module/annotationClient.mjs';
const response = (data,status=200) => ({ ok:status===200,status,json:async () => ({ code:status===200?0:status,data,msg:'PUBLIC_CODE' }) });
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });
describe('annotation API and private background transport', () => {
  it('uses frozen JSON DTOs, string IDs, stable request bodies and encoded list cursors', async () => {
    const fetchImpl=vi.fn().mockResolvedValue(response({ id:'9223372036854775806' })); const client=createAnnotationClient({ baseUrl:'/xiaozhi',getToken:()=>'long-lived',fetchImpl });
    const body={conversation_id:'c',client_frame_id:'stable',source_attachment_id:'9',time_ms:1250}; await client.createFrame(body); await client.createFrame(body); await client.frame('9223372036854775806'); await client.list('c','9223372036854775805');
    expect(fetchImpl.mock.calls[0][1].body).toBe(JSON.stringify(body)); expect(fetchImpl.mock.calls[1][1].body).toBe(fetchImpl.mock.calls[0][1].body); expect(fetchImpl.mock.calls[2][0]).toContain('/frames/9223372036854775806'); expect(fetchImpl.mock.calls[3][0]).toContain('after=9223372036854775805'); expect(fetchImpl.mock.calls[0][1].headers.Authorization).toBe('Bearer long-lived');
  });
  it('aborts API requests and surfaces denied/disabled statuses instead of reporting success', async () => {
    const fetchImpl=vi.fn().mockResolvedValue(response(null,404)),client=createAnnotationClient({ baseUrl:'/xiaozhi',getToken:()=>'',fetchImpl }); await expect(client.get('9')).rejects.toMatchObject({status:404});
    fetchImpl.mockImplementation((_url,{signal}) => new Promise((_resolve,reject) => signal.addEventListener('abort',() => reject(new DOMException('Aborted','AbortError')))));
    const controller=new AbortController(),pending=client.frame('9',controller.signal); controller.abort(); await expect(pending).rejects.toMatchObject({name:'AbortError'});
  });
  it('loads content with a short ticket and no Bearer/cookies, renewing once if a ticket expires', async () => {
    const client={access:vi.fn().mockResolvedValueOnce({url:'https://api.test/content?ticket=expired'}).mockResolvedValueOnce({url:'https://api.test/content?ticket=fresh'})};
    const fetchImpl=vi.fn().mockResolvedValueOnce({status:403,ok:false}).mockResolvedValueOnce(new Response(new Uint8Array([1,2,3]),{headers:{'Content-Type':'image/jpeg'}}));
    const blob=await privateAnnotationBlob(client,'9',new AbortController().signal,fetchImpl); expect(blob.size).toBe(3); expect(client.access).toHaveBeenCalledTimes(2);
    expect(fetchImpl.mock.calls[0][1]).toMatchObject({credentials:'omit',redirect:'error',referrerPolicy:'no-referrer'}); expect(fetchImpl.mock.calls[0][1].headers.Authorization).toBeUndefined(); expect(fetchImpl.mock.calls[1][0]).toContain('ticket=fresh');
  });
  it('does not loop on revocation and rejects active content or oversized backgrounds', async () => {
    const client={access:vi.fn().mockResolvedValue({url:'ticket'})}; const denied=vi.fn().mockResolvedValue({status:403,ok:false}); await expect(privateAnnotationBlob(client,'9',undefined,denied)).rejects.toMatchObject({status:403}); expect(denied).toHaveBeenCalledTimes(2);
    for (const headers of [{'Content-Type':'text/html'},{'Content-Type':'image/jpeg','Content-Length':String(21*1024*1024)}]) await expect(privateAnnotationBlob(client,'9',undefined,async () => new Response('bad',{headers}))).rejects.toThrow('INVALID_BACKGROUND');
  });
  it('bounds streamed response bytes even without Content-Length', async () => {
    const client={access:vi.fn().mockResolvedValue({url:'ticket'})}; let canceled=false;
    const stream=new ReadableStream({start(controller){controller.enqueue(new Uint8Array(21*1024*1024));},cancel(){canceled=true;}});
    await expect(privateAnnotationBlob(client,'9',undefined,async () => new Response(stream,{headers:{'Content-Type':'image/jpeg'}}))).rejects.toThrow('INVALID_BACKGROUND'); expect(canceled).toBe(true);
  });
  it('releases blob object URLs on decoding errors and cancellation', async () => {
    const created=vi.fn().mockReturnValue('blob:private'),revoked=vi.fn(); vi.stubGlobal('URL',class {static createObjectURL=created;static revokeObjectURL=revoked;}); let image;
    vi.stubGlobal('Image',class {constructor(){image=this;}});
    const controller=new AbortController(),pending=decodeAnnotationBlob(new Blob(['x']),controller.signal); expect(image.src).toBe('blob:private'); controller.abort(); await expect(pending).rejects.toMatchObject({name:'AbortError'}); expect(revoked).toHaveBeenCalledWith('blob:private'); expect(image.src).toBe('');
  });
});
