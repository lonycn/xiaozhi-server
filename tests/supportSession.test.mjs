import { flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createSupportSession } from '../src/support/deskSession.mjs';

const page = items => ({items,next_cursor:'0',has_more:false});
let session, server, api, conversations, queues, assignments, ledger, last, visible, notify, sound, storage;
const request = (id='r',service_type='support_agent') => ({request_id:id,conversation_id:'c-'+id,service_type,status:'active',assigned_agent_user_id:'42'});
const message = (seq, sender_type='user', parts=[{type:'text',text:'消息'}]) => ({id:'m-'+seq,seq:String(seq),sender_type,parts});
beforeEach(()=>{
  vi.useFakeTimers();
  server={user_id:'42',role:'support_agent',roles:['support_agent','expert'],enabled:true,accepting:false};
  queues={support_agent:[],expert:[]};assignments={support_agent:[request()],expert:[]};ledger={'c-r':[]};last={'c-r':'9007199254740993'};visible=true;
  api={me:vi.fn(async()=>({...server})),presence:vi.fn(async (accepting,role)=>(server={...server,...(server.service_presence?{service_presence:{...server.service_presence,[role]:{accepting}}}:{}),...(role===server.role?{accepting}:{})})),queue:vi.fn(async(cursor,role)=>page(queues[role])),assigned:vi.fn(async(cursor,role)=>page(assignments[role]))};
  conversations={detail:vi.fn(async id=>({last_seq:last[id]||'0'})),messages:vi.fn(async(id,after,signal,limit)=>{
    const items=(ledger[id]||[]).filter(row=>BigInt(row.seq)>BigInt(after)).slice(0,limit);
    return{items,next_cursor:items.at(-1)?.seq||String(after),has_more:false};
  })};
  notify=vi.fn(()=>vi.fn());sound={available:true,unlock:vi.fn().mockResolvedValue(true),play:vi.fn(),close:vi.fn()};storage=new Map();
  session=createSupportSession({api,conversations,notify,sound,storage:{getItem:key=>storage.get(key),setItem:(key,value)=>storage.set(key,value)},isVisible:()=>visible});
});
afterEach(()=>{session.stop();vi.useRealTimers();});
async function start(){await session.start('account-42');await flushPromises();}
async function incoming(seq,sender='user',parts){ledger['c-r'].push(message(seq,sender,parts));await session.poll();}
describe('global staff availability',()=>{
  it('restores the server preference and keeps it through view changes without writing false on teardown',async()=>{
    server.accepting=true;await start();
    expect(session.state.profile.accepting).toBe(true);
    session.setView('desk',{requestId:'r',conversationId:'c-r',serviceType:'support_agent',isReading:()=>true});session.clearView('desk');
    await vi.advanceTimersByTimeAsync(16000);
    expect(api.presence.mock.calls.length).toBeGreaterThan(0);expect(api.presence.mock.calls.every(call=>call[0]===true)).toBe(true);
    const writes=api.presence.mock.calls.length;session.stop();expect(api.presence).toHaveBeenCalledTimes(writes);expect(vi.getTimerCount()).toBe(0);
  });
  it('does not change a paused preference just by opening the application',async()=>{
    await start();expect(api.presence).not.toHaveBeenCalled();
    await session.setAccepting(true);expect(session.state.profile.accepting).toBe(true);expect((await session.ready()).accepting).toBe(true);
    await session.start('same-account-new-page');await flushPromises();expect(session.state.profile.accepting).toBe(true);
    expect(api.presence.mock.calls.every(call=>call[0]===true)).toBe(true);
  });
  it('serializes a user pause after an in-flight heartbeat so stale renewal cannot turn it back on',async()=>{
    server.accepting=true;await start();
    let finish;api.me.mockImplementationOnce(()=>new Promise(resolve=>finish=resolve));
    const heartbeat=session.heartbeat();await flushPromises();const pause=session.setAccepting(false);
    finish({...server});await Promise.all([heartbeat,pause]);
    expect(server.accepting).toBe(false);expect(session.state.profile.accepting).toBe(false);expect(api.presence.mock.calls.at(-1)[0]).toBe(false);
  });
  it('keeps the last server-confirmed preference when a save fails and reconciles an ambiguous success',async()=>{
    server.accepting=true;await start();
    api.presence.mockRejectedValueOnce(new Error('offline'));
    await expect(session.setAccepting(false)).rejects.toThrow('offline');expect(session.state.profile.accepting).toBe(true);expect(session.state.error).toBe('offline');
    server.accepting=false;api.presence.mockImplementationOnce(async value=>{server={...server,accepting:value};throw new Error('response lost');});
    await expect(session.setAccepting(true)).rejects.toThrow('response lost');expect(session.state.profile.accepting).toBe(true);
  });
  it('reads a preference changed in another tab before sending a heartbeat',async()=>{
    server.accepting=true;await start();api.presence.mockClear();server.accepting=false;
    await session.heartbeat();expect(session.state.profile.accepting).toBe(false);expect(api.presence).not.toHaveBeenCalled();
  });
  it('aborts old account requests and cannot restore an old accepting profile after switching identities',async()=>{
    let finish;api.me.mockImplementationOnce(()=>new Promise(resolve=>finish=resolve));
    const old=session.start('old-account'),signal=api.me.mock.calls[0][0];
    server={...server,user_id:'new-account',accepting:false};await session.start('new-account');await flushPromises();
    finish({...server,user_id:'old-account',accepting:true});await old;
    expect(signal.aborted).toBe(true);expect(session.state.profile.user_id).toBe('new-account');expect(session.state.profile.accepting).toBe(false);
  });
});
describe('new request and customer-message reminders',()=>{
  it('seeds existing queues and message cursors silently without downloading old history',async()=>{
    server.accepting=true;queues.support_agent=[{...request('queued'),status:'queued'}];await start();
    expect(notify).not.toHaveBeenCalled();expect(sound.play).not.toHaveBeenCalled();expect(conversations.messages).toHaveBeenCalledExactlyOnceWith('c-r','9007199254740992',expect.anything(),1);
    expect(session.state.queueCounts.support_agent).toBe(1);expect(session.counts()).toEqual({support_agent:0,expert:0});
  });
  it('counts new incoming messages once, skips outgoing replies, and retains 64-bit cursor precision',async()=>{
    await start();
    await incoming('9007199254740994','support_agent');expect(notify).not.toHaveBeenCalled();
    await incoming('9007199254740995','user',[{type:'image',attachment_id:'incoming-image'}]);
    expect(session.state.unread.r.count).toBe(1);expect(notify).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({kind:'message',request_id:'r',count:1}));
    await session.poll();expect(session.state.unread.r.count).toBe(1);expect(notify).toHaveBeenCalledTimes(1);
    expect(conversations.messages.mock.calls.at(-1)[1]).toBe('9007199254740995');
  });
  it('does not interrupt the focused conversation being read, but alerts while another page or tab is active',async()=>{
    await start();const latest=vi.fn().mockResolvedValue();
    session.setView('desk',{requestId:'r',conversationId:'c-r',serviceType:'support_agent',isReading:()=>true,openLatest:latest});
    await incoming('9007199254740994');expect(notify).not.toHaveBeenCalled();
    visible=false;await incoming('9007199254740995');expect(session.state.unread.r.count).toBe(1);
    visible=true;session.setView('desk',{requestId:'r',conversationId:'c-r',serviceType:'support_agent',isReading:()=>false,openLatest:latest});
    expect(session.reveal('r')).toBe(true);expect(latest).toHaveBeenCalled();
    session.setView('desk',{requestId:'r',conversationId:'c-r',serviceType:'support_agent',isReading:()=>true});expect(session.state.unread.r).toBeUndefined();
    session.clearView('old-desk');await incoming('9007199254740996');expect(notify).toHaveBeenCalledTimes(1);
  });
  it('still alerts for messages in active assignments when new reception is paused',async()=>{
    await start();expect(session.state.profile.accepting).toBe(false);
    await incoming('9007199254740994');expect(notify).toHaveBeenCalledWith(expect.objectContaining({kind:'message'}));
  });
  it('notifies a new waiting request once in each permitted desk and suppresses waiting alerts while paused',async()=>{
    server.service_presence={support_agent:{accepting:true},expert:{accepting:true}};await start();
    queues.expert=[{...request('expert-queue','expert'),status:'queued'}];await session.poll();await session.poll();
    expect(notify).toHaveBeenCalledTimes(1);expect(session.state.newRequests['expert-queue'].kind).toBe('queue');expect(session.counts().expert).toBe(1);
    await session.setAccepting(false,'expert');queues.expert.push({...request('paused-queue','expert'),status:'queued'});await session.poll();expect(notify).toHaveBeenCalledTimes(1);
    session.markRead('expert-queue');expect(session.counts().expert).toBe(0);
  });
  it('announces a newly assigned conversation without replaying that conversation’s old messages',async()=>{
    await start();assignments.expert=[request('new-expert','expert')];last['c-new-expert']='999';
    await session.poll();
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({kind:'assigned',request_id:'new-expert',service_type:'expert'}));
    expect(conversations.messages.mock.calls.filter(call=>call[0]==='c-new-expert').map(call=>[call[1],call[3]])).toEqual([['998',1]]);
  });
  it('does not read another staff member’s assignments and removes unread markers after release',async()=>{
    await start();assignments.expert=[{...request('other','expert'),assigned_agent_user_id:'someone-else'}];
    await incoming('9007199254740994');expect(conversations.detail.mock.calls.every(call=>call[0]!=='c-other')).toBe(true);
    assignments.support_agent=[];await session.poll();expect(session.state.unread).toEqual({});expect(session.state.requests).toEqual([]);
  });
  it('clears badges, notification handles and timers when staff permission is revoked',async()=>{
    await start();await incoming('9007199254740994');const close=notify.mock.results[0].value;
    server=null;api.me.mockResolvedValue(null);await session.heartbeat();
    expect(session.state.profile).toBeNull();expect(session.state.unread).toEqual({});expect(close).toHaveBeenCalled();expect(vi.getTimerCount()).toBe(0);
  });
  it('rejects a broken message cursor instead of repeatedly downloading the same page',async()=>{
    await start();conversations.messages.mockResolvedValue({items:[],next_cursor:'9007199254740993',has_more:true});await session.poll();
    expect(session.state.error).toBe('INVALID_SUPPORT_CURSOR');expect(notify).not.toHaveBeenCalled();
  });
});
describe('notification audio preferences',()=>{
  it('persists the sound setting per account and keeps visual notifications while muted',async()=>{
    storage.set('staff-notification-sound:42','false');await start();
    expect(session.state.soundEnabled).toBe(false);await incoming('9007199254740994');expect(notify).toHaveBeenCalledTimes(1);expect(sound.play).not.toHaveBeenCalled();
    session.setSoundEnabled(true);await session.unlockSound();expect(session.state.soundReady).toBe(true);expect(storage.get('staff-notification-sound:42')).toBe('true');
    await incoming('9007199254740995');expect(sound.play).toHaveBeenCalledTimes(1);
  });
  it('ignores an old audio unlock after logout',async()=>{
    let finish;sound.unlock.mockReturnValue(new Promise(resolve=>finish=resolve));await start();const unlocking=session.unlockSound();
    session.stop();finish(true);await unlocking;expect(session.state.soundReady).toBe(false);
  });
});

