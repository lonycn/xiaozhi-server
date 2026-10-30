import {describe,it,expect,vi} from 'vitest';
import {createMediaClient} from '../src/apis/module/mediaClient.mjs';
const response=(data,status=200)=>({ok:status===200,status,json:async()=>({code:status===200?0:status,data,msg:'ROOM_PREPARING'})});
describe('B5 public HTTP contracts',()=>{
 it('uses exact frozen paths and epoch/generation fences without room/identity/grants input',async()=>{
  const fetchImpl=vi.fn(async()=>response({id:'9223372036854775806'})),client=createMediaClient({baseUrl:'/xiaozhi',getToken:()=>'bearer',fetchImpl});
  await client.create({request_id:'r',client_session_id:'stable',mode:'audio',consent:{microphone:true,camera:false}});await client.token('7',{expected_epoch:2,room_generation:3});await client.ready('7',{expected_epoch:2,room_generation:3,participant_sid:'p',peer_sid:'q',transport_ready:true,receiving_audio:true,receiving_video:false});await client.transferAction('8','approve',{expected_epoch:2,consent:{microphone:true,camera:false}});await client.experts('9223372036854775806');await client.capture('7',{expected_epoch:2,room_generation:3,client_frame_id:'stable',source_attachment_id:'9',captured_at:'2026-10-30T00:00:00Z'});
  expect(fetchImpl.mock.calls[1][0]).toBe('/xiaozhi/api/v1/support/media-sessions/7/token');expect(JSON.parse(fetchImpl.mock.calls[1][1].body)).toEqual({expected_epoch:2,room_generation:3});expect(fetchImpl.mock.calls[3][0]).toContain('/media-transfers/8/approve');expect(fetchImpl.mock.calls[4][0]).toContain('after=9223372036854775806');expect(fetchImpl.mock.calls[5][0]).toContain('/media-sessions/7/frames');expect(fetchImpl.mock.calls[0][1].headers.Authorization).toBe('Bearer bearer');
 });
 it('surfaces service failure and cancels pending API I/O on identity changes',async()=>{
  const fetchImpl=vi.fn(async()=>response(null,409)),client=createMediaClient({baseUrl:'/xiaozhi',getToken:()=>'',fetchImpl});await expect(client.token('7',{expected_epoch:1,room_generation:1})).rejects.toMatchObject({status:409,code:'ROOM_PREPARING'});
  fetchImpl.mockImplementation((_url,{signal})=>new Promise((_resolve,reject)=>signal.addEventListener('abort',()=>reject(new DOMException('Aborted','AbortError')))));const controller=new AbortController(),pending=client.current('c',controller.signal);controller.abort();await expect(pending).rejects.toMatchObject({name:'AbortError'});
 });
 it('clears bearer authentication on a 401 without exposing raw server content',async()=>{const clear=vi.fn(),client=createMediaClient({baseUrl:'/x',getToken:()=>'',fetchImpl:async()=>response(null,401),onUnauthorized:clear});await expect(client.invitations()).rejects.toMatchObject({status:401,message:'MEDIA_REQUEST_FAILED'});expect(clear).toHaveBeenCalledTimes(1);});
});
