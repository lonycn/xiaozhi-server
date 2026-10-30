import { mount, flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Editor from '../src/components/AnnotationEditor.vue';
const api=vi.hoisted(() => ({createFrame:vi.fn(),frame:vi.fn(),retryFrame:vi.fn(),save:vi.fn(),get:vi.fn(),retry:vi.fn(),list:vi.fn()}));
const attachment=vi.hoisted(() => ({get:vi.fn(),access:vi.fn()}));
const images=vi.hoisted(() => ({blob:vi.fn(),decode:vi.fn(),release:vi.fn()}));
vi.mock('../src/apis/module/annotation',() => ({default:api}));
vi.mock('../src/apis/module/attachment',() => ({default:attachment}));
vi.mock('../src/apis/module/annotationClient.mjs',() => ({privateAnnotationBlob:images.blob,decodeAnnotationBlob:images.decode}));
const frame={id:'10',conversation_id:'c',source_attachment_id:'9',frame_attachment_id:'11',requested_time_ms:null,actual_time_ms:null,capture_policy:'first_at_or_after',width:1000,height:500,status:'ready'};
const scene={format:'annotation-v1',frame_id:'10',width:1000,height:500,elements:[]};
const snapshot={id:'12',conversation_id:'c',frame_id:'10',render_attachment_id:'13',label:'检查阀门',scene,status:'ready'};
const source={id:'9',conversation_id:'c',filename:'现场.jpg',kind:'image',status:'ready',metadata:{width:1000,height:500}};
const generated={id:'13',conversation_id:'c',filename:'标注.jpg',kind:'image',size:123,status:'ready',metadata:{annotation_id:'12',frame_id:'10',source_attachment_id:'9'}};
const deferred=() => {let resolve;const promise=new Promise(r => {resolve=r;});return {promise,resolve};};
let wrapper,ctx;
function desk(props={}) {wrapper=mount(Editor,{props:{conversationId:'c',scopeKey:'actor:c:assignment',identity:'actor',writable:true,...props},global:{mocks:{$t:key => key},stubs:{Teleport:true}}});return wrapper.vm;}
beforeEach(() => {
  vi.useFakeTimers();vi.resetAllMocks();
  attachment.get.mockImplementation(async id => id==='13'?generated:source);
  api.createFrame.mockResolvedValue(frame);api.frame.mockResolvedValue(frame);api.get.mockResolvedValue(snapshot);api.list.mockResolvedValue({items:[snapshot],next_cursor:'12',has_more:false});api.save.mockImplementation(async body => ({...snapshot,label:body.label,scene:body.scene}));api.retry.mockResolvedValue(snapshot);api.retryFrame.mockResolvedValue(frame);
  images.blob.mockResolvedValue(new Blob(['private'],{type:'image/jpeg'}));images.decode.mockResolvedValue({image:{src:'blob:private',naturalWidth:1000,naturalHeight:500},release:images.release});
  ctx={setTransform:vi.fn(),clearRect:vi.fn(),drawImage:vi.fn(),save:vi.fn(),restore:vi.fn(),beginPath:vi.fn(),moveTo:vi.fn(),lineTo:vi.fn(),stroke:vi.fn(),strokeRect:vi.fn(),arc:vi.fn(),fillText:vi.fn()};
  vi.spyOn(HTMLCanvasElement.prototype,'getContext').mockReturnValue(ctx);vi.spyOn(HTMLCanvasElement.prototype,'getBoundingClientRect').mockReturnValue({left:0,top:0,width:500,height:250});
});
afterEach(() => {wrapper?.unmount();wrapper=null;vi.useRealTimers();vi.restoreAllMocks();});
const arrow={id:'a1',type:'arrow',color:'#123456',stroke_width:.004,points:[{x:.1,y:.1},{x:.5,y:.5}]};
describe('annotation editor snapshots and private canvas', () => {
  it('creates an authorized image frame, downloads a private blob and sizes the canvas to the viewport', async () => {
    const vm=desk();await vm.openSource({attachmentId:'9'});
    expect(api.createFrame.mock.calls[0][0]).toMatchObject({conversation_id:'c',source_attachment_id:'9',client_frame_id:expect.any(String)});expect(api.createFrame.mock.calls[0][0].time_ms).toBeUndefined();
    expect(images.blob).toHaveBeenCalledWith(attachment,'11',expect.any(AbortSignal));expect(vm.scene).toEqual(scene);expect(ctx.drawImage).toHaveBeenCalled();expect(wrapper.find('canvas').element.width).toBe(500);
  });
  it('requests an exact integer video time and displays the server requested/actual frame times', async () => {
    attachment.get.mockResolvedValue({...source,kind:'video',metadata:{duration_ms:3000}});api.createFrame.mockResolvedValue({...frame,requested_time_ms:1250,actual_time_ms:1333});const vm=desk();await vm.openSource({attachmentId:'9'});expect(api.createFrame).not.toHaveBeenCalled();
    vm.requestedTimeMs=1250;await vm.capture();expect(api.createFrame.mock.calls[0][0].time_ms).toBe(1250);expect(wrapper.text()).toContain('1250 ms');expect(wrapper.text()).toContain('1333 ms');
    vm.requestedTimeMs=3000;await vm.capture();expect(vm.error).toBe('annotations.invalidTime');expect(api.createFrame).toHaveBeenCalledOnce();
  });
  it('polls a processing frame before installing dimensions or enabling drawing', async () => {
    api.createFrame.mockResolvedValue({...frame,status:'processing',width:null,height:null});const vm=desk(),pending=vm.openSource({attachmentId:'9'});await flushPromises();expect(vm.scene).toBeNull();expect(vm.busy).toBe(true);
    await vi.advanceTimersByTimeAsync(1500);await pending;expect(api.frame).toHaveBeenCalledWith('10',expect.any(AbortSignal));expect(vm.scene.width).toBe(1000);
  });
  it('preserves local edits and the save idempotency identifier on failure; a content edit gets a new ID', async () => {
    const vm=desk();await vm.openSource({attachmentId:'9'});vm.commit({...vm.scene,elements:[arrow]});api.save.mockRejectedValueOnce(new Error('network'));
    await vm.saveSnapshot();const first=api.save.mock.calls[0][0];expect(vm.scene.elements).toHaveLength(1);expect(vm.error).toBe('annotations.failed');
    await vm.saveSnapshot();expect(api.save.mock.calls[1][0]).toEqual(first);expect(wrapper.emitted('ready')).toBeUndefined();
    vm.label='另一个不可变快照';await vm.saveSnapshot();expect(api.save.mock.calls[2][0].client_annotation_id).not.toBe(first.client_annotation_id);
    expect(JSON.stringify(first.scene)).not.toMatch(/blob:|https:|base64|render_attachment/);
  });
  it('keeps frame request IDs on ambiguous creation failures', async () => {
    api.createFrame.mockRejectedValueOnce(new Error('network'));const vm=desk();await vm.openSource({attachmentId:'9'});expect(vm.error).toBe('annotations.failed');const first=api.createFrame.mock.calls[0][0];
    await vm.capture();expect(api.createFrame.mock.calls[1][0]).toEqual(first);expect(vm.frame.status).toBe('ready');
  });
  it('keeps saving separate from sending and stages only a ready server-rendered image with trusted provenance', async () => {
    const vm=desk();await vm.openSource({attachmentId:'9'});await vm.saveSnapshot();expect(wrapper.emitted('ready')).toBeUndefined();expect(vm.previewUrl).toBe('blob:private');
    await vm.addToReply();const event=wrapper.emitted('ready')[0][0];expect(event.scopeKey).toBe('actor:c:assignment');expect(event.row.attachment).toEqual(generated);expect(event.row.kind).toBe('image');expect(event.row.file).toBeUndefined();expect(vm.visible).toBe(false);
  });
  it('cannot stage processing renders, or a saved render after further unsaved edits', async () => {
    api.save.mockResolvedValue({...snapshot,status:'processing'});const vm=desk();await vm.openSource({attachmentId:'9'});const saving=vm.saveSnapshot();await flushPromises();await vm.addToReply();expect(wrapper.emitted('ready')).toBeUndefined();
    await vi.advanceTimersByTimeAsync(1500);await saving;vm.label='unsaved change';await vm.addToReply();expect(wrapper.emitted('ready')).toBeUndefined();
  });
  it('rejects a generated attachment whose server provenance does not match the selected snapshot', async () => {
    const vm=desk();await vm.openSource({attachmentId:'9'});await vm.saveSnapshot();attachment.get.mockResolvedValue({...generated,metadata:{annotation_id:'99',frame_id:'10'}});await vm.addToReply();expect(wrapper.emitted('ready')).toBeUndefined();expect(vm.error).toBe('annotations.failed');
  });
  it('restores unsaved local drawing history after closing without altering a saved snapshot', async () => {
    const vm=desk();await vm.openSource({attachmentId:'9'});vm.commit({...vm.scene,elements:[arrow]});vm.close();expect(images.release).toHaveBeenCalled();await vm.openSource({attachmentId:'9'});expect(vm.scene.elements).toEqual([arrow]);expect(api.createFrame).toHaveBeenCalledOnce();vm.undo();expect(vm.scene.elements).toHaveLength(0);
  });
  it('lists persisted snapshots and restores the original frame and complete scene after a fresh mount', async () => {
    const vm=desk();await vm.loadSnapshots();expect(vm.snapshots).toEqual([snapshot]);await vm.openSnapshot('12');expect(vm.frame.id).toBe('10');expect(vm.scene).toEqual(scene);expect(vm.saved.id).toBe('12');expect(vm.dirty).toBe(false);
  });
  it('never reuses a historical render: restored scenes require an explicit new immutable snapshot before staging', async () => {
    const vm=desk();await vm.openSnapshot('12');expect(vm.requiresCopy).toBe(true);await vm.addToReply();expect(wrapper.emitted('ready')).toBeUndefined();
    api.save.mockImplementation(async body => ({...snapshot,id:'20',render_attachment_id:'21',label:body.label,scene:body.scene}));attachment.get.mockResolvedValue({...generated,id:'21',metadata:{annotation_id:'20',frame_id:'10'}});
    await vm.saveSnapshot();expect(api.save).toHaveBeenCalledOnce();expect(vm.saved.id).toBe('20');expect(vm.requiresCopy).toBe(false);await vm.addToReply();expect(wrapper.emitted('ready')[0][0].row.attachment.id).toBe('21');expect(vm.requiresCopy).toBe(true);expect(vm.annotationPending).toBeNull();
  });
  it('resumes polling a processing snapshot restored after page refresh', async () => {
    api.get.mockResolvedValueOnce({...snapshot,status:'processing'}).mockResolvedValueOnce(snapshot);
    const vm=desk(),opening=vm.openSnapshot('12');await flushPromises();expect(vm.saved.status).toBe('processing');expect(vm.previewUrl).toBe('');
    await vi.advanceTimersByTimeAsync(1500);await opening;expect(vm.saved.status).toBe('ready');expect(vm.previewUrl).toBe('blob:private');expect(vm.requiresCopy).toBe(true);
  });
  it('uses 64-bit snapshot cursors and rejects non-advancing pages', async () => {
    api.list.mockResolvedValueOnce({items:[snapshot],has_more:true,next_cursor:'9223372036854775805'}).mockResolvedValueOnce({items:[],has_more:true,next_cursor:'9223372036854775805'});const vm=desk();await vm.loadSnapshots();await vm.loadSnapshots(true);expect(api.list.mock.calls[1][1]).toBe('9223372036854775805');expect(vm.error).toBe('annotations.failed');
  });
  it('aborts a late frame response on conversation switch without installing its scene or leaking a draft to the new identity', async () => {
    const response=deferred();api.createFrame.mockReturnValue(response.promise);const vm=desk(),opening=vm.openSource({attachmentId:'9'});await flushPromises();const signal=api.createFrame.mock.calls[0][1];
    await wrapper.setProps({conversationId:'other',scopeKey:'actor:other:assignment'});expect(signal.aborted).toBe(true);response.resolve(frame);await opening;expect(vm.scene).toBeNull();expect(images.blob).not.toHaveBeenCalled();expect(Object.keys(vm.localDrafts).every(key => key.includes('actor:c:assignment'))).toBe(true);
    await wrapper.setProps({identity:'another-actor',scopeKey:'another:other'});expect(vm.localDrafts).toEqual({});
  });
  it('releases private images on assignment loss and blocks saving, retry and staging while retaining the local draft', async () => {
    const vm=desk();await vm.openSource({attachmentId:'9'});vm.commit({...vm.scene,elements:[arrow]});await wrapper.setProps({writable:false});expect(images.release).toHaveBeenCalled();expect(vm.visible).toBe(false);expect(Object.values(vm.localDrafts)[0].scene.elements).toEqual([arrow]);
    await vm.saveSnapshot();await vm.retryCurrent();await vm.addToReply();expect(api.save).not.toHaveBeenCalled();expect(wrapper.emitted('ready')).toBeUndefined();
  });
  it('ignores a late decoded private background and releases it after teardown', async () => {
    const decode=deferred();images.decode.mockReturnValue(decode.promise);const vm=desk(),opening=vm.openSource({attachmentId:'9'});await flushPromises();wrapper.unmount();wrapper=null;decode.resolve({image:{naturalWidth:1000,naturalHeight:500},release:images.release});await opening;expect(images.release).toHaveBeenCalled();expect(vm.background).toBeNull();
  });
  it('draws normalized pointer gestures, supports undo/redo and makes reset reversible', async () => {
    const vm=desk();await vm.openSource({attachmentId:'9'});const canvas=wrapper.find('canvas').element;const event=(x,y) => ({pointerId:1,button:0,isPrimary:true,clientX:x,clientY:y,currentTarget:canvas,preventDefault:vi.fn()});
    vm.tool='rect';vm.pointerDown(event(50,25));vm.pointerUp(event(250,125));expect(vm.scene.elements[0]).toMatchObject({type:'rect',x:.1,y:.1,width:.4,height:.4});vm.resetScene();expect(vm.scene.elements).toHaveLength(0);vm.undo();expect(vm.scene.elements).toHaveLength(1);vm.redo();expect(vm.scene.elements).toHaveLength(0);
  });
  it('respects Chinese IME and retains invalid text for correction instead of discarding it', async () => {
    const vm=desk();await vm.openSource({attachmentId:'9'});vm.textPosition={x:.1,y:.1};vm.textDraft='检查冷库';const preventDefault=vi.fn();vm.textKeydown({key:'Enter',isComposing:true,preventDefault});expect(vm.scene.elements).toHaveLength(0);
    vm.textKeydown({key:'Enter',isComposing:false,preventDefault});expect(vm.scene.elements[0]).toMatchObject({type:'text',text:'检查冷库'});expect(vm.scene.elements[0].stroke_width).toBeUndefined();
    vm.textPosition={x:.1,y:.1};vm.textDraft='🙂'.repeat(1001);vm.commitText();expect(vm.textDraft).toBe('🙂'.repeat(1001));expect(vm.error).toBe('annotations.invalidScene');
  });
  it('opens an authorized B5 live frame directly without recapturing it as a static attachment', async () => {
    const vm=desk();await vm.openFrame('10');expect(api.frame).toHaveBeenCalledWith('10',expect.any(AbortSignal));expect(api.createFrame).not.toHaveBeenCalled();expect(vm.scene).toEqual(scene);expect(vm.source).toBeNull();
  });
  it('rejects a live frame from a different conversation before downloading private pixels', async () => {
    api.frame.mockResolvedValue({...frame,conversation_id:'another'});const vm=desk();await vm.openFrame('10');expect(vm.scene).toBeNull();expect(images.blob).not.toHaveBeenCalled();expect(vm.error).toBe('annotations.failed');
  });
  it.each([[1000,500,2400,1060],[500,1000,2400,1060],[1000,500,390,844],[500,1000,390,844]])('keeps a %i x %i frame within the 55dvh canvas bound at a %i x %i viewport', async (width,height,viewportWidth,viewportHeight) => {
    const vm=desk();vm.scene={...scene,width,height};vm.background={};vm.visible=true;await vm.$nextTick();const wrap=wrapper.find('.annotation-canvas-wrap');expect(wrap.exists()).toBe(true);expect(vm.canvasLayout.width).toBe(`min(100%, ${55*width/height}dvh)`);expect(wrapper.find('canvas').attributes('style')).toContain(`aspect-ratio: ${width} / ${height}`);
    // CSS min() uses the dialog's available width and the dynamic viewport height.
    const availableWidth=Math.min(1080,viewportWidth-(viewportWidth<=600?8:24))-(viewportWidth<=600?24:36),renderWidth=Math.min(availableWidth,.55*viewportHeight*width/height),renderHeight=renderWidth*height/width;
    expect(renderWidth).toBeLessThanOrEqual(availableWidth);expect(renderHeight).toBeLessThanOrEqual(.55*viewportHeight+.001);expect(renderWidth/renderHeight).toBeCloseTo(width/height);
  });

  it('pauses temporary send work without reporting permission loss and fences late editor responses', async () => {
    const response=deferred();api.createFrame.mockReturnValueOnce(response.promise);const vm=desk(),opening=vm.openSource({attachmentId:'9'});await flushPromises();const signal=api.createFrame.mock.calls[0][1];
    await wrapper.setProps({disabled:true});expect(vm.writable).toBe(true);expect(signal.aborted).toBe(true);expect(vm.visible).toBe(false);expect(vm.error).toBe('');
    response.resolve(frame);await opening;expect(vm.scene).toBeNull();expect(images.blob).not.toHaveBeenCalled();
    await vm.openSource({attachmentId:'9'});await vm.openSnapshot('12');await vm.loadSnapshots();await vm.saveSnapshot();await vm.retryCurrent();await vm.addToReply();expect(api.createFrame).toHaveBeenCalledTimes(1);expect(api.get).not.toHaveBeenCalled();expect(api.list).not.toHaveBeenCalled();expect(api.save).not.toHaveBeenCalled();expect(wrapper.emitted('ready')).toBeUndefined();
    await wrapper.setProps({disabled:false});expect(vm.error).toBe('');await vm.openSource({attachmentId:'9'});expect(vm.visible).toBe(true);expect(vm.scene).toEqual(scene);
    const resumedSignal=api.createFrame.mock.calls.at(-1)[1];await wrapper.setProps({writable:false});expect(resumedSignal.aborted).toBe(true);expect(vm.visible).toBe(false);expect(vm.error).toBe('annotations.denied');expect(images.release).toHaveBeenCalled();
  });

});
