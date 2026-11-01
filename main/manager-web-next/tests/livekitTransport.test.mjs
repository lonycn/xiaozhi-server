import {describe,it,expect,vi} from 'vitest';
import {createRoomTransport} from '../src/media/livekitTransport.mjs';
function harness(options={}){
  const listeners=new Map(),local={trackPublications:new Map(),publishTrack:vi.fn(async()=>{}),unpublishTrack:vi.fn(async()=>{})};
  const room={state:'connected',localParticipant:{...local,sid:'self'},remoteParticipants:new Map(),on:vi.fn((name,callback)=>listeners.set(name,callback)),connect:vi.fn(async()=>{}),disconnect:vi.fn(async()=>{}),removeAllListeners:vi.fn(),startAudio:vi.fn(async()=>{})};
  const sdk={Room:class{constructor(){return room;}},RoomEvent:{TrackSubscribed:'track',TrackUnsubscribed:'untrack',ConnectionStateChanged:'state',Disconnected:'disconnected'},createLocalTracks:vi.fn(async()=>[{kind:'audio',stop:vi.fn()}])};
  const onTrack=vi.fn(),onLocalTrack=vi.fn(),onChange=vi.fn(),transport=createRoomTransport(sdk,{onChange,onTrack,onLocalTrack,...options});
  const track=(kind='audio')=>{const element=document.createElement(kind==='audio'?'audio':'video');element.play=vi.fn(async()=>{});return {kind,sid:`TR_${kind}`,isMuted:false,mediaStreamTrack:{readyState:'live'},attach:vi.fn(()=>element),detach:vi.fn(()=>[element]),getRTCStatsReport:vi.fn()};};
  return {room,sdk,listeners,transport,onTrack,onLocalTrack,onChange,track};
}
describe('LiveKit transport device and receive evidence boundary',()=>{
  it('previews only the existing authorized camera track, muted inline, and releases it on camera-off',async()=>{
    const h=harness(),video={...h.track('video'),stop:vi.fn()},host=document.createElement('div');h.onLocalTrack.mockImplementation(element=>host.appendChild(element));h.sdk.createLocalTracks.mockResolvedValue([video]);h.transport.setForeground(true);
    await h.transport.connect('wss://test','private',{microphone:false,camera:true});expect(h.sdk.createLocalTracks).toHaveBeenCalledOnce();expect(h.sdk.createLocalTracks).toHaveBeenCalledWith({audio:false,video:true});expect(h.onTrack).not.toHaveBeenCalled();
    const element=host.querySelector('video');expect(element.muted).toBe(true);expect(element.playsInline).toBe(true);expect(h.onChange).toHaveBeenLastCalledWith(expect.objectContaining({hasLocalVideo:true,hasVideo:false}));
    await h.transport.camera(false);expect(host.querySelector('video')).toBeNull();expect(video.stop).toHaveBeenCalledOnce();expect(h.room.localParticipant.unpublishTrack).toHaveBeenCalledWith(video);await h.transport.close();expect(video.stop).toHaveBeenCalledOnce();
  });
  it('keeps the preparing-generation local preview private until foreground and removes it on disconnect',async()=>{
    const h=harness(),video={...h.track('video'),stop:vi.fn()},host=document.createElement('div');h.onLocalTrack.mockImplementation(element=>host.appendChild(element));h.sdk.createLocalTracks.mockResolvedValue([video]);
    await h.transport.connect('wss://test','private',{microphone:false,camera:true});expect(video.attach).not.toHaveBeenCalled();h.transport.setForeground(true);expect(host.querySelector('video')).not.toBeNull();h.listeners.get('disconnected')();expect(host.querySelector('video')).toBeNull();expect(video.stop).toHaveBeenCalledOnce();expect(h.onChange).toHaveBeenLastCalledWith(expect.objectContaining({hasLocalVideo:false}));await h.transport.close();
  });
  it.each(['disable','disconnect'])('stops a late camera grant after %s without publishing or displaying it',async mode=>{
    const h=harness(),video={...h.track('video'),stop:vi.fn()};let grant;h.sdk.createLocalTracks.mockReturnValue(new Promise(resolve=>{grant=resolve;}));const pending=h.transport.camera(true);
    if(mode==='disable')await h.transport.camera(false);else h.listeners.get('disconnected')();grant([video]);await pending;
    expect(video.stop).toHaveBeenCalledOnce();expect(h.room.localParticipant.publishTrack).not.toHaveBeenCalled();expect(h.onLocalTrack).not.toHaveBeenCalled();await h.transport.close();
  });

  it('only requests sources explicitly chosen by the user and never logs or persists a token',async()=>{
    const h=harness();await h.transport.connect('wss://test','private',{microphone:true,camera:false});expect(h.sdk.createLocalTracks).toHaveBeenCalledTimes(1);expect(h.sdk.createLocalTracks).toHaveBeenCalledWith({audio:true,video:false});await h.transport.close();
  });
  it('does not attach prepared-room media until foreground is committed',async()=>{
    const h=harness(),track=h.track();await h.transport.connect('wss://test','private',{microphone:false,camera:false});h.transport.setForeground(false);h.listeners.get('track')(track);expect(track.attach).not.toHaveBeenCalled();h.transport.setForeground(true);expect(track.attach).toHaveBeenCalledTimes(1);h.transport.setForeground(false);expect(track.detach).toHaveBeenCalledTimes(1);await h.transport.close();
  });
  it('removes its video when the SDK already detached it, across unsubscribe, foreground changes and close',async()=>{
    const host=document.createElement('div'),h=harness();h.onTrack.mockImplementation(element=>host.appendChild(element));
    const makeTrack=()=>{let attached=[];return {kind:'video',sid:'TR_video',isMuted:false,attach:vi.fn(()=>{const element=document.createElement('video');element.play=vi.fn(async()=>{});attached.push(element);return element;}),detach:vi.fn(()=>{const previous=attached;attached=[];return previous;})};};
    await h.transport.connect('wss://test','private',{microphone:false,camera:false});h.transport.setForeground(true);
    const first=makeTrack();h.listeners.get('track')(first);expect(host.querySelectorAll('video')).toHaveLength(1);
    first.detach();h.listeners.get('untrack')(first);expect(host.querySelectorAll('video')).toHaveLength(0);
    const second=makeTrack();h.listeners.get('track')(second);expect(host.querySelectorAll('video')).toHaveLength(1);
    second.detach();h.transport.setForeground(false);expect(host.querySelectorAll('video')).toHaveLength(0);
    h.transport.setForeground(true);expect(host.querySelectorAll('video')).toHaveLength(1);
    second.detach();await h.transport.close();expect(host.querySelectorAll('video')).toHaveLength(0);
    const next=harness();next.onTrack.mockImplementation(element=>host.appendChild(element));next.transport.setForeground(true);next.listeners.get('track')(makeTrack());expect(host.querySelectorAll('video')).toHaveLength(1);await next.transport.close();expect(host.querySelectorAll('video')).toHaveLength(0);
  });
  it('requires a fresh inbound byte increase from the actual peer before reporting receiving audio',async()=>{
    const h=harness(),track=h.track();h.room.remoteParticipants.set('peer',{sid:'peer',trackPublications:new Map([['track',{track}]])});track.getRTCStatsReport.mockResolvedValueOnce(new Map([['x',{type:'inbound-rtp',bytesReceived:100}]])).mockResolvedValueOnce(new Map([['x',{type:'inbound-rtp',bytesReceived:160}]])).mockResolvedValueOnce(new Map([['x',{type:'inbound-rtp',bytesReceived:160}]]));await h.transport.connect('wss://test','private',{microphone:false,camera:false});h.listeners.get('track')(track);
    expect(await h.transport.evidence()).toMatchObject({transport_ready:true,receiving_audio:false,participant_sid:'self',peer_sid:'peer'});expect((await h.transport.evidence()).receiving_audio).toBe(true);expect((await h.transport.evidence()).receiving_audio).toBe(false);await h.transport.close();
  });
  it('stops a track granted after teardown before it can be published',async()=>{
    const h=harness();let grant;const track={stop:vi.fn()};h.sdk.createLocalTracks.mockReturnValue(new Promise(resolve=>{grant=resolve;}));const pending=h.transport.connect('wss://test','private',{microphone:true,camera:false});while(!h.sdk.createLocalTracks.mock.calls.length)await Promise.resolve();await h.transport.close();grant([track]);await expect(pending).rejects.toMatchObject({name:'AbortError'});expect(track.stop).toHaveBeenCalledTimes(1);expect(h.room.localParticipant.publishTrack).not.toHaveBeenCalled();
  });
  it('stops capture synchronously on close and does not mark reconnecting transport ready',async()=>{
    const h=harness(),track={stop:vi.fn()};h.sdk.createLocalTracks.mockResolvedValue([track]);await h.transport.connect('wss://test','private',{microphone:true,camera:false});h.room.state='reconnecting';h.listeners.get('state')();expect((await h.transport.evidence()).transport_ready).toBe(false);const pending=h.transport.close();expect(track.stop).toHaveBeenCalledTimes(1);await pending;expect(h.room.disconnect).toHaveBeenCalledWith(true);
  });
  it('reports six seconds without fresh RTP and clears the diagnostic when packets return',async()=>{
    let time=0,bytes=100;const h=harness({now:()=>time}),track=h.track();
    h.room.remoteParticipants.set('peer',{sid:'peer',trackPublications:new Map([['track',{track}]])});track.getRTCStatsReport.mockImplementation(async()=>new Map([['inbound',{type:'inbound-rtp',bytesReceived:bytes}]]));
    await h.transport.connect('wss://test','private',{microphone:false,camera:false});h.transport.setForeground(true);h.listeners.get('track')(track);await h.transport.evidence();
    time=5999;await h.transport.evidence();expect(h.onChange).not.toHaveBeenCalledWith(expect.objectContaining({mediaStalled:true}));
    time=6000;await h.transport.evidence();expect(h.onChange).toHaveBeenLastCalledWith(expect.objectContaining({connected:true,mediaStalled:true}));
    time=6500;bytes=160;expect((await h.transport.evidence()).receiving_audio).toBe(true);expect(h.onChange).toHaveBeenLastCalledWith(expect.objectContaining({mediaStalled:false}));await h.transport.close();
  });
  it.each(['muted','paused','disabled','unsubscribed','backgroundVideo','hiddenDocument'])('does not label intentional %s as missing RTP and resets the recovery grace period',async mode=>{
    let time=0;const hidden=vi.spyOn(document,'hidden','get').mockReturnValue(false);const h=harness({now:()=>time}),track=h.track(['backgroundVideo','hiddenDocument'].includes(mode)?'video':'audio'),publication={track};
    h.room.remoteParticipants.set('peer',{sid:'peer',trackPublications:new Map([['track',publication]])});track.getRTCStatsReport.mockResolvedValue(new Map([['inbound',{type:'inbound-rtp',bytesReceived:100}]]));
    await h.transport.connect('wss://test','private',{microphone:false,camera:false});h.transport.setForeground(true);h.listeners.get('track')(track);await h.transport.evidence();
    if(mode==='muted')track.isMuted=true;if(mode==='paused')track.streamState='paused';if(mode==='disabled')publication.isEnabled=false;if(mode==='unsubscribed')publication.isSubscribed=false;if(mode==='backgroundVideo')h.transport.setForeground(false);if(mode==='hiddenDocument')hidden.mockReturnValue(true);
    time=20000;await h.transport.evidence();expect(h.onChange).not.toHaveBeenCalledWith(expect.objectContaining({mediaStalled:true}));
    track.isMuted=false;track.streamState='active';publication.isEnabled=true;publication.isSubscribed=true;hidden.mockReturnValue(false);h.transport.setForeground(true);await h.transport.evidence();time+=5999;await h.transport.evidence();expect(h.onChange).not.toHaveBeenCalledWith(expect.objectContaining({mediaStalled:true}));await h.transport.close();hidden.mockRestore();
  });

  it('clears a receive warning for remote mute and treats a counter reset as a fresh grace period',async()=>{
    let time=0,bytes=100;const h=harness({now:()=>time}),track=h.track(),publication={track};h.room.remoteParticipants.set('peer',{sid:'peer',trackPublications:new Map([['track',publication]])});track.getRTCStatsReport.mockImplementation(async()=>new Map([['inbound',{type:'inbound-rtp',bytesReceived:bytes}]]));
    await h.transport.connect('wss://test','private',{microphone:false,camera:false});h.listeners.get('track')(track);await h.transport.evidence();time=6000;await h.transport.evidence();expect(h.onChange).toHaveBeenLastCalledWith(expect.objectContaining({mediaStalled:true}));
    track.isMuted=true;await h.transport.evidence();expect(h.onChange).toHaveBeenLastCalledWith(expect.objectContaining({mediaStalled:false}));track.isMuted=false;await h.transport.evidence();time=12000;await h.transport.evidence();expect(h.onChange).toHaveBeenLastCalledWith(expect.objectContaining({mediaStalled:true}));
    time=12500;bytes=1;expect((await h.transport.evidence()).receiving_audio).toBe(false);expect(h.onChange).toHaveBeenLastCalledWith(expect.objectContaining({mediaStalled:false}));await h.transport.close();
  });

});
