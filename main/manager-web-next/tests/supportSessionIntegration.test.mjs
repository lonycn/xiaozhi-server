import { mount, flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createPinia } from 'pinia';
import { createMemoryHistory, createRouter, RouterView } from 'vue-router';
import { h } from 'vue';
import Layout from '../src/layouts/AdminLayout.vue';
import Workspace from '../src/views/SupportWorkspace.vue';
import { useAuthStore } from '../src/stores/auth';

const mocks=vi.hoisted(()=>({profile:null,queues:{},assigned:{},messages:[],invites:[],media:{invitations:vi.fn()},api:{me:vi.fn(),presence:vi.fn(),queue:vi.fn(),assigned:vi.fn(),get:vi.fn()},conversation:{detail:vi.fn(),messages:vi.fn()},annotation:{list:vi.fn()},notify:vi.fn()}));
vi.mock('../src/apis/module/support',()=>({default:mocks.api}));
vi.mock('../src/apis/module/conversation',()=>({default:mocks.conversation}));
vi.mock('../src/apis/module/media',()=>({default:mocks.media}));
vi.mock('../src/apis/module/annotation',()=>({default:mocks.annotation}));
vi.mock('../src/utils/featureManager',()=>({default:{waitForInitialization:vi.fn().mockResolvedValue()}}));
vi.mock('element-plus',async original=>({...await original(),ElNotification:mocks.notify}));
let wrapper,router;
const page=items=>({items,next_cursor:'0',has_more:false});
const stub={template:'<div><slot/><slot name="title"/><slot name="dropdown"/></div>'};
beforeEach(()=>{
 vi.useFakeTimers();vi.clearAllMocks();
 mocks.profile={user_id:'42',enabled:true,role:'support_agent',roles:['support_agent','expert'],accepting:false,service_presence:{support_agent:{accepting:false},expert:{accepting:false}}};
 mocks.queues={support_agent:[],expert:[]};mocks.assigned={support_agent:[],expert:[]};mocks.messages=[];mocks.invites=[];mocks.media.invitations.mockImplementation(async()=>page(mocks.invites));
 mocks.api.me.mockImplementation(async()=>({...mocks.profile}));
 mocks.api.presence.mockImplementation(async (accepting,role)=>(mocks.profile={...mocks.profile,service_presence:{...mocks.profile.service_presence,[role]:{accepting}},...(role===mocks.profile.role?{accepting}:{})}));
 mocks.api.queue.mockImplementation(async(before,role)=>page(mocks.queues[role]));mocks.api.assigned.mockImplementation(async(before,role)=>page(mocks.assigned[role]));
 mocks.api.get.mockImplementation(async id=>Object.values(mocks.assigned).flat().find(row=>row.request_id===id));
 mocks.conversation.detail.mockResolvedValue({title:'咨询',last_seq:'7'});
 mocks.conversation.messages.mockImplementation(async(id,after)=>{const items=mocks.messages.filter(row=>BigInt(row.seq)>BigInt(after));return{items,next_cursor:items.at(-1)?.seq||String(after),has_more:false};});
 mocks.annotation.list.mockResolvedValue(page([]));mocks.notify.mockImplementation(()=>({close:vi.fn()}));
});
afterEach(()=>{wrapper?.unmount();wrapper=null;vi.useRealTimers();});
async function render(path='/support'){
 router=createRouter({history:createMemoryHistory(),routes:[{path:'/home',component:{template:'<h1>管理首页</h1>'}},{path:'/support',component:Workspace,props:{serviceType:'support_agent'}},{path:'/expert-conversations',component:Workspace,props:{serviceType:'expert'}}]});
 await router.push(path);await router.isReady();
 const pinia=createPinia(),auth=useAuthStore(pinia);auth.token=JSON.stringify({token:'test-internal'});auth.userInfo={id:'42',username:'staff'};
 wrapper=mount(Layout,{slots:{default:()=>h(RouterView)},global:{plugins:[pinia,router],mocks:{$t:(key,values)=>key+(values?':'+values.count:'')},stubs:{
  ElMenu:stub,ElMenuItem:stub,ElMenuItemGroup:stub,ElSubMenu:stub,ElScrollbar:stub,ElBreadcrumb:stub,ElBreadcrumbItem:stub,ElDropdown:stub,ElDropdownMenu:stub,ElDropdownItem:stub,ElIcon:stub,ChangePasswordDialog:true,
  MediaSessionOverlay:true,AnnotationEditor:true,AttachmentComposer:true,AttachmentPart:true,AgentToolStatus:true,SupportSummaryPanel:true,ExpertAnnotationTools:true,HistorySearchDrawer:true,
  ElSwitch:true,ElAlert:true,ElDialog:true,ElInput:true,ElSelect:true,ElOption:true,ElButton:stub,
 }}});
 await flushPromises();
}
it('keeps each business independent through management, desk changes, heartbeat, reload and teardown',async()=>{
 await render();let desk=wrapper.findComponent(Workspace);await desk.vm.updatePresence(true);
 expect(mocks.profile.accepting).toBe(true);
 await router.push('/home');await flushPromises();await vi.advanceTimersByTimeAsync(16000);
 expect(mocks.profile.accepting).toBe(true);expect(wrapper.findComponent(Workspace).exists()).toBe(false);
 await router.push('/expert-conversations');await flushPromises();desk=wrapper.findComponent(Workspace);expect(desk.vm.accepting).toBe(false);
 await desk.vm.updatePresence(true);expect(mocks.profile.service_presence.support_agent.accepting).toBe(true);
 await desk.vm.updatePresence(false);expect(mocks.profile.service_presence.support_agent.accepting).toBe(true);
 await router.push('/support');await flushPromises();expect(wrapper.findComponent(Workspace).vm.accepting).toBe(true);
 const writes=mocks.api.presence.mock.calls.length;wrapper.unmount();wrapper=null;
 expect(mocks.api.presence).toHaveBeenCalledTimes(writes);expect(mocks.api.presence.mock.calls.at(-1).slice(0,2)).toEqual([false,'expert']);
 await render('/expert-conversations');expect(wrapper.findComponent(Workspace).vm.accepting).toBe(false);
 await router.push('/support');await flushPromises();expect(wrapper.findComponent(Workspace).vm.accepting).toBe(true);
 wrapper.unmount();wrapper=null;
 expect(vi.getTimerCount()).toBe(0);
});
it('shows global and desk badges plus an actionable notification for messages arriving on the management page',async()=>{
 mocks.assigned.support_agent=[{request_id:'r',conversation_id:'c',service_type:'support_agent',status:'active',assigned_agent_user_id:'42'}];
 await render('/home');
 expect(mocks.notify).not.toHaveBeenCalled();
 mocks.messages.push({id:'incoming',seq:'8',sender_type:'user',parts:[{type:'text',text:'新消息'}]});
 await vi.advanceTimersByTimeAsync(4000);await flushPromises();
 expect(wrapper.find('.admin-notification-count').text()).toBe('1');
 expect(mocks.notify).toHaveBeenCalledOnce();
 mocks.notify.mock.calls[0][0].onClick();await flushPromises();
 expect(router.currentRoute.value.path).toBe('/support');expect(router.currentRoute.value.query.request).toBe('r');
 expect(wrapper.findComponent(Workspace).vm.unreadCounts.r).toBe(1);
 expect(wrapper.find('.contact-unread').text()).toBe('1');
 wrapper.vm.staffSession.markRead('r');await flushPromises();expect(wrapper.find('.admin-notification-count').exists()).toBe(false);
});

it('shows a persistent incoming-call banner on a management page and opens the right business without accepting',async()=>{
 mocks.invites=[{id:'71',type:'transfer',session_id:'9007199254740999',conversation_id:'call-c',request_id:'call-r',service_type:'expert',to_user_id:'42',mode:'video',status:'invited',requires_response:true,expires_at:new Date(Date.now()+60000).toISOString()}];
 await render('/home');expect(mocks.notify).toHaveBeenCalledOnce();const notice=mocks.notify.mock.calls[0][0];expect(notice.duration).toBe(0);expect(notice.customClass).toContain('staff-toast--call');
 notice.onClick();await flushPromises();expect(router.currentRoute.value.path).toBe('/expert-conversations');expect(router.currentRoute.value.query.media_session).toBe('9007199254740999');
 expect(router.currentRoute.value.query.request).toBeUndefined();expect(wrapper.findComponent(Workspace).vm.selected).toBeNull();
 expect(mocks.api.get).not.toHaveBeenCalled();
 mocks.invites=[];await wrapper.vm.staffSession.pollCalls();expect(mocks.notify.mock.results[0].value.close).toHaveBeenCalled();
});