it('coordinates presence changes from two tabs through one account lock',async()=>{
 let tail=Promise.resolve();
 const locks={request:vi.fn((name,options,callback)=>{const job=tail.catch(()=>{}).then(()=>{if(options.signal.aborted)throw new DOMException('Aborted','AbortError');return callback();});tail=job;return job;})};
 session.stop();session=createSupportSession({api,conversations,locks});
 const other=createSupportSession({api,conversations,locks});
 try{
  server.accepting=true;await session.start('first-tab');await other.start('second-tab');await flushPromises();
  let finish;api.me.mockImplementationOnce(()=>new Promise(resolve=>finish=resolve));
  const heartbeat=other.heartbeat();await flushPromises();const pause=session.setAccepting(false);
  finish({...server});await Promise.all([heartbeat,pause]);await other.heartbeat();
  expect(server.accepting).toBe(false);expect(other.state.profile.accepting).toBe(false);
  expect(locks.request.mock.calls.every(call=>call[0]==='staff-presence:42')).toBe(true);
 }finally{other.stop();}
});

 describe('independent business availability',()=>{
  it('toggles each desk independently and renews only the enabled business',async()=>{
    server.service_presence={support_agent:{accepting:false},expert:{accepting:false}};
    await start();await session.setAccepting(true,'expert');
    expect(session.state.profile.service_presence).toEqual({support_agent:{accepting:false},expert:{accepting:true}});
    api.presence.mockClear();await session.heartbeat();
    expect(api.presence.mock.calls.map(call=>[call[0],call[1]])).toEqual([[true,'expert']]);
    await session.setAccepting(true,'support_agent');await session.setAccepting(false,'expert');
    expect(session.state.profile.service_presence).toEqual({support_agent:{accepting:true},expert:{accepting:false}});
    api.presence.mockClear();await session.heartbeat();
    expect(api.presence.mock.calls.map(call=>[call[0],call[1]])).toEqual([[true,'support_agent']]);
  });
  it('does not enable or notify the secondary business from a legacy primary-role response',async()=>{
    server.accepting=true;await start();api.presence.mockClear();await session.heartbeat();
    expect(api.presence.mock.calls.map(call=>call[1])).toEqual(['support_agent']);
    queues.expert=[{...request('off-expert','expert'),status:'queued'}];
    queues.support_agent=[{...request('on-support'),status:'queued'}];await session.poll();
    expect(notify).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({service_type:'support_agent',kind:'queue'}));
  });
  it('reads another tab’s independent pause before renewing either desk',async()=>{
    server.service_presence={support_agent:{accepting:true},expert:{accepting:true}};await start();
    server.service_presence={support_agent:{accepting:true},expert:{accepting:false}};api.presence.mockClear();
    await session.heartbeat();expect(api.presence.mock.calls.map(call=>call[1])).toEqual(['support_agent']);
    expect(session.state.profile.service_presence.expert.accepting).toBe(false);
  });
 });
