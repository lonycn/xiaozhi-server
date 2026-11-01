import {h} from 'vue';
import {mount,flushPromises} from '@vue/test-utils';
import {describe,it,expect,vi,beforeEach,afterEach} from 'vitest';
import Overlay from '../src/components/MediaSessionOverlay.vue';
const api=vi.hoisted(()=>({create:vi.fn(),invitations:vi.fn(),current:vi.fn(),get:vi.fn(),accept:vi.fn(),reject:vi.fn(),token:vi.fn(),ready:vi.fn(),end:vi.fn(),experts:vi.fn(),capture:vi.fn(),transfer:vi.fn()}));
vi.mock('../src/apis/module/media',()=>({default:api}));
const attachment=vi.hoisted(()=>({init:vi.fn(),upload:vi.fn(),complete:vi.fn(),get:vi.fn()}));
vi.mock('../src/apis/module/attachment',()=>({default:attachment}));
vi.mock('../src/utils/attachments.mjs',async original=>({...await original(),fileSha256:vi.fn(async()=> 'a'.repeat(64))}));
const cameraTrack={mediaStreamTrack:{readyState:'live'}};
const media=vi.hoisted(()=>({factory:vi.fn()}));
vi.mock('../src/media/livekitTransport.mjs',()=>({createLiveKitTransport:media.factory}));
const session=(extra={})=>({id:'7',conversation_id:'c',request_id:'r',status:'invited',control_epoch:1,current_generation:1,agent_user_id:'a',participants:[{user_id:'u',role:'customer',room_generation:1,status:'invited',consent:{microphone:false,camera:false}},{user_id:'a',role:'agent',room_generation:1,status:'accepted',consent:{microphone:true,camera:false}}],...extra});
let wrapper,room;
function mountOverlay(props={}){wrapper=mount(Overlay,{props:{userId:'u',conversationId:'c',requestId:'r',identity:'u:true',writable:true,...props},global:{mocks:{$t:key=>key}}});return wrapper;}
beforeEach(()=>{vi.useFakeTimers();vi.resetAllMocks();api.invitations.mockResolvedValue({items:[{id:'7',type:'session',session_id:'7',conversation_id:'c'}],has_more:false,next_cursor:'0'});api.current.mockResolvedValue(session());api.get.mockResolvedValue(session());api.experts.mockResolvedValue({items:[],has_more:false,next_cursor:'0'});room={setForeground:vi.fn(),connect:vi.fn(async()=>{}),close:vi.fn(async()=>{}),evidence:vi.fn(async()=>({})),microphone:vi.fn(),camera:vi.fn(),videoTrack:()=>cameraTrack};media.factory.mockResolvedValue(room);});
afterEach(()=>{wrapper?.unmount();wrapper=null;delete document.fullscreenElement;delete document.exitFullscreen;vi.useRealTimers();vi.restoreAllMocks();});
describe('in-chat media consent and display',()=>{
 it('enters fullscreen only on request and follows actual native fullscreenchange including external exit',async()=>{
  mountOverlay({embedded:true});await flushPromises();const target=wrapper.find('.media-overlay').element;let actual=null;
  Object.defineProperty(document,'fullscreenElement',{configurable:true,get:()=>actual});target.requestFullscreen=vi.fn(async()=>{actual=target;document.dispatchEvent(new Event('fullscreenchange'));});Object.defineProperty(document,'exitFullscreen',{configurable:true,value:vi.fn(async()=>{actual=null;document.dispatchEvent(new Event('fullscreenchange'));})});
  expect(target.requestFullscreen).not.toHaveBeenCalled();await wrapper.vm.toggleFullscreen();expect(wrapper.vm.fullscreen).toBe(true);expect(wrapper.find('[aria-label="media.exitFullscreen"]').exists()).toBe(true);
  actual=null;document.dispatchEvent(new Event('fullscreenchange'));await wrapper.vm.$nextTick();expect(wrapper.vm.fullscreen).toBe(false);expect(target.requestFullscreen).toHaveBeenCalledOnce();
  target.requestFullscreen.mockRejectedValueOnce(new Error('denied'));await wrapper.vm.toggleFullscreen();expect(wrapper.vm.fullscreenError).toBe(true);expect(wrapper.vm.fullscreen).toBe(false);
 });
 it('guards media shortcuts for editable/IME/repeat input, and confirms keyboard hangup',async()=>{
  const active=session({status:'active',participants:[{user_id:'u',role:'customer',room_generation:1,status:'accepted',consent:{microphone:true,camera:true,allow_snapshots:true}},{user_id:'a',role:'agent',room_generation:1,status:'accepted',consent:{microphone:true,camera:true}}]});api.current.mockResolvedValue(active);api.get.mockResolvedValue(active);mountOverlay({embedded:true,staff:true,userId:'a'});await flushPromises();wrapper.vm.state={...wrapper.vm.state,session:active,joined:true,localState:'connected'};wrapper.vm.hasVideo=true;
  const toggle=vi.spyOn(wrapper.vm.controller,'toggle').mockResolvedValue(),end=vi.spyOn(wrapper.vm.controller,'end').mockResolvedValue(),capture=vi.spyOn(wrapper.vm,'capture').mockResolvedValue(),confirm=vi.spyOn(window,'confirm').mockReturnValue(false);
  const key=(code,extra={},target=document)=>target.dispatchEvent(new KeyboardEvent('keydown',{code,key:code.slice(3).toLowerCase(),altKey:true,bubbles:true,cancelable:true,...extra}));
  key('KeyM');expect(toggle).toHaveBeenCalledWith('microphone');key('KeyV');expect(toggle).toHaveBeenCalledWith('camera');
  const input=document.createElement('input');document.body.appendChild(input);key('KeyM',{},input);input.remove();key('KeyM',{isComposing:true});key('KeyM',{repeat:true});key('KeyM',{ctrlKey:true});expect(toggle).toHaveBeenCalledTimes(2);
  await wrapper.setProps({captureDisabled:true});key('KeyS');expect(capture).not.toHaveBeenCalled();await wrapper.setProps({captureDisabled:false});key('KeyS');expect(capture).toHaveBeenCalledOnce();
  key('KeyH');expect(confirm).toHaveBeenCalledWith('media.hangupConfirm');expect(end).not.toHaveBeenCalled();confirm.mockReturnValue(true);key('KeyH');expect(end).toHaveBeenCalledOnce();
 });
 it('drags and swaps PiP by changing layout while preserving remote/local video nodes and Room',async()=>{
  mountOverlay({embedded:true});await flushPromises();wrapper.vm.state={...wrapper.vm.state,hasLocalVideo:true};wrapper.vm.hasVideo=true;await wrapper.vm.$nextTick();
  const remote=wrapper.find('.media-stage').element,local=wrapper.find('.media-local-preview').element,remoteVideo=document.createElement('video'),localVideo=document.createElement('video');remote.append(remoteVideo);local.append(localVideo);
  vi.spyOn(wrapper.find('.media-stage-host').element,'getBoundingClientRect').mockReturnValue({width:500,height:300});vi.spyOn(local,'getBoundingClientRect').mockReturnValue({width:135,height:93});
  const event={pointerId:1,button:0,isPrimary:true,clientX:300,clientY:20,currentTarget:local,preventDefault:vi.fn()};wrapper.vm.startPip(event,true);wrapper.vm.movePip({...event,clientX:400,clientY:-100});wrapper.vm.endPip({...event,clientX:400,clientY:-100});expect(wrapper.vm.pipPosition).toEqual({x:.73,y:0});
  await wrapper.find('.media-local-preview').trigger('dblclick');expect(wrapper.vm.localMain).toBe(true);expect(wrapper.find('.media-local-main').exists()).toBe(true);expect(remote.contains(remoteVideo)).toBe(true);expect(local.contains(localVideo)).toBe(true);expect(room.close).not.toHaveBeenCalled();
  const touch={...event,pointerType:'touch'};wrapper.vm.startPip(touch,false);wrapper.vm.endPip(touch);wrapper.vm.startPip(touch,false);wrapper.vm.endPip(touch);expect(wrapper.vm.localMain).toBe(false);wrapper.vm.swapVideo();expect(wrapper.vm.localMain).toBe(true);
  await wrapper.setProps({captureDisabled:true});wrapper.vm.swapVideo();expect(wrapper.vm.localMain).toBe(true);expect(wrapper.find('.media-stage').element).toBe(remote);
 });

 it('mounts its editor slot inside the video host while keeping live-call controls outside it',async()=>{
  wrapper=mount(Overlay,{props:{userId:'u',conversationId:'c',requestId:'r',identity:'u:true',writable:true,embedded:true},slots:{'stage-overlay':()=>h('div',{'data-test':'editor-slot'},'frozen editor')},global:{mocks:{$t:key=>key}}});await flushPromises();
  const host=wrapper.find('.media-stage-host');expect(host.find('[data-test=editor-slot]').exists()).toBe(true);expect(host.find('.media-hangup').exists()).toBe(false);expect(wrapper.find('.media-hangup').exists()).toBe(true);
  await wrapper.vm.controller.load(null);await wrapper.vm.$nextTick();expect(wrapper.find('.media-stage-host').element).toBe(host.element);expect(host.find('[data-test=editor-slot]').exists()).toBe(true);
 });
 it('renders a persistent empty embedded stage without synthetic calls or opening devices',async()=>{
  api.current.mockResolvedValue(null);api.invitations.mockResolvedValue({items:[],has_more:false,next_cursor:'0'});mountOverlay({embedded:true});await flushPromises();
  const host=wrapper.find('.media-stage').element;expect(wrapper.find('.media-workspace--embedded').exists()).toBe(true);expect(wrapper.text()).toContain('media.waitingTitle');expect(wrapper.text()).toContain('media.waitingNote');
  expect(wrapper.find('[aria-label="media.minimize"]').exists()).toBe(false);expect(media.factory).not.toHaveBeenCalled();expect(api.token).not.toHaveBeenCalled();expect(api.create).not.toHaveBeenCalled();
  await wrapper.vm.controller.load(session());await flushPromises();expect(wrapper.find('.media-stage').element).toBe(host);expect(wrapper.text()).toContain('media.accept');
 });
 it('keeps attached video/audio nodes and room lifetime stable while snapshot editing blocks further capture',async()=>{
  const active=session({status:'active',participants:[{user_id:'u',role:'customer',room_generation:1,status:'accepted',consent:{microphone:true,camera:true,allow_snapshots:true}},{user_id:'a',role:'agent',room_generation:1,status:'accepted',consent:{microphone:true,camera:false}}]});
  api.current.mockResolvedValue(active);api.get.mockResolvedValue(active);mountOverlay({staff:true,userId:'a',embedded:true});await flushPromises();
  wrapper.vm.state={...wrapper.vm.state,session:active,joined:true,localState:'connected',microphone:true};wrapper.vm.hasVideo=true;await wrapper.vm.$nextTick();
  const host=wrapper.find('.media-stage').element,video=document.createElement('video'),audio=document.createElement('audio');host.append(video,audio);expect(wrapper.vm.canCapture).toBe(true);
  await wrapper.setProps({captureDisabled:true});await wrapper.vm.capture();expect(wrapper.vm.canCapture).toBe(false);expect(attachment.init).not.toHaveBeenCalled();expect(api.capture).not.toHaveBeenCalled();
  await wrapper.vm.poll();await wrapper.setProps({captureDisabled:false});
  expect(wrapper.find('.media-stage').element).toBe(host);expect(host.contains(video)).toBe(true);expect(host.contains(audio)).toBe(true);expect(room.close).not.toHaveBeenCalled();expect(wrapper.find('.media-hangup').exists()).toBe(true);
 });

 it('starts a new create intent after a completed call and preserves its key after an uncertain response',async()=>{
  api.invitations.mockResolvedValue({items:[],has_more:false,next_cursor:'0'}); api.current.mockResolvedValue(null);
  const first=session({id:'7',status:'invited'}), ended={...first,status:'ended'}, second=session({id:'8',status:'invited'});
  api.create.mockResolvedValueOnce(first).mockRejectedValueOnce(new Error('response lost')).mockResolvedValueOnce(second);
  api.end.mockResolvedValue(ended); mountOverlay(); await flushPromises();
  wrapper.vm.choice={microphone:true,camera:false,allow_snapshots:false};
  await wrapper.vm.invite(); const firstKey=api.create.mock.calls[0][0].client_session_id;
  await wrapper.vm.controller.end(); expect(wrapper.vm.terminal).toBe(true);
  await wrapper.vm.invite(); const secondKey=api.create.mock.calls[1][0].client_session_id;
  expect(secondKey).not.toBe(firstKey); expect(wrapper.vm.state.error).toBe('media.failed');
  await wrapper.vm.invite(); expect(api.create.mock.calls[2][0].client_session_id).toBe(secondKey);
  expect(wrapper.vm.state.session.id).toBe('8');
 });
 it.each(['rejected','canceled','expired','failed'])('starts a new intent after a %s session',async status=>{
  api.invitations.mockResolvedValue({items:[],has_more:false,next_cursor:'0'});api.current.mockResolvedValue(null);
  const first=session({id:'7'});api.create.mockResolvedValueOnce(first).mockResolvedValueOnce(session({id:'8'}));
  mountOverlay();await flushPromises();wrapper.vm.choice={microphone:true,camera:false,allow_snapshots:false};
  await wrapper.vm.invite();await wrapper.vm.controller.load({...first,status});await wrapper.vm.invite();
  expect(api.create.mock.calls[1][0].client_session_id).not.toBe(api.create.mock.calls[0][0].client_session_id);
 });
 it('uses a new key when the request or consent changes after an uncertain create',async()=>{
  api.invitations.mockResolvedValue({items:[],has_more:false,next_cursor:'0'});api.current.mockResolvedValue(null);api.create.mockRejectedValue(new Error('response lost'));
  mountOverlay();await flushPromises();wrapper.vm.choice={microphone:true,camera:false,allow_snapshots:false};
  await wrapper.vm.invite();await wrapper.vm.invite();
  const key=api.create.mock.calls[0][0].client_session_id;expect(api.create.mock.calls[1][0].client_session_id).toBe(key);
  wrapper.vm.choice.camera=true;await wrapper.vm.invite();const next=api.create.mock.calls[2][0].client_session_id;expect(next).not.toBe(key);
  await wrapper.setProps({requestId:'other'});await wrapper.vm.invite();expect(api.create.mock.calls[3][0].client_session_id).not.toBe(next);
 });
 it('ignores a late create response after an identity reset',async()=>{
  api.invitations.mockResolvedValue({items:[],has_more:false,next_cursor:'0'});api.current.mockResolvedValue(null);
  let finish;api.create.mockReturnValue(new Promise(resolve=>{finish=resolve;}));mountOverlay();await flushPromises();
  wrapper.vm.choice={microphone:true,camera:false,allow_snapshots:false};wrapper.vm.inviting=true;const pending=wrapper.vm.invite();
  await wrapper.setProps({identity:'other:true',userId:'other'});wrapper.vm.inviting=true;wrapper.vm.hidden=true;
  finish(session());await pending;expect(wrapper.vm.state.session).toBeNull();expect(wrapper.vm.inviting).toBe(true);expect(wrapper.vm.hidden).toBe(true);
 });
 it('renders incoming consent without requesting a token or opening a device',async()=>{mountOverlay();await flushPromises();expect(wrapper.text()).toContain('media.accept');expect(wrapper.findAll('input[type=checkbox]').every(input=>!input.element.checked)).toBe(true);expect(media.factory).not.toHaveBeenCalled();expect(api.token).not.toHaveBeenCalled();const accept=wrapper.findAll('button').find(button=>button.text()==='media.accept');expect(accept.attributes('disabled')).toBeDefined();});
 it('requires microphone consent, keeps camera optional, and passes exact choices on explicit accept',async()=>{mountOverlay();await flushPromises();const accepted=session({status:'connecting',control_epoch:2,participants:session().participants.map(p=>({...p,status:'accepted',consent:{microphone:true,camera:false,allow_snapshots:false}}))});api.accept.mockResolvedValue(accepted);api.get.mockResolvedValue(accepted);api.token.mockResolvedValue({session_id:'7',control_epoch:2,room_generation:1,server_url:'wss://m',participant_token:'private',publish_sources:['microphone']});await wrapper.find('input[type=checkbox]').setValue(true);await wrapper.findAll('button').find(button=>button.text()==='media.accept').trigger('click');await flushPromises();expect(api.accept.mock.calls[0][1].consent).toEqual({microphone:true,camera:false,allow_snapshots:false});expect(room.connect.mock.calls[0][2]).toEqual({microphone:true,camera:false});});
 it('retains mounted media elements while minimized and expanded',async()=>{mountOverlay();await flushPromises();const host=wrapper.find('.media-stage').element;const button=wrapper.find('button[aria-label="media.minimize"]');await button.trigger('click');expect(wrapper.find('.media-stage').element).toBe(host);await button.trigger('click');expect(wrapper.find('.media-stage').element).toBe(host);});
 it('does not render active when only server status is active but this browser is disconnected',async()=>{api.current.mockResolvedValue(session({status:'active'}));api.get.mockResolvedValue(session({status:'active'}));mountOverlay();await flushPromises();expect(wrapper.text()).toContain('media.locallyDisconnected');expect(wrapper.text()).not.toContain('media.status_active');});
 it('shows a receive interruption instead of active and disables capture until current RTP recovers',async()=>{
  const active=session({status:'active',participants:[{user_id:'u',role:'customer',room_generation:1,consent:{microphone:true,camera:true,allow_snapshots:true}},{user_id:'a',role:'agent',room_generation:1,consent:{microphone:true,camera:false}}]});
  api.current.mockResolvedValue(active);api.get.mockResolvedValue(active);mountOverlay({staff:true,userId:'a'});await flushPromises();
  wrapper.vm.state={...wrapper.vm.state,session:active,joined:true,localState:'connected',mediaStalled:true};wrapper.vm.hasVideo=true;await wrapper.vm.$nextTick();
  expect(wrapper.text()).toContain('media.waitingForMedia');expect(wrapper.text()).not.toContain('media.status_active');expect(wrapper.vm.canCapture).toBe(false);
  wrapper.vm.state={...wrapper.vm.state,mediaStalled:false};await wrapper.vm.$nextTick();expect(wrapper.text()).toContain('media.status_active');expect(wrapper.vm.canCapture).toBe(true);
 });
 it('closes media and aborts pending discovery when the authenticated identity changes',async()=>{mountOverlay();await flushPromises();const signal=api.current.mock.calls[0][1];await wrapper.setProps({identity:'other:true',userId:'other'});expect(signal.aborted).toBe(true);expect(wrapper.vm.choice).toEqual({microphone:false,camera:false,allow_snapshots:false});});
 it('shows actual unavailable state for a disabled feature and never creates a synthetic call',async()=>{api.invitations.mockRejectedValue(Object.assign(new Error('off'),{status:404}));mountOverlay();await flushPromises();expect(wrapper.text()).toContain('media.unavailable');expect(wrapper.find('.media-overlay').exists()).toBe(false);expect(wrapper.findAll('button').some(button=>button.text()==='media.invite')).toBe(false);});

 it('captures only an explicitly permitted customer frame through a private upload and frozen frame API, without sending a chat message',async()=>{
  const active=session({status:'active',control_epoch:3,participants:[{user_id:'u',role:'customer',room_generation:1,status:'accepted',consent:{microphone:true,camera:true,allow_snapshots:true}},{user_id:'a',role:'agent',room_generation:1,status:'accepted',consent:{microphone:true,camera:false}}]});
  api.current.mockResolvedValue(active);api.get.mockResolvedValue(active);api.invitations.mockResolvedValue({items:[],has_more:false,next_cursor:'0'});api.token.mockResolvedValue({session_id:'7',control_epoch:3,room_generation:1,server_url:'wss://m',participant_token:'private',publish_sources:['microphone']});
  media.factory.mockImplementation(async options=>{room.connect.mockImplementation(async()=>options.onChange({state:'connected',connected:true,hasVideo:true,hasLocalVideo:true}));return room;});
  mountOverlay({staff:true,userId:'a',embedded:true});await flushPromises();await wrapper.vm.controller.reconnect({microphone:true,camera:false});await flushPromises();expect(wrapper.vm.canCapture).toBe(true);
  const video=document.createElement('video');Object.defineProperties(video,{readyState:{value:3},videoWidth:{value:3840},videoHeight:{value:2160}});wrapper.find('.media-stage').element.appendChild(video);const localVideo=document.createElement('video');wrapper.find('.media-local-preview').element.appendChild(localVideo);wrapper.vm.localMain=true;
  const draw=vi.fn();vi.spyOn(HTMLCanvasElement.prototype,'getContext').mockReturnValue({drawImage:draw});vi.spyOn(HTMLCanvasElement.prototype,'toBlob').mockImplementation(function(callback){expect(this.width).toBe(1920);expect(this.height).toBe(1080);callback(new Blob(['jpeg'],{type:'image/jpeg'}));});
  attachment.init.mockResolvedValue({id:'9',status:'pending_upload',upload:{method:'PUT',url:'private'}});attachment.upload.mockResolvedValue({});attachment.complete.mockResolvedValue({id:'9',status:'ready'});api.capture.mockResolvedValue({id:'11',conversation_id:'c',frame_attachment_id:'10',status:'ready'});
  await wrapper.vm.capture();expect(draw.mock.calls[0][0]).toBe(video);expect(draw.mock.calls[0][0]).not.toBe(localVideo);expect(attachment.upload).toHaveBeenCalledTimes(1);expect(api.capture).toHaveBeenCalledWith('7',{expected_epoch:3,room_generation:1,source_attachment_id:'9',client_frame_id:expect.any(String),captured_at:expect.any(String)},expect.any(AbortSignal));expect(wrapper.emitted('frame')[0][0]).toMatchObject({frame:{id:'11'},conversationId:'c'});expect(wrapper.text()).toContain('media.captureNote');
 });
 it('does not capture or upload when the customer has not consented to saved frames',async()=>{mountOverlay({staff:true,userId:'a'});await flushPromises();await wrapper.vm.capture();expect(attachment.init).not.toHaveBeenCalled();expect(api.capture).not.toHaveBeenCalled();});
 it('creates a new nonempty transfer intent when the same expert is selected after an earlier request completed',async()=>{
  const active=session({status:'active'});api.current.mockResolvedValue(active);api.get.mockResolvedValue(active);api.transfer.mockResolvedValue({});
  mountOverlay({staff:true,userId:'a'});await flushPromises();wrapper.vm.targetId='72';await wrapper.vm.startTransfer();
  const first=api.transfer.mock.calls[0][1].client_transfer_id;expect(first).toMatch(/^[a-f0-9-]{36}$/);
  wrapper.vm.targetId='72';await wrapper.vm.startTransfer();const second=api.transfer.mock.calls[1][1].client_transfer_id;
  expect(second).toMatch(/^[a-f0-9-]{36}$/);expect(second).not.toBe(first);
 });
 it('keeps the transfer intent when its response is lost and retries the same expert safely',async()=>{
  const active=session({status:'active'});api.current.mockResolvedValue(active);api.get.mockResolvedValue(active);api.transfer.mockRejectedValueOnce(new Error('response lost')).mockResolvedValueOnce({});
  mountOverlay({staff:true,userId:'a'});await flushPromises();wrapper.vm.targetId='72';await wrapper.vm.startTransfer();
  const first=api.transfer.mock.calls[0][1].client_transfer_id;expect(wrapper.vm.state.error).toBe('media.failed');
  await wrapper.vm.startTransfer();expect(api.transfer.mock.calls[1][1].client_transfer_id).toBe(first);expect(wrapper.vm.targetId).toBe('');
 });
 it('keeps hangup available during pending browser permissions',async()=>{mountOverlay();await flushPromises();wrapper.vm.state={...wrapper.vm.state,busy:true};await wrapper.vm.$nextTick();expect(wrapper.find('.media-hangup').attributes('disabled')).toBeUndefined();});
});
