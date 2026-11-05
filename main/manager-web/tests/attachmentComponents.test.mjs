import { webcrypto } from 'node:crypto';
import { mount, flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AttachmentPart from '../src/components/AttachmentPart.vue';
import AttachmentComposer from '../src/components/AttachmentComposer.vue';
const api = vi.hoisted(() => ({ init: vi.fn(), upload: vi.fn(), complete: vi.fn(), get: vi.fn(), access: vi.fn(), retry: vi.fn() }));
vi.mock('../src/apis/module/attachment', () => ({ default: api }));
let wrapper;
const options = { global: { mocks: { $t: key => key } } };
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
function part(type = 'image') { wrapper = mount(AttachmentPart, { ...options, props: { part: { type, attachment_id: '9223372036854775806' }, scopeKey: 'identity:conversation' } }); return wrapper; }
beforeEach(() => {
  vi.resetAllMocks(); vi.useFakeTimers();
  api.get.mockResolvedValue({ id: '9223372036854775806', filename: '<script>alert(1)</script>.png', status: 'ready', jobs: [], metadata: {} });
  api.access.mockResolvedValue({ url: 'http://localhost/xiaozhi/api/v1/ai/attachments/9223372036854775806/content?ticket=short', expires_at: new Date(Date.now() + 10000).toISOString() });
});
afterEach(() => { wrapper?.unmount(); wrapper = null; vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
describe('private attachment presentation', () => {
  it('does not request metadata or a ticket until the user asks to view it', async () => {
    part(); await flushPromises(); expect(api.get).not.toHaveBeenCalled(); expect(api.access).not.toHaveBeenCalled(); expect(wrapper.find('img').exists()).toBe(false);
    await wrapper.find('button').trigger('click'); await flushPromises();
    expect(api.access).toHaveBeenCalledWith('9223372036854775806', 'display', expect.any(AbortSignal));
    expect(wrapper.find('img').attributes('src')).toContain('?ticket=short'); expect(wrapper.find('script').exists()).toBe(false); expect(wrapper.text()).toContain('<script>alert(1)</script>.png');
  });
  it.each(['video','audio'])('creates a %s player only after an explicit user action and never autoplays', async type => {
    part(type); expect(wrapper.find(type).exists()).toBe(false);
    await wrapper.vm.open(false); const player = wrapper.find(type); expect(player.exists()).toBe(true); expect(player.attributes('autoplay')).toBeUndefined(); expect(player.attributes('preload')).toBe('none'); expect(player.attributes('controls')).toBeDefined();
    expect(api.access.mock.calls[0][1]).toBe('original');
  });
  it('expires private links and requires a new explicit authorization action', async () => {
    part(); await wrapper.vm.open(false); expect(api.access).toHaveBeenCalledOnce();
    await vi.advanceTimersByTimeAsync(10001); expect(wrapper.find('img').exists()).toBe(false); expect(wrapper.text()).toContain('attachments.reauthorize'); expect(api.access).toHaveBeenCalledOnce();
    api.access.mockResolvedValue({ url: 'http://localhost/content?ticket=renewed', expires_at: new Date(Date.now() + 10000).toISOString() });
    await wrapper.vm.open(false); expect(api.access).toHaveBeenCalledTimes(2); expect(wrapper.find('img').attributes('src')).toContain('renewed');
  });
  it('requires a user action after a content load failure rather than looping access requests', async () => {
    part(); await wrapper.vm.open(false); await wrapper.find('img').trigger('error');
    expect(wrapper.find('img').exists()).toBe(false); expect(wrapper.text()).toContain('attachments.expired'); expect(api.access).toHaveBeenCalledOnce();
  });
  it('aborts and fences metadata when switching identity or conversation', async () => {
    const request = deferred(); api.get.mockReturnValue(request.promise); part(); const pending = wrapper.vm.open(false), signal = api.get.mock.calls[0][1];
    await wrapper.setProps({ scopeKey: 'different:conversation' }); expect(signal.aborted).toBe(true);
    request.resolve({ id: '9', filename: 'old-account.png', status: 'ready' }); await pending;
    expect(api.access).not.toHaveBeenCalled(); expect(wrapper.text()).not.toContain('old-account'); expect(wrapper.find('img').exists()).toBe(false);
  });
  it('aborts late tickets on teardown and never inserts the late media', async () => {
    const request = deferred(); api.access.mockReturnValue(request.promise); part(); const pending = wrapper.vm.open(false); await flushPromises();
    const signal = api.access.mock.calls[0][2]; wrapper.unmount(); wrapper = null; expect(signal.aborted).toBe(true);
    request.resolve({ url: 'late', expires_at: new Date(Date.now()+10000).toISOString() }); await pending;
  });
  it('shows transcription failure independently while keeping checked audio playable', async () => {
    api.get.mockResolvedValue({ id: '9', filename: 'recording.m4a', status: 'ready', jobs: [{ id: '11', job_type: 'audio_transcription', status: 'failed', error_code: 'PROVIDER_AUTH' }], metadata: { artifacts: ['audio'] } });
    part('audio'); await wrapper.vm.open(false);
    expect(wrapper.find('audio').exists()).toBe(true); expect(wrapper.text()).toContain('attachments.transcriptionFailed'); expect(wrapper.text()).not.toContain('PROVIDER_AUTH');
  });
  it('downloads through a ticket without embedding a long-lived bearer or external HTML', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () { expect(this.href).toContain('?ticket=short'); expect(this.referrerPolicy).toBe('no-referrer'); expect(this.rel).toBe('noopener noreferrer'); });
    part('file'); await wrapper.vm.open(true);
    expect(api.access.mock.calls[0][1]).toBe('original'); expect(click).toHaveBeenCalledOnce(); expect(wrapper.find('iframe').exists()).toBe(false); expect(wrapper.find('a').exists()).toBe(false);
  });
  it('does not open unready or expired attachments', async () => {
    api.get.mockResolvedValue({ id: '9', status: 'processing' }); part(); await wrapper.vm.open(false); expect(api.access).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('attachments.notReady');
  });
});
describe('shared attachment composer', () => {
  function composer(rows = []) {
    wrapper = mount(AttachmentComposer, { ...options, props: { conversationId: 'c', identity: 'account', modelValue: rows, 'onUpdate:modelValue': rows => wrapper.setProps({ modelValue: rows }) } }); return wrapper;
  }
  it('uploads a user-selected file through the reactive v-model and reaches ready without proxy identity races', async () => {
    vi.useRealTimers(); vi.stubGlobal('crypto', webcrypto);
    const file = new File(['a'], '现场.png', { type: 'image/png' });
    Object.defineProperty(file, 'arrayBuffer', { value: async () => new TextEncoder().encode('a').buffer });
    api.init.mockResolvedValue({ id: '9', status: 'pending_upload' });
    api.upload.mockResolvedValue({ id: '9', status: 'uploaded' }); api.complete.mockResolvedValue({ id: '9', status: 'ready' });
    composer(); await wrapper.vm.choose({ target: { files: [file], value: 'selected' } });
    await vi.waitFor(() => expect(wrapper.props('modelValue')[0].status).toBe('ready'));
    expect(api.init).toHaveBeenCalledOnce(); expect(api.upload.mock.calls[0][1]).toBe(file); expect(api.complete).toHaveBeenCalledOnce();
    expect(wrapper.props('modelValue')[0].status).toBe('ready'); expect(wrapper.vm.ready).toBe(true); expect(wrapper.text()).toContain('现场.png');
  });
  it('rejects unsupported files before any network request and retains valid drafts', async () => {
    const file = new File(['<script>bad</script>'], 'bad.svg', { type: 'image/svg+xml' }); composer();
    await wrapper.vm.choose({ target: { files: [file], value: 'selected' } }); expect(wrapper.text()).toContain('attachments.type'); expect(api.init).not.toHaveBeenCalled();
  });
  it('keeps attachment order explicit and removes a failed row without altering other drafts', async () => {
    const rows = [{ file: new File(['1'], 'one.png', { type: 'image/png' }), clientUploadId: 'one', status: 'failed', progress: 0, error: 'attachments.failed' }, { file: new File(['2'], 'two.png', { type: 'image/png' }), clientUploadId: 'two', status: 'paused', progress: 0 }];
    composer(rows); wrapper.vm.move(1, -1); await flushPromises(); expect(wrapper.props('modelValue').map(row => row.clientUploadId)).toEqual(['two','one']);
    wrapper.vm.remove(rows[0]); await flushPromises(); expect(wrapper.props('modelValue').map(row => row.clientUploadId)).toEqual(['two']);
  });
  it('pauses ongoing work on conversation change and preserves the previous attachment draft', async () => {
    const waiting = deferred(), row = { file: new File(['a'], 'a.png', { type: 'image/png' }), clientUploadId: 'one', status: 'paused', sha256: 'a'.repeat(64), progress: 0 };
    composer([row]); api.init.mockReturnValue(waiting.promise); const pending = wrapper.vm.controller.run(row); await flushPromises();
    const signal = api.init.mock.calls[0][1]; await wrapper.setProps({ conversationId: 'second', modelValue: [] });
    expect(signal.aborted).toBe(true); expect(row.status).toBe('paused'); waiting.resolve({ id: '9', status: 'ready' }); await pending; expect(wrapper.props('modelValue')).toEqual([]);
  });
  it('reports disabled services and keeps failed attachments available for removal', async () => {
    const row = { file: new File(['a'], 'a.png', { type: 'image/png' }), clientUploadId: 'one', status: 'paused', sha256: 'a'.repeat(64), progress: 0 };
    composer([row]); api.init.mockRejectedValue({ status: 404 }); await wrapper.vm.controller.run(row); await flushPromises();
    expect(wrapper.vm.unavailable).toBe(true); expect(wrapper.find('input').element.disabled).toBe(true); expect(wrapper.text()).toContain('attachments.unavailable'); expect(wrapper.props('modelValue')).toHaveLength(1);
  });
});

describe('B4 attachment provenance actions', () => {
  it('emits annotation actions only for writable message media and traces IDs from server metadata', async () => {
    api.get.mockResolvedValue({id:'9223372036854775806',filename:'render.jpg',status:'ready',jobs:[],metadata:{annotation_id:'12',frame_id:'10'}});
    part();await wrapper.setProps({canAnnotate:true});const annotate=wrapper.findAll('button').find(button => button.text()==='annotations.annotate');await annotate.trigger('click');expect(wrapper.emitted('annotate')[0][0]).toEqual({attachmentId:'9223372036854775806'});
    expect(wrapper.text()).not.toContain('annotations.sourceScene');await wrapper.vm.open(false);const provenance=wrapper.findAll('button').find(button => button.text()==='annotations.sourceScene');await provenance.trigger('click');expect(wrapper.emitted('snapshot')[0][0]).toBe('12');
    await wrapper.setProps({canAnnotate:false});expect(wrapper.findAll('button').some(button => button.text()==='annotations.annotate')).toBe(false);
  });
});
