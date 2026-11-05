import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import ExpertCollaborationPane from '../src/components/expert/ExpertCollaborationPane.vue';
import ExpertWorkspaceLayout from '../src/components/expert/ExpertWorkspaceLayout.vue';
const global = { mocks: { $t: key => key } };
const videoState = { visible: true, active: true, hasVideo: true, portrait: true, ratio: 9 / 16 };

describe('persistent portrait collaboration pane', () => {
  it('clamps keyboard resizing to portrait width and preserves at least 400px for chat where available', async () => {
    const wrapper = mount(ExpertCollaborationPane, { props: { mediaState: videoState }, global });
    wrapper.vm.width = 1000; await wrapper.vm.$nextTick();
    const splitter = wrapper.find('[role="separator"]');
    expect(splitter.attributes('aria-orientation')).toBe('vertical');
    await splitter.trigger('keydown', { key: 'ArrowRight' }); expect(wrapper.vm.videoWidth).toBe(380);
    await splitter.trigger('keydown', { key: 'End' }); expect(wrapper.vm.videoWidth).toBe(460);
    await splitter.trigger('keydown', { key: 'ArrowRight' }); expect(wrapper.vm.videoWidth).toBe(460);
    wrapper.vm.width = 760; await wrapper.vm.$nextTick(); expect(wrapper.vm.videoWidth).toBe(348);
    expect(760 - wrapper.vm.videoWidth - 12).toBe(400);
    await splitter.trigger('keydown', { key: 'Home' }); expect(wrapper.vm.videoWidth).toBe(300);
    await splitter.trigger('keydown', { key: 'ArrowLeft' }); expect(wrapper.vm.videoWidth).toBe(300);
    wrapper.unmount();
  });

  it('fits the initial portrait width to height and preserves a manual width across track metadata and resize changes', async () => {
    const wrapper = mount(ExpertCollaborationPane, { props: { mediaState: videoState }, global, slots: { media: '<video />' } });
    const video = wrapper.find('video').element;
    wrapper.vm.width = 1000; wrapper.vm.height = 900; await wrapper.vm.$nextTick();
    expect(wrapper.vm.videoWidth).toBe(416);
    await wrapper.find('[role="separator"]').trigger('keydown', { key: 'Home' });
    await wrapper.find('[role="separator"]').trigger('keydown', { key: 'ArrowRight', shiftKey: true }); expect(wrapper.vm.videoWidth).toBe(340);
    await wrapper.setProps({ mediaState: { ...videoState, ratio: 16 / 9, portrait: false } });
    wrapper.vm.height = 640; await wrapper.vm.$nextTick(); expect(wrapper.vm.videoWidth).toBe(340);
    wrapper.vm.width = 711; await wrapper.vm.$nextTick(); expect(wrapper.classes()).toContain('is-stacked');
    wrapper.vm.width = 1000; await wrapper.vm.$nextTick(); expect(wrapper.vm.videoWidth).toBe(340);
    expect(wrapper.find('video').element).toBe(video); wrapper.unmount();
  });

  it('drags the splitter with pointer capture and cancels drag when space requires stacking', async () => {
    const wrapper = mount(ExpertCollaborationPane, { props: { mediaState: videoState }, global });
    wrapper.vm.width = 1000; await wrapper.vm.$nextTick();
    const splitter = wrapper.find('[role="separator"]'), target = splitter.element;
    target.setPointerCapture = vi.fn(); target.releasePointerCapture = vi.fn();
    const event = { pointerId: 4, button: 0, clientX: 300, currentTarget: target, preventDefault: vi.fn() };
    wrapper.vm.startResize(event); expect(target.setPointerCapture).toHaveBeenCalledWith(4);
    wrapper.vm.moveResize({ ...event, clientX: 380 }); expect(wrapper.vm.videoWidth).toBe(440);
    wrapper.vm.endResize({ ...event, clientX: 900 }); expect(wrapper.vm.videoWidth).toBe(460); expect(wrapper.vm.drag).toBeNull();
    expect(target.releasePointerCapture).toHaveBeenCalledWith(4);
    wrapper.vm.startResize(event);
    vi.spyOn(wrapper.element, 'getBoundingClientRect').mockReturnValue({ width: 620 }); wrapper.vm.measure();
    await wrapper.vm.$nextTick(); expect(wrapper.classes()).toContain('is-stacked'); expect(wrapper.vm.drag).toBeNull();
    expect(splitter.isVisible()).toBe(false);
    wrapper.unmount();
  });

  it('keeps media, editor, composer and attachment nodes through video/idle/stacked modes and drawer changes', async () => {
    const mounted = vi.fn(), unmounted = vi.fn();
    const Media = defineComponent({ mounted, unmounted, render: () => h('video', { 'data-live-media': '' }) });
    const Editor = defineComponent({ mounted, unmounted, render: () => h('canvas', { 'data-editor': '' }) });
    const wrapper = mount(ExpertWorkspaceLayout, { attachTo: document.body, props: { collaboration: true, mediaState: videoState }, global,
      slots: { media: () => h(Media), annotation: () => h(Editor), default: '<div data-chat>message</div>', reply: '<div><input value="unsent draft"><div data-attachment>pending upload</div></div>', tools: 'tools', contacts: 'customers' } });
    const pane = wrapper.findComponent(ExpertCollaborationPane);
    wrapper.vm.layoutWidth = 1400; pane.vm.width = 1150; await wrapper.vm.$nextTick();
    const nodes = ['video', 'canvas', 'input', '[data-attachment]'].map(selector => wrapper.find(selector).element);
    await wrapper.findAll('.mobile-panels button')[0].trigger('click'); expect(wrapper.classes()).toContain('clients-collapsed');
    expect(wrapper.findAll('.mobile-panels button')[0].attributes('aria-expanded')).toBe('false');
    expect(wrapper.classes()).toContain('tools-docked');
    wrapper.vm.layoutWidth = 900; await wrapper.vm.$nextTick();
    await wrapper.find('.tools-rail-toggle').trigger('click'); expect(document.activeElement).toBe(wrapper.find('.expert-tools .panel-close').element);
    await wrapper.trigger('keydown', { key: 'Escape' }); expect(document.activeElement).toBe(wrapper.find('.tools-rail-toggle').element);
    wrapper.vm.layoutWidth = 620; pane.vm.width = 620; await wrapper.vm.$nextTick();
    await wrapper.findAll('.mobile-panels button')[0].trigger('click'); expect(wrapper.find('.expert-clients').classes()).toContain('panel-open');
    await wrapper.setProps({ mediaState: { visible: true, active: false, preparing: true, hasVideo: false } }); expect(pane.classes()).toContain('is-preparing');
    await wrapper.setProps({ mediaState: { active: true, hasVideo: false } }); expect(pane.classes()).not.toContain('has-video');
    expect(pane.classes()).not.toContain('is-preparing');
    await wrapper.setProps({ mediaState: videoState }); expect(pane.classes()).toContain('is-stacked');
    wrapper.vm.layoutWidth = 1400; pane.vm.width = 1150; await wrapper.vm.$nextTick(); expect(pane.classes()).not.toContain('is-stacked');
    ['video', 'canvas', 'input', '[data-attachment]'].forEach((selector, index) => expect(wrapper.find(selector).element).toBe(nodes[index]));
    expect(wrapper.find('input').element.value).toBe('unsent draft'); expect(mounted).toHaveBeenCalledTimes(2); expect(unmounted).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('docks tools when space permits and restores a collapsed right rail after shrinking', async () => {
    const wrapper = mount(ExpertWorkspaceLayout, { attachTo: document.body, props: { collaboration: true, mediaState: videoState }, global,
      slots: { tools: '<input data-tool-draft value="retained note" />' } });
    const draft = wrapper.find('[data-tool-draft]').element;
    wrapper.vm.layoutWidth = 1440; await wrapper.vm.$nextTick();
    expect(wrapper.vm.toolsVisible).toBe(true); expect(wrapper.classes()).toContain('tools-docked');
    expect(wrapper.find('.tools-rail-toggle').exists()).toBe(false);
    await wrapper.trigger('keydown', { key: 'Escape' }); expect(wrapper.vm.toolsVisible).toBe(true);
    draft.focus(); wrapper.vm.layoutWidth = 1120; await wrapper.vm.$nextTick(); await wrapper.vm.$nextTick();
    expect(wrapper.vm.toolsVisible).toBe(false); expect(wrapper.find('.tools-rail-toggle').attributes('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(wrapper.find('.tools-rail-toggle').element);
    await wrapper.find('.tools-rail-toggle').trigger('click'); expect(wrapper.vm.toolsVisible).toBe(true);
    expect(wrapper.find('.tools-rail-toggle').attributes('aria-expanded')).toBe('true');
    wrapper.vm.layoutWidth = 1440; await wrapper.vm.$nextTick();
    expect(wrapper.vm.toolsOpen).toBe(false); expect(wrapper.vm.toolsVisible).toBe(true);
    wrapper.vm.layoutWidth = 1120; await wrapper.vm.$nextTick(); expect(wrapper.vm.toolsVisible).toBe(false);
    expect(wrapper.find('[data-tool-draft]').element).toBe(draft); expect(draft.value).toBe('retained note');
    await wrapper.findAll('.mobile-panels button')[0].trigger('click');
    expect(wrapper.classes()).toContain('tools-docked');
    wrapper.unmount();
  });

  it('reserves extra room for video and keeps tools docked for text when they still fit', async () => {
    const wrapper = mount(ExpertWorkspaceLayout, { props: { collaboration: true }, global });
    wrapper.vm.layoutWidth = 1120; await wrapper.vm.$nextTick(); expect(wrapper.vm.toolsDocked).toBe(true);
    await wrapper.setProps({ mediaState: videoState }); expect(wrapper.vm.toolsDocked).toBe(false);
    wrapper.vm.layoutWidth = 390; await wrapper.vm.$nextTick();
    await wrapper.find('.mobile-panels button').trigger('click'); expect(wrapper.vm.clientsOpen).toBe(true);
    await wrapper.find('.tools-rail-toggle').trigger('click'); expect(wrapper.vm.toolsVisible).toBe(true); expect(wrapper.vm.clientsOpen).toBe(false);
    await wrapper.find('.expert-tools .panel-close').trigger('click'); expect(wrapper.vm.toolsVisible).toBe(false);
    wrapper.unmount();
  });

  it('propagates editing visibility without remounting the editor or the compact transcript', async () => {
    const wrapper = mount(ExpertWorkspaceLayout, { props: { collaboration: true, mediaState: videoState }, global,
      slots: { annotation: '<canvas data-editor />', default: '<div data-transcript>retained messages</div>', reply: '<input value="draft" />' } });
    const pane = wrapper.findComponent(ExpertCollaborationPane), editor = wrapper.find('canvas').element, transcript = wrapper.find('[data-transcript]').element;
    pane.vm.width = 390; pane.vm.height = 500; await wrapper.setProps({ annotationVisible: true });
    expect(pane.props('annotationVisible')).toBe(true); expect(pane.classes()).toContain('has-annotation'); expect(pane.classes()).toContain('is-stacked');
    await wrapper.setProps({ annotationVisible: false }); expect(pane.classes()).not.toContain('has-annotation');
    expect(wrapper.find('canvas').element).toBe(editor); expect(wrapper.find('[data-transcript]').element).toBe(transcript); expect(wrapper.find('input').element.value).toBe('draft');
    wrapper.unmount();
  });

  it('collapses idle media during editing and restores pending/audio/video calls without replacing nodes', async () => {
    const wrapper = mount(ExpertCollaborationPane, { props: { mediaState: { active: false, hasVideo: false } }, global,
      slots: { media: '<video data-persistent-media />', annotation: '<canvas />', reply: '<input value="retained draft" />' } });
    wrapper.vm.width = 390; await wrapper.vm.$nextTick();
    const media = wrapper.find('video').element, editor = wrapper.find('canvas').element, draft = wrapper.find('input').element;
    expect(wrapper.classes()).not.toContain('has-call'); expect(wrapper.classes()).not.toContain('has-annotation');
    await wrapper.setProps({ annotationVisible: true }); expect(wrapper.classes()).toContain('has-annotation'); expect(wrapper.classes()).not.toContain('has-call');
    await wrapper.setProps({ mediaState: { active: true, hasVideo: false } }); expect(wrapper.classes()).toContain('has-call'); expect(wrapper.classes()).not.toContain('has-video');
    await wrapper.setProps({ mediaState: videoState }); expect(wrapper.classes()).toContain('has-call'); expect(wrapper.classes()).toContain('has-video');
    expect(wrapper.find('video').element).toBe(media); expect(wrapper.find('canvas').element).toBe(editor); expect(wrapper.find('input').element).toBe(draft); expect(draft.value).toBe('retained draft');
    wrapper.unmount();
  });

  it('splits preparation and audio before video arrives, retaining width and all session/editor/draft nodes through track and expiry changes', async () => {
    const mounted = vi.fn(), unmounted = vi.fn();
    const Media = defineComponent({ mounted, unmounted, render: () => h('video') });
    const wrapper = mount(ExpertCollaborationPane, { attachTo: document.body, props: { annotationVisible: true, mediaState: { visible: false, active: false, hasVideo: false } }, global,
      slots: { media: () => h(Media), annotation: '<canvas />', reply: '<input value="unsent reply" />' } });
    wrapper.vm.width = 1000; await wrapper.vm.$nextTick();
    const media = wrapper.find('video').element, editor = wrapper.find('canvas').element, draft = wrapper.find('input').element;
    const splitter = wrapper.find('[role="separator"]');
    expect(wrapper.classes()).not.toContain('has-media'); expect(splitter.isVisible()).toBe(false);
    await wrapper.setProps({ mediaState: { visible: true, active: false, hasVideo: false } });
    expect(wrapper.classes()).toContain('has-media'); expect(wrapper.classes()).not.toContain('has-call'); expect(wrapper.classes()).not.toContain('has-video'); expect(splitter.isVisible()).toBe(true);
    await splitter.trigger('keydown', { key: 'Home' }); await splitter.trigger('keydown', { key: 'ArrowRight', shiftKey: true }); expect(wrapper.vm.videoWidth).toBe(340);
    const target = splitter.element; target.setPointerCapture = vi.fn();
    wrapper.vm.startResize({ pointerId: 2, button: 0, clientX: 300, currentTarget: target, preventDefault: vi.fn() }); expect(wrapper.vm.drag).not.toBeNull();
    for (const state of [{ visible: true, active: true, hasVideo: false }, videoState, { visible: true, active: true, hasVideo: false, ratio: 0 }]) {
      await wrapper.setProps({ mediaState: state }); expect(wrapper.classes()).toContain('has-media'); expect(splitter.isVisible()).toBe(true); expect(wrapper.vm.videoWidth).toBe(340);
    }
    // A terminal session can retain old track metadata; explicit visible:false still collapses it.
    await wrapper.setProps({ mediaState: { visible: false, active: false, hasVideo: true } });
    expect(wrapper.classes()).not.toContain('has-media'); expect(splitter.isVisible()).toBe(false); expect(wrapper.vm.drag).toBeNull();
    wrapper.vm.width = 390; await wrapper.vm.$nextTick(); expect(wrapper.classes()).toContain('is-stacked'); expect(wrapper.classes()).toContain('has-annotation'); expect(wrapper.classes()).not.toContain('has-media');
    await wrapper.setProps({ mediaState: { visible: true, active: false, hasVideo: false } }); expect(wrapper.classes()).toContain('has-media');
    wrapper.vm.width = 1000; await wrapper.vm.$nextTick(); expect(wrapper.vm.videoWidth).toBe(340);
    expect(wrapper.find('video').element).toBe(media); expect(wrapper.find('canvas').element).toBe(editor); expect(wrapper.find('input').element).toBe(draft); expect(draft.value).toBe('unsent reply');
    expect(mounted).toHaveBeenCalledTimes(1); expect(unmounted).not.toHaveBeenCalled(); wrapper.unmount();
  });

  it('reserves the media split for preparation/audio when docking tools and keeps that reservation through camera mute', async () => {
    const wrapper = mount(ExpertWorkspaceLayout, { props: { collaboration: true, mediaState: { active: false, visible: false } }, global, slots: { tools: '<input value="tool note" />' } });
    const draft = wrapper.find('.tools-scroll input').element;
    wrapper.vm.layoutWidth = 1120; await wrapper.vm.$nextTick(); expect(wrapper.vm.toolsDocked).toBe(true);
    for (const state of [{ active: false, visible: true, hasVideo: false }, { active: true, visible: true, hasVideo: false }, videoState, { active: true, visible: true, hasVideo: false }]) {
      await wrapper.setProps({ mediaState: state }); expect(wrapper.vm.toolsDocked).toBe(false); expect(wrapper.find('.tools-rail-toggle').exists()).toBe(true);
    }
    await wrapper.setProps({ mediaState: { active: false, visible: false, hasVideo: true } }); expect(wrapper.vm.toolsDocked).toBe(true);
    // Older callers can omit visible; explicit false takes precedence over stale active/track state.
    await wrapper.setProps({ mediaState: { active: true, hasVideo: false } }); expect(wrapper.vm.toolsDocked).toBe(false);
    await wrapper.setProps({ mediaState: { active: true, visible: false, hasVideo: true } }); expect(wrapper.vm.toolsDocked).toBe(true);
    expect(wrapper.find('.tools-scroll input').element).toBe(draft); expect(draft.value).toBe('tool note'); wrapper.unmount();
  });

  it('keeps the compact customer restore control accessible while desktop rail placement changes without replacing workspace nodes', async () => {
    const wrapper = mount(ExpertWorkspaceLayout, { attachTo: document.body, props: { collaboration: true, mediaState: { visible: true, active: false, hasVideo: false } }, global,
      slots: { contacts: '<input data-client-search value="customer search" />', media: '<video />', annotation: '<canvas />', default: '<div data-messages>message history</div>', reply: '<input data-reply value="draft reply" />', tools: '<input data-tool-draft value="tool draft" />' } });
    wrapper.vm.layoutWidth = 1440; await wrapper.vm.$nextTick();
    const selectors = ['[data-client-search]', 'video', 'canvas', '[data-messages]', '[data-reply]', '[data-tool-draft]'];
    const nodes = selectors.map(selector => wrapper.find(selector).element), toggle = wrapper.find('.mobile-panels button');
    expect(wrapper.classes()).toContain('contacts-docked'); expect(wrapper.vm.toolsDocked).toBe(true); expect(toggle.attributes('aria-controls')).toBe('expert-clients'); expect(toggle.attributes('aria-expanded')).toBe('true');
    toggle.element.focus(); await toggle.trigger('click');
    expect(wrapper.classes()).not.toContain('contacts-docked'); expect(toggle.attributes('aria-expanded')).toBe('false'); expect(document.activeElement).toBe(toggle.element);
    await toggle.trigger('click'); expect(wrapper.classes()).toContain('contacts-docked'); expect(toggle.attributes('aria-expanded')).toBe('true');
    wrapper.vm.layoutWidth = 900; await wrapper.vm.$nextTick(); expect(wrapper.classes()).not.toContain('contacts-docked'); expect(wrapper.vm.toolsDocked).toBe(false);
    await toggle.trigger('click'); expect(wrapper.find('.expert-clients').classes()).toContain('panel-open'); expect(toggle.attributes('aria-expanded')).toBe('true');
    await wrapper.trigger('keydown', { key: 'Escape' }); expect(toggle.attributes('aria-expanded')).toBe('false'); expect(document.activeElement).toBe(toggle.element);
    wrapper.vm.layoutWidth = 1440; await wrapper.vm.$nextTick(); expect(wrapper.classes()).toContain('contacts-docked');
    selectors.forEach((selector, index) => expect(wrapper.find(selector).element).toBe(nodes[index])); expect(wrapper.find('[data-reply]').element.value).toBe('draft reply');
    wrapper.unmount();
  });

  it('leaves the default device conversation layout unchanged', () => {
    const wrapper = mount(ExpertWorkspaceLayout, { global, slots: { default: '<div data-device>device conversation</div>', reply: 'reply' } });
    expect(wrapper.findComponent(ExpertCollaborationPane).exists()).toBe(false);
    expect(wrapper.find('.expert-content [data-device]').exists()).toBe(true);
    expect(wrapper.classes()).not.toContain('expert-layout--collaboration'); wrapper.unmount();
  });
});
