import { afterEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import DeviceItem from '../src/components/DeviceItem.vue';
const wrappers=[];
afterEach(()=>{wrappers.splice(0).forEach(w=>w.unmount());});
const device={agentId:'agent-one',agentName:'Existing agent',llmModelName:'Configured LLM',ttsModelName:'Configured TTS',ttsVoiceName:'Existing voice',deviceCount:2,memModelId:'Memory_long',tags:[{tagName:'Existing tag'}]};
function create(overrides={},featureStatus={voiceprintRecognition:true}){
 const push=vi.fn();const wrapper=mount(DeviceItem,{props:{device:{...device,...overrides},featureStatus},global:{plugins:[ElementPlus],mocks:{$t:key=>key,$router:{push}}}});wrappers.push(wrapper);return{wrapper,push};
}
describe('admin agent row',()=>{
 it('shows real agent metadata and preserves contextual navigation',async()=>{
  const{wrapper,push}=create();
  for(const text of ['Existing agent','Configured LLM','Configured TTS','Existing voice','Existing tag'])expect(wrapper.text()).toContain(text);
  const click=async text=>wrapper.findAll('button').find(b=>b.text()===text).trigger('click');
  await click('home.configureRole');expect(push).toHaveBeenLastCalledWith({path:'/role-config',query:{agentId:'agent-one'}});
  await click('home.deviceManagement');expect(push).toHaveBeenLastCalledWith({path:'/device-management',query:{agentId:'agent-one'}});
  await click('home.voiceprintRecognition');expect(push).toHaveBeenLastCalledWith({path:'/voice-print',query:{agentId:'agent-one'}});
 });
 it('keeps history and guarded deletion actions available',async()=>{
  const{wrapper}=create();await wrapper.findAll('button').find(b=>b.text()==='home.chatHistory').trigger('click');
  expect(wrapper.emitted('chat-history')).toEqual([[{agentId:'agent-one',agentName:'Existing agent'}]]);
  await wrapper.get('button.agent-delete').trigger('click');expect(wrapper.emitted('delete')?.[0][0]).toEqual(device);
 });
 it('disables history when memory is off and honors the voiceprint feature switch',()=>{
  const{wrapper}=create({memModelId:'Memory_nomem'},{voiceprintRecognition:false});
  expect(wrapper.findAll('button').find(b=>b.text()==='home.chatHistory').element.disabled).toBe(true);
  expect(wrapper.findAll('button').some(b=>b.text()==='home.voiceprintRecognition')).toBe(false);
 });
});
