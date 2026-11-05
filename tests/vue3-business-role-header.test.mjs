import { afterEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus, { ElCascader } from 'element-plus';
import { nextTick } from 'vue';
import RoleConfig from '../src/views/roleConfig.vue';
import HeaderBar from '../src/components/HeaderBar.vue';
import ChangePasswordDialog from '../src/components/ChangePasswordDialog.vue';
const mocks=vi.hoisted(()=>({logout:vi.fn().mockResolvedValue(),changeLanguage:vi.fn()}));
vi.mock('vuex',()=>({mapState:()=>({userInfo:()=>({username:'Tester'}),featureStatus:()=>({})}),mapActions:()=>({logout:mocks.logout})}));
vi.mock('../src/i18n',()=>({default:{t:k=>k,global:{locale:'en'}},changeLanguage:mocks.changeLanguage}));
vi.mock('../src/utils/featureManager',()=>({default:{waitForInitialization:()=>Promise.resolve(),getConfig:()=>({})}}));
vi.mock('../src/apis/api',()=>({default:{
 agent:{getDeviceConfig:(_id,cb)=>cb({data:{code:0,data:{agentName:'Real loaded agent',systemPrompt:'Prompt loaded from API',functions:[],contextProviders:[],correctWordFileIds:[]}}}),getAgentTags:(_id,cb)=>cb({data:{code:0,data:[]}}),getAgentTemplate:cb=>cb({data:{code:0,data:[]}})},
 model:{getModelNames:(_type,_query,cb)=>cb({data:{code:0,data:[]}}),getLlmModelCodeList:(_query,cb)=>cb({data:{code:0,data:[]}}),getPluginFunctionList:(_query,cb)=>cb({data:{code:0,data:[]}})}
}}));
const wrappers=[];afterEach(()=>{wrappers.splice(0).forEach(w=>w.unmount());document.body.innerHTML='';vi.restoreAllMocks();});
const globalOptions={plugins:[ElementPlus],mocks:{$t:k=>k,$i18n:{locale:'en'},$router:{push:vi.fn()},$route:{query:{agentId:'agent-1'}},$message:{success:vi.fn(),error:vi.fn()}}};
describe('role configuration and account menu regressions',()=>{
 it('mounts the role form, loads config and edits prompts without warnings or recursive updates',async()=>{
  const warnings=[];const errors=[];
  const wrapper=mount(RoleConfig,{global:{...globalOptions,config:{warnHandler:message=>warnings.push(message),errorHandler:error=>errors.push(error)},stubs:{HeaderBar:true,VersionFooter:true,FunctionDialog:true,ContextProviderDialog:true,TtsAdvancedSettings:true,AgentSnapshotDialog:true}}});wrappers.push(wrapper);
  await flushPromises();await nextTick();
  expect(wrapper.vm.form.agentName).toBe('Real loaded agent');
  expect(wrapper.findAll('textarea')).toHaveLength(2);
  await wrapper.findAll('textarea')[0].setValue('Edited prompt');await flushPromises();
  expect(wrapper.vm.form.systemPrompt).toBe('Edited prompt');expect(warnings).toEqual([]);expect(errors).toEqual([]);
 });
 it('opens the avatar menu through the public cascader API and resets its controlled selection',async()=>{
  const warnings=[];const wrapper=mount(HeaderBar,{attachTo:document.body,global:{...globalOptions,config:{warnHandler:message=>warnings.push(message)}}});wrappers.push(wrapper);
  await flushPromises();await wrapper.get('.avatar-img').trigger('click');await flushPromises();
  expect(wrapper.vm.userMenuVisible).toBe(true);
  const cascader=wrapper.getComponent(ElCascader);cascader.vm.$emit('update:modelValue',['changePassword']);cascader.vm.$emit('change',['changePassword']);await flushPromises();
  expect(wrapper.vm.isChangePasswordDialogVisible).toBe(true);expect(wrapper.vm.userMenuValue).toEqual([]);expect(wrapper.vm.userMenuVisible).toBe(false);
  expect(wrapper.getComponent(ChangePasswordDialog).props('modelValue')).toBe(true);
  expect(warnings.filter(w=>w.includes('Missing required prop')||w.includes('Invalid prop'))).toEqual([]);
 });
 it('synchronizes password-dialog dismissal through the Vue 3 model contract',async()=>{
  const wrapper=mount(ChangePasswordDialog,{props:{modelValue:true},global:globalOptions});wrappers.push(wrapper);await flushPromises();
  wrapper.vm.dialogVisible=false;await nextTick();expect(wrapper.emitted('update:modelValue')).toEqual([[false]]);
 });
});
