import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Dialog from '../src/components/ModelTestDialog.vue';
import messages from '../src/i18n/modelTestMessages';
const api=vi.hoisted(()=>({startModelTest:vi.fn(),getModelTest:vi.fn(),getModelTestHistory:vi.fn(),getModelTestAudio:vi.fn()}));
vi.mock('../src/apis/module/modelTest',()=>api);
let wrapper;
const run=(status='succeeded')=>({id:'run',status,scope:'inference',summary:'done',durationMs:12,result:{output:'连接成功'},logs:[{elapsedMs:1,stage:'REQUEST',message:'request sent',level:'info'}],createdAt:'2026-11-05T08:00:00Z'});
async function open(type='TTS'){
 wrapper=mount(Dialog,{props:{visible:false,modelId:'model',modelName:'Test',modelType:type,configJson:{type:'alibl_stream',api_key:'masked****key',model:'draft-model'}},global:{plugins:[ElementPlus],mocks:{$t:key=>messages.zh_CN[key]||key},stubs:{ElDialog:{template:'<div><slot/><slot name="footer"/></div>'}}}});
 await wrapper.setProps({visible:true});await flushPromises();return wrapper;
}
beforeEach(()=>{vi.clearAllMocks();api.getModelTestHistory.mockResolvedValue([]);api.startModelTest.mockResolvedValue(run());});
afterEach(()=>{wrapper?.unmount();wrapper=null;vi.useRealTimers();});
describe('model tests',()=>{
 it('opening reads history without making a billed provider call',async()=>{await open();expect(api.getModelTestHistory).toHaveBeenCalledWith('model');expect(api.startModelTest).not.toHaveBeenCalled();});
 it('tests current draft parameters without saving them',async()=>{await open();await wrapper.vm.start();expect(api.startModelTest.mock.calls[0][1].configJson.model).toBe('draft-model');expect(wrapper.text()).toContain('连接成功');expect(wrapper.text()).toContain('request sent');});
 it('shows exact failed provider results without automatic retry',async()=>{api.startModelTest.mockResolvedValue({...run('failed'),errorCode:'InvalidParameter',summary:'Engine error 418'});await open();await wrapper.vm.start();expect(wrapper.text()).toContain('Engine error 418');expect(wrapper.text()).toContain('InvalidParameter');expect(api.startModelTest).toHaveBeenCalledTimes(1);});
 it('polls a running test and stops after terminal completion',async()=>{await open();vi.useFakeTimers();api.startModelTest.mockResolvedValue(run('running'));api.getModelTest.mockResolvedValue(run());await wrapper.vm.start();await vi.advanceTimersByTimeAsync(800);expect(wrapper.vm.run.status).toBe('succeeded');await vi.advanceTimersByTimeAsync(3000);expect(api.getModelTest).toHaveBeenCalledTimes(1);});
 it('ignores completion from a closed dialog',async()=>{await open();let resolve;api.startModelTest.mockReturnValue(new Promise(r=>resolve=r));const promise=wrapper.vm.start();await wrapper.setProps({visible:false});resolve(run());await promise;expect(wrapper.vm.run).toBeNull();});
 it('distinguishes local configuration checks from inference',async()=>{api.startModelTest.mockResolvedValue({...run('checked'),scope:'configuration'});await open('VAD');await wrapper.vm.start();expect(wrapper.text()).toContain('仅配置检查通过');expect(wrapper.find('textarea').exists()).toBe(false);});
 it('offers different sample inputs for ASR and vision',async()=>{await open('ASR');expect(wrapper.text()).toContain('内置普通话样音');expect(wrapper.find('input[type="file"]').attributes('accept')).toContain('.wav');await wrapper.setProps({modelType:'VLLM'});expect(wrapper.text()).toContain('红色圆形图片');});
 it('keeps recent test details selectable',async()=>{api.getModelTestHistory.mockResolvedValue([run('failed')]);await open();await wrapper.get('.history-item').trigger('click');expect(wrapper.vm.run.status).toBe('failed');});
 it('keeps all locale keys available',()=>{const keys=Object.keys(messages.en).sort();for(const locale of Object.values(messages))expect(Object.keys(locale).sort()).toEqual(keys);});
});
