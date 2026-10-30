import {describe,it,expect,vi} from 'vitest';
import {createRoomTransport} from '../src/media/livekitTransport.mjs';
function harness(){
  const listeners=new Map(),local={trackPublications:new Map(),publishTrack:vi.fn(async()=>{}),unpublishTrack:vi.fn(async()=>{})};
  const room={state:'connected',localParticipant:{...local,sid:'self'},remoteParticipants:new Map(),on:vi.fn((name,callback)=>listeners.set(name,callback)),connect:vi.fn(async()=>{}),disconnect:vi.fn(async()=>{}),removeAllListeners:vi.fn(),startAudio:vi.fn(async()=>{})};
  const sdk={Room:class{constructor(){return room;}},RoomEvent:{TrackSubscribed:'track',TrackUnsubscribed:'untrack',ConnectionStateChanged:'state',Disconnected:'disconnected'},createLocalTracks:vi.fn(async()=>[{kind:'audio',stop:vi.fn()}])};
  const onTrack=vi.fn(),onChange=vi.fn(),transport=createRoomTransport(sdk,{onChange,onTrack});
  const track=(kind='audio')=>{const element=document.createElement(kind==='audio'?'audio':'video');element.play=vi.fn(async()=>{});return {kind,sid:`TR_${kind}`,isMuted:false,mediaStreamTrack:{readyState:'live'},attach:vi.fn(()=>element),detach:vi.fn(()=>[element]),getRTCStatsReport:vi.fn()};};
  return {room,sdk,listeners,transport,onTrack,onChange,track};
}
describe('LiveKit transport device and receive evidence boundary',()=>{
  it('only requests sources explicitly chosen by the user and never logs or persists a token',async()=>{
    const h=harness();await h.transport.connect('wss://test','private',{microphone:true,camera:false});expect(h.sdk.createLocalTracks).toHaveBeenCalledTimes(1);expect(h.sdk.createLocalTracks).toHaveBeenCalledWith({audio:true,video:false});await h.transport.close();
  });
  it('does not attach prepared-room media until foreground is committed',async()=>{
    const h=harness(),track=h.track();await h.transport.connect('wss://test','private',{microphone:false,camera:false});h.transport.setForeground(false);h.listeners.get('track')(track);expect(track.attach).not.toHaveBeenCalled();h.transport.setForeground(true);expect(track.attach).toHaveBeenCalledTimes(1);h.transport.setForeground(false);expect(track.detach).toHaveBeenCalledTimes(1);await h.transport.close();
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
});