describe('latest message activity and saved unread state',()=>{
 it('updates previews for image and text arrivals, coalesces their toast and persists unread cursors across reload',async()=>{
  await start();await incoming('9007199254740994','user',[{type:'image',attachment_id:'private'}]);
  const close=notify.mock.results[0].value;expect(session.state.activity.r.preview.type).toBe('image');
  await incoming('9007199254740995','user',[{type:'text',text:'新消息内容'}]);expect(close).toHaveBeenCalled();expect(session.state.recent).toHaveLength(1);
  expect(notify).toHaveBeenLastCalledWith(expect.objectContaining({preview:{type:'text',text:'新消息内容'},unread_count:2,last_seq:'9007199254740995'}));
  last['c-r']='9007199254740995';session.stop();notify.mockClear();await start();
  expect(session.state.unread.r.count).toBe(2);expect(notify).not.toHaveBeenCalled();
  session.markRead('r');session.stop();await start();expect(session.state.unread.r).toBeUndefined();
 });
 it('updates the latest activity while reading without beeping or displaying another toast',async()=>{
  await start();session.setView('desk',{requestId:'r',conversationId:'c-r',serviceType:'support_agent',isReading:()=>true});
  await incoming('9007199254740994','user',[{type:'file',attachment_id:'file'}]);
  expect(session.state.activity.r.preview.type).toBe('file');expect(notify).not.toHaveBeenCalled();expect(sound.play).not.toHaveBeenCalled();
 });
});
describe('global incoming calls',()=>{
 let media,invites;
 beforeEach(()=>{
  session.stop();invites=[];media={invitations:vi.fn(async()=>page(invites))};sound.startRinging=vi.fn(()=>true);sound.stopRinging=vi.fn();
  session=createSupportSession({api,conversations,media,notify,sound,storage:{getItem:key=>storage.get(key),setItem:(key,value)=>storage.set(key,value)}});
 });
 const call=(extra={})=>({id:'77',type:'transfer',session_id:'99',conversation_id:'c-r',request_id:'r',service_type:'expert',mode:'video',to_user_id:'42',status:'invited',requires_response:true,expires_at:new Date(Date.now()+60000).toISOString(),...extra});
 it('rings for a pending video call even when reception is paused, and stops after acceptance',async()=>{
  invites=[call()];await start();expect(notify).toHaveBeenCalledWith(expect.objectContaining({kind:'call',service_type:'expert',mode:'video'}));
  expect(sound.startRinging).not.toHaveBeenCalled();await session.unlockSound();expect(sound.startRinging).toHaveBeenCalledOnce();
  await session.pollCalls();expect(notify).toHaveBeenCalledTimes(1);expect(session.counts().expert).toBe(1);
  invites=[call({requires_response:false})];await session.pollCalls();expect(session.state.calls).toEqual([]);expect(sound.stopRinging).toHaveBeenCalled();expect(session.counts().expert).toBe(0);
 });
 it('supports silencing one call and rings for another; logout closes persistent notices',async()=>{
  invites=[call()];await start();await session.unlockSound();session.silenceCall('transfer:77');expect(sound.stopRinging).toHaveBeenCalled();
  invites.push(call({id:'78',session_id:'100'}));await session.pollCalls();await flushPromises();expect(session.state.calls).toHaveLength(2);
  session.stop();expect(notify.mock.results.every(result=>result.value.mock.calls.length>0)).toBe(true);expect(vi.getTimerCount()).toBe(0);
 });
 it('never announces another recipient, another role, accepted calls or expired invitations',async()=>{
  invites=[call({to_user_id:'another'}),call({service_type:'unknown'}),call({requires_response:false}),call({expires_at:new Date(Date.now()-1).toISOString()})];
  await start();expect(notify).not.toHaveBeenCalled();expect(session.state.calls).toEqual([]);
 });
 it('stops expired ringing even when the next invitation read fails',async()=>{
  invites=[call({expires_at:new Date(Date.now()+1000).toISOString()})];await start();await session.unlockSound();media.invitations.mockRejectedValue(new Error('offline'));
  await vi.advanceTimersByTimeAsync(2500);await flushPromises();expect(session.state.calls).toEqual([]);expect(sound.stopRinging).toHaveBeenCalled();
 });
});
