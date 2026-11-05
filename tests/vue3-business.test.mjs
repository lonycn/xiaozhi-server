import { afterEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus, { ElButton, ElDialog, ElPagination } from 'element-plus';
import { nextTick } from 'vue';
import CustomButton from '../src/components/CustomButton.vue';
import CustomDialog from '../src/components/CustomDialog.vue';
import CustomTable from '../src/components/CustomTable.vue';
import CustomPagination from '../src/components/CustomPagination.vue';
import AudioPlayer from '../src/components/AudioPlayer.vue';
import ModelEditDialog from '../src/components/ModelEditDialog.vue';
vi.mock('../src/apis/api', () => ({ default: { model: { getModelProviders: vi.fn() } } }));
const wrappers=[];
const create=(component,options={})=>{const w=mount(component,{global:{plugins:[ElementPlus],mocks:{$t:key=>key}},...options});wrappers.push(w);return w;};
afterEach(()=>{wrappers.splice(0).forEach(w=>w.unmount());document.body.innerHTML='';vi.restoreAllMocks();});
describe('Vue 3 business interactions',()=>{
 it('forwards one button click and renders its SVG icon',async()=>{
  const onClick=vi.fn();const w=create(CustomButton,{props:{icon:'el-icon-plus',type:'add',onClick},slots:{default:'Add'}});
  await w.get('button').trigger('click');expect(onClick).toHaveBeenCalledTimes(1);expect(w.find('svg').exists()).toBe(true);
 });
 it('renders custom table cell and operation slots and toggles a reactive checkbox',async()=>{
  const row={id:'one',name:'Agent',selected:false};const w=create(CustomTable,{props:{data:[row],columns:[{prop:'name'}],showSelection:true,showOperations:true,showPagination:false},slots:{name:({row})=>`Name: ${row.name}`,operations:({row})=>`Edit ${row.id}`}});
  await flushPromises();expect(w.text()).toContain('Name: Agent');expect(w.text()).toContain('Edit one');
  await w.get('input[type="checkbox"]').setValue(true);expect(row.selected).toBe(true);
 });
 it('handles Element Plus controlled pagination updates',async()=>{
  const w=create(CustomPagination,{props:{total:100,currentPage:2,pageSize:10}});const pagination=w.getComponent(ElPagination);
  pagination.vm.$emit('update:current-page',3);pagination.vm.$emit('update:page-size',20);await nextTick();
  expect(w.emitted('page-change')).toEqual([[3]]);expect(w.emitted('size-change')).toEqual([[20]]);
 });
 it('renders a real dialog header/footer and closes with update:visible',async()=>{
  const w=create(CustomDialog,{attachTo:document.body,props:{visible:true,title:'Agent settings',cancelText:'Cancel',confirmText:'Save'},slots:{default:'Settings form'}});
  await flushPromises();expect(document.body.textContent).toContain('Agent settings');expect(document.body.textContent).toContain('Settings form');
  const cancel=w.findAllComponents(CustomButton).find(b=>b.text()==='Cancel');await cancel.get('button').trigger('click');
  expect(w.emitted('cancel')).toHaveLength(1);expect(w.emitted('update:visible')?.[0]).toEqual([false]);
 });
 it('renders editable dictionary controls inside the model form',async()=>{
  const w=create(ModelEditDialog,{props:{modelType:'TTS'},global:{plugins:[ElementPlus],mocks:{$t:key=>key},stubs:{CustomDialog:{template:'<div><slot /></div>'}}}});
  w.vm.dynamicCallInfoFields=[{prop:'settings',label:'Settings',type:'json-textarea'}];w.vm.form.configJson={settings:{speed:1}};w.vm.fieldJsonMap={settings:'{"speed":1}'};
  await nextTick();expect(w.find('textarea').exists()).toBe(true);expect(w.text()).toContain('modelConfigDialog.callInfo');
 });
 it('handles rejected playback and releases audio, drag listeners and timers on unmount',async()=>{
  vi.spyOn(HTMLMediaElement.prototype,'play').mockRejectedValue(new Error('denied'));const pause=vi.spyOn(HTMLMediaElement.prototype,'pause').mockImplementation(()=>{});
  const remove=vi.spyOn(document,'removeEventListener');const w=create(AudioPlayer,{props:{audioUrl:'/sound.wav'}});
  await w.get('.play-btn').trigger('click');await flushPromises();expect(w.vm.isPlaying).toBe(false);
  await w.get('.progress-thumb').trigger('mousedown');w.unmount();wrappers.splice(wrappers.indexOf(w),1);
  expect(pause).toHaveBeenCalled();expect(remove.mock.calls.some(([event])=>event==='mousemove')).toBe(true);expect(remove.mock.calls.some(([event])=>event==='mouseup')).toBe(true);
 });
});
