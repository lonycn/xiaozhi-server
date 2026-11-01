import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import ExpertContactList from '../src/components/expert/ExpertContactList.vue';
import ExpertWorkspaceLayout from '../src/components/expert/ExpertWorkspaceLayout.vue';
import ExpertAnnotationTools from '../src/components/expert/ExpertAnnotationTools.vue';
const global = { mocks: { $t: key => key } };
describe('expert customer access', () => {
  it('searches real request identifiers and titles, filters statuses and preserves selected request', async () => {
    const assigned = [{ request_id:'a', conversation_id:'a1', status:'active' }, { request_id:'b', conversation_id:'b1', status:'resolved' }];
    const queue = [{ request_id:'q', conversation_id:'q1', status:'queued' }];
    const wrapper = mount(ExpertContactList, { props:{ assigned,queue,titles:{ a1:'冷库压力检查', b1:'压缩机维修' },selectedId:'a',accepting:false },global });
    expect(wrapper.findAll('.contact')).toHaveLength(3);
    expect(wrapper.find('.contact.selected strong').text()).toBe('冷库压力检查');
    await wrapper.find('input').setValue('压力'); expect(wrapper.findAll('.contact')).toHaveLength(1);
    await wrapper.find('input').setValue('q1'); expect(wrapper.findAll('.contact')).toHaveLength(1);
    expect(wrapper.find('.claim').attributes('disabled')).toBeDefined();
    await wrapper.setProps({ accepting:true }); await wrapper.find('.claim').trigger('click'); expect(wrapper.emitted('claim')[0][0]).toEqual(queue[0]);
    await wrapper.find('input').setValue(''); await wrapper.findAll('.filters button')[3].trigger('click');
    expect(wrapper.findAll('.contact')).toHaveLength(1); expect(wrapper.find('.contact strong').text()).toBe('压缩机维修');
    await wrapper.find('.contact-main').trigger('click'); expect(wrapper.emitted('select')[0][0]).toEqual(assigned[1]); wrapper.unmount();
  });
  it('shows a search empty state without changing source lists', async () => {
    const request={ request_id:'r',conversation_id:'c',status:'active' };
    const wrapper=mount(ExpertContactList,{ props:{assigned:[request]},global });
    await wrapper.find('input').setValue('not-found'); expect(wrapper.text()).toContain('expertWorkspace.noMatches'); expect(wrapper.props('assigned')).toEqual([request]); wrapper.unmount();
  });
});
describe('expert collaboration layout', () => {
  it('keeps media and tools mounted through responsive panel toggles and restores toggle focus', async () => {
    const mounted=vi.fn(),unmounted=vi.fn();
    const Media=defineComponent({ mounted,unmounted,render:()=>h('video',{'data-live-media':''}) });
    const wrapper=mount(ExpertWorkspaceLayout,{ attachTo:document.body,global,slots:{ default:()=>h(Media),tools:'saved annotation',contacts:'real requests' } });
    const video=wrapper.find('video').element;
    await wrapper.findAll('.mobile-panels button')[1].trigger('click'); expect(wrapper.find('.expert-tools').classes()).toContain('panel-open');
    await wrapper.trigger('keydown',{ key:'Escape' }); expect(wrapper.find('.expert-tools').classes()).not.toContain('panel-open');
    expect(document.activeElement).toBe(wrapper.findAll('.mobile-panels button')[1].element);
    await wrapper.findAll('.mobile-panels button')[0].trigger('click'); await wrapper.find('.expert-clients>.panel-close').trigger('click');
    expect(wrapper.find('video').element).toBe(video); expect(mounted).toHaveBeenCalledTimes(1); expect(unmounted).not.toHaveBeenCalled(); wrapper.unmount();
  });
  it('requires explicit add for saved annotations and blocks duplicate or read-only staging', async () => {
    const row={attachment:{id:'13',filename:'标注.jpg',metadata:{annotation_id:'12'}}};
    const wrapper=mount(ExpertAnnotationTools,{props:{ items:[{row,sent:false}],writable:true },global});
    expect(wrapper.emitted('add')).toBeUndefined(); await wrapper.findAll('li button')[1].trigger('click'); expect(wrapper.emitted('add')[0][0]).toEqual(row);
    await wrapper.setProps({draftRows:[row]}); expect(wrapper.findAll('li button')[1].attributes('disabled')).toBeDefined();
    await wrapper.setProps({draftRows:[],writable:false}); expect(wrapper.findAll('li button')[1].attributes('disabled')).toBeDefined(); wrapper.unmount();
  });
});
