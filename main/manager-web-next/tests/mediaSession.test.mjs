import { describe, it, expect, vi } from 'vitest';
import { createMediaSession } from '../src/media/mediaSession.mjs';
const member = (user_id,role,generation=1,consent={microphone:true,camera:false}) => ({user_id,role,room_generation:generation,status:'accepted',consent});
const session = (extras={}) => ({id:'7',conversation_id:'c',request_id:'r',status:'connecting',control_epoch:2,current_generation:1,preparing_generation:null,agent_user_id:'agent',participants:[member('customer','customer'),member('agent','agent')],...extras});
function harness(initial=session(), user='customer') {
  let next=initial;const transports=[];
  const client={get:vi.fn(async()=>next),token:vi.fn(async(id,body)=>({server_url:'wss://media.test',participant_token:'secret-in-memory',session_id:id,control_epoch:next.control_epoch,room_generation:body.room_generation,publish_sources:['microphone','camera']})),ready:vi.fn(async()=>next),accept:vi.fn(async()=>next),create:vi.fn(async()=>next),end:vi.fn(async()=>next),transferAction:vi.fn(async()=>next),transfer:vi.fn(async()=>next)};
  const factory=vi.fn(async options=>{const room={connect:vi.fn(async()=>options.onChange({connected:true,state:'connected'})),close:vi.fn(async()=>{}),setForeground:vi.fn(),evidence:vi.fn(async()=>({participant_sid:'PA_self',peer_sid:'PA_peer',transport_ready:true,receiving_audio:true,receiving_video:false})),microphone:vi.fn(async()=>{}),camera:vi.fn(async()=>{}),resumeAudio:vi.fn(async()=>{}),videoTrack:vi.fn()};transports.push(room);return room;});
  const owner=createMediaSession({client,userId:()=>user,transportFactory:factory});
  return {owner,client,transports,factory,setNext(value){next=value;}};
}
describe('B5 media lifecycle and transfer fencing',()=>{
  it('keeps a reload disconnected until explicit user reconnection and only requests agreed sources',async()=>{
    const h=harness();await h.owner.load(session());await h.owner.refresh();expect(h.client.token).not.toHaveBeenCalled();expect(h.factory).not.toHaveBeenCalled();
    await h.owner.reconnect({microphone:true,camera:true});expect(h.transports[0].connect).toHaveBeenCalledWith('wss://media.test','secret-in-memory',{microphone:true,camera:false});expect(h.owner.snapshot().session.status).toBe('connecting');h.owner.dispose();
  });
  it('does not request a token or capture devices while the recipient has not accepted',async()=>{
    const s=session({status:'invited'}),h=harness(s);await h.owner.create('r',{microphone:true,camera:false},'stable-id');await h.owner.refresh();expect(h.client.token).not.toHaveBeenCalled();expect(h.owner.snapshot().joined).toBe(true);h.owner.dispose();
  });
  it('passes explicit acceptance consent and uses the returned/current epoch',async()=>{
    const h=harness();await h.owner.load(session({status:'invited',control_epoch:1}));await h.owner.accept({microphone:true,camera:false,allow_snapshots:false});expect(h.client.accept).toHaveBeenCalledWith('7',{expected_epoch:1,consent:{microphone:true,camera:false,allow_snapshots:false}},expect.any(AbortSignal));expect(h.client.token.mock.calls[0][1]).toEqual({expected_epoch:2,room_generation:1});h.owner.dispose();
  });
  it('prepares two rooms without remote playback from the new room and closes old media before switching output',async()=>{
    const h=harness();await h.owner.load(session());await h.owner.reconnect({microphone:true,camera:false});const old=h.transports[0];
    const transfer={id:'t',status:'preparing',room_generation:2,customer_approved:true,target_accepted:true,target_agent_user_id:'target'};
    h.setNext(session({control_epoch:3,preparing_generation:2,pending_transfer:transfer,participants:[...session().participants,member('customer','customer',2),member('target','agent',2)]}));await h.owner.refresh();const prepared=h.transports[1];expect(prepared.setForeground).toHaveBeenCalledWith(false);expect(old.close).not.toHaveBeenCalled();expect(h.client.ready.mock.calls.some(call=>call[1].room_generation===2&&call[1].receiving_audio)).toBe(true);
    h.setNext(session({control_epoch:4,current_generation:2,agent_user_id:'target',pending_transfer:{...transfer,status:'completed'},participants:[member('customer','customer',2),member('target','agent',2)]}));await h.owner.refresh();expect(old.close).toHaveBeenCalledTimes(1);expect(prepared.setForeground).toHaveBeenLastCalledWith(true);expect(old.close.mock.invocationCallOrder[0]).toBeLessThan(prepared.setForeground.mock.invocationCallOrder.at(-1));h.owner.dispose();
  });
  it('cancels preparation and preserves the original connected room on failed handoff',async()=>{
    const h=harness();await h.owner.load(session());await h.owner.reconnect({microphone:true,camera:false});const old=h.transports[0],transfer={id:'t',status:'preparing',room_generation:2,customer_approved:true,target_accepted:true};
    h.setNext(session({preparing_generation:2,pending_transfer:transfer,participants:[...session().participants,member('customer','customer',2)]}));await h.owner.refresh();const prepared=h.transports[1];h.setNext(session({pending_transfer:{...transfer,status:'failed'}}));await h.owner.refresh();expect(prepared.close).toHaveBeenCalledTimes(1);expect(old.close).not.toHaveBeenCalled();expect(old.setForeground).toHaveBeenLastCalledWith(true);h.owner.dispose();
  });
  it('never asks for a new-room token as the departing source agent',async()=>{
    const h=harness(session(),'agent');await h.owner.load(session());await h.owner.reconnect({microphone:true,camera:false});
    h.setNext(session({preparing_generation:2,pending_transfer:{status:'preparing',room_generation:2,customer_approved:true,target_accepted:true},participants:[...session().participants,member('customer','customer',2),member('target','agent',2)]}));await h.owner.refresh();expect(h.client.token.mock.calls.map(call=>call[1].room_generation)).toEqual([1]);h.owner.dispose();
  });
  it('closes local tracks immediately when hangup fails and does not resolve support or resume AI',async()=>{
    const h=harness();await h.owner.load(session());await h.owner.reconnect({microphone:true,camera:false});h.client.end.mockRejectedValue(new Error('network'));await h.owner.end();expect(h.transports[0].close).toHaveBeenCalledTimes(1);expect(h.owner.snapshot()).toMatchObject({joined:false,error:'media.failed'});expect(h.client.end.mock.calls[0][1]).toEqual({expected_epoch:2,reason:'user_hangup'});await h.owner.refresh();expect(h.client.token).toHaveBeenCalledTimes(1);h.owner.dispose();
  });
  it('closes and fences a room whose connection finishes after identity disposal',async()=>{
    const h=harness();let finish;h.factory.mockImplementationOnce(async()=>{const room={connect:vi.fn(()=>new Promise(resolve=>{finish=resolve;})),close:vi.fn(async()=>{}),setForeground:vi.fn()};h.transports.push(room);return room;});await h.owner.load(session());const work=h.owner.reconnect({microphone:true,camera:false});while(!finish)await Promise.resolve();h.owner.dispose();finish();await work;expect(h.transports[0].close).toHaveBeenCalled();expect(h.client.ready).not.toHaveBeenCalled();
  });
  it('rejects stale generation tokens and closes capture on a server authorization denial',async()=>{
    const h=harness();await h.owner.load(session());h.client.token.mockResolvedValueOnce({session_id:'7',room_generation:99,control_epoch:2,participant_token:'secret',server_url:'wss://media.test',publish_sources:['microphone']});await h.owner.reconnect({microphone:true,camera:false});expect(h.factory).not.toHaveBeenCalled();await h.owner.refresh();expect(h.transports).toHaveLength(1);h.client.get.mockRejectedValueOnce(Object.assign(new Error('revoked'),{status:403}));await h.owner.refresh();expect(h.transports[0].close).toHaveBeenCalledTimes(1);expect(h.owner.snapshot()).toMatchObject({joined:false,error:'media.denied'});h.owner.dispose();
  });
  it('offers a fresh connection after a fatal disconnect instead of keeping a stale room object',async()=>{
    const h=harness();await h.owner.load(session());await h.owner.reconnect({microphone:true,camera:false});const old=h.transports[0];await h.owner.reconnect({microphone:true,camera:false});expect(old.close).toHaveBeenCalledTimes(1);expect(h.transports).toHaveLength(2);expect(h.transports[1].connect).toHaveBeenCalledTimes(1);h.owner.dispose();
  });
  it('never enables a camera which has no stored consent',async()=>{
    const h=harness();await h.owner.load(session());await h.owner.reconnect({microphone:true,camera:false});await h.owner.toggle('camera');expect(h.transports[0].camera).not.toHaveBeenCalled();expect(h.owner.snapshot().camera).toBe(false);h.owner.dispose();
  });
});
