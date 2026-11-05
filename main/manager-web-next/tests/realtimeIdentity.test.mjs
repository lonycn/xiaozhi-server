import {mount,flushPromises} from '@vue/test-utils';
import {defineComponent,nextTick} from 'vue';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import Workspace from '../src/views/ConversationWorkspace.vue';
import store from '../src/store';
import {realtimeAuthorityKey} from '../src/realtime/identity.mjs';
const api=vi.hoisted(()=>({list:vi.fn()}));
vi.mock('../src/apis/module/conversation',()=>({default:api}));
const stop=vi.fn(),overlay=defineComponent({template:'<div/>',methods:{stopLocalForIdentity(){stop();}}});
let wrapper;
beforeEach(async()=>{vi.useFakeTimers();vi.clearAllMocks();store.commit('clearAuth');store.commit('setUserInfo',{id:'fixture-user',issuer:'fixture-source',external_subject:'subject',tenant:{type:'company',id:'1'}});store.commit('setToken',{token:'fixture-only-token-A',expire:300,_localStoredAt:Date.now()});api.list.mockResolvedValue({items:[],has_more:false,next_cursor:'0'});wrapper=mount(Workspace,{global:{mocks:{$t:key=>key},stubs:{AIRealtimeOverlay:overlay,AttachmentComposer:true,ToolResults:true,AgentToolStatus:true,AnnotationEditor:true,MediaSessionOverlay:true,RouterLink:true,ElDialog:true,ElInput:true,ElButton:true}}});await flushPromises();wrapper.vm.selected={id:'c',state:'AI_ACTIVE'};wrapper.vm.drafts={c:'private draft'};wrapper.vm.pending={c:{clientId:'stable'}};wrapper.vm.realtimeState={session:{id:'rt',status:'active'}};await nextTick();});
afterEach(()=>{wrapper?.unmount();wrapper=null;store.commit('clearAuth');vi.useRealTimers();});
describe('A5 actual Bearer and source lifecycle isolation',()=>{
 it('keeps capture owner, history selection and drafts for the same Bearer expiry-only renewal',async()=>{const key=realtimeAuthorityKey(store.state),scope=wrapper.vm.attachmentScope;store.commit('setToken',{token:'fixture-only-token-A',expire:600,_localStoredAt:Date.now()+1});expect(realtimeAuthorityKey(store.state)).toBe(key);expect(stop).not.toHaveBeenCalled();await nextTick();expect(wrapper.vm.attachmentScope).toBe(scope);expect(wrapper.vm.selected.id).toBe('c');expect(wrapper.vm.draft).toBe('private draft');expect(wrapper.vm.pending.c.clientId).toBe('stable');});
 it('synchronously stops old media on a new Bearer for the same actor and clears old private scope',async()=>{const scope=wrapper.vm.attachmentScope;store.commit('setToken',{token:'fixture-only-token-B',expire:300,_localStoredAt:Date.now()});expect(stop).toHaveBeenCalledOnce();await nextTick();expect(wrapper.vm.selected).toBeNull();expect(wrapper.vm.drafts).toEqual({});expect(wrapper.vm.pending).toEqual({});expect(wrapper.vm.attachmentScope).not.toBe(scope);expect(wrapper.vm.attachmentScope).not.toContain('fixture-only-token');});
 it('stops on actual source/tenant binding changes without treating display metadata as identity',async()=>{const original=store.state.userInfo,key=realtimeAuthorityKey(store.state);store.commit('setUserInfo',{...original,display_name:'renamed'});expect(realtimeAuthorityKey(store.state)).toBe(key);expect(stop).not.toHaveBeenCalled();store.commit('setUserInfo',{...original,issuer:'different-source',tenant:{type:'company',id:'2'}});expect(stop).toHaveBeenCalledOnce();await nextTick();expect(wrapper.vm.drafts).toEqual({});expect(wrapper.vm.selected).toBeNull();});
});
