import { mount, flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Panel from '../src/components/SupportSummaryPanel.vue';
const api = vi.hoisted(() => ({ summaries: vi.fn(), prepareSummary: vi.fn(), editSummary: vi.fn(), summaryAssistance: vi.fn(), createSummaryAssistance: vi.fn(), summaryAssistanceJob: vi.fn(), retrySummaryAssistance: vi.fn() }));
vi.mock('../src/apis/module/support', () => ({ default: api }));
const keys = ['user_goal','project/equipment','symptoms','evidence_attachments','verified_facts','hypotheses','attempted_steps','missing_information','safety_flags'];
const unknown = () => ({ text: '待确认', attribution: 'unknown', evidence: [], verification_note: null });
const revision = (version = '1') => ({ version, origin: 'deterministic_excerpt', source_through_seq: '2', created_at: '2026-10-30', fields: Object.fromEntries(keys.map(key => [key,[unknown()]])) });
const messages = [ { id: 'u1', seq: '1', sender_type: 'user', parts: [{ type: 'text', text: '<script>原始用户内容</script>' }] }, { id: 'a1', seq: '2', sender_type: 'assistant', parts: [{ type: 'text', text: 'AI 推测' }] } ];
let wrapper;
function panel(props = {}) { wrapper = mount(Panel,{ props: { conversationId: 'c1', messages, writable: true, ...props }, global: { stubs: { ElButton: { template: '<button><slot /></button>' }, ElSelect: true, ElOption: true, ElInput: true } } }); return wrapper; }
beforeEach(() => { vi.resetAllMocks(); api.summaries.mockResolvedValue([revision()]); api.summaryAssistance.mockResolvedValue({ enabled: true, latest_job: null }); });
afterEach(() => { wrapper?.unmount(); wrapper = null; });
describe('internal handoff summary', () => {
  it('creates one draft for an active request upgraded without backfilling business rows', async () => {
    api.summaries.mockResolvedValue([]); api.prepareSummary.mockResolvedValue(revision()); panel(); await flushPromises(); expect(wrapper.text()).toContain('创建原文草稿'); await wrapper.vm.prepare(); expect(api.prepareSummary).toHaveBeenCalledWith('c1',expect.any(AbortSignal)); expect(wrapper.vm.revisions).toHaveLength(1);
  });
  it('renders nine fields as an internal unverified excerpt draft', async () => {
    panel(); await flushPromises(); expect(wrapper.findAll('.summary-field')).toHaveLength(9); expect(wrapper.text()).toContain('不发送给用户'); expect(wrapper.text()).toContain('原文摘录草稿'); expect(api.summaries).toHaveBeenCalledWith('c1','0',expect.any(AbortSignal));
  });
  it('edits by cloning the Vue proxy and saves a new version with original evidence', async () => {
    panel(); await flushPromises(); wrapper.vm.beginEdit(); wrapper.vm.sourceChoices.user_goal = 'u1:0'; wrapper.vm.addEvidence('user_goal');
    expect(wrapper.vm.edited.user_goal.at(-1)).toMatchObject({ attribution: 'user_statement', evidence: [{ message_id: 'u1', seq: '1', quote: '<script>原始用户内容</script>' }] });
    expect(wrapper.vm.selected.fields.user_goal).toHaveLength(1);
    api.editSummary.mockResolvedValue({ ...revision('2'), origin: 'staff_edit', edited_by: '3', fields: JSON.parse(JSON.stringify(wrapper.vm.edited)) }); await wrapper.vm.save();
    expect(api.editSummary.mock.calls[0][1].base_version).toBe('1'); expect(wrapper.vm.selectedVersion).toBe('2'); expect(wrapper.vm.editing).toBe(false); expect(wrapper.find('script').exists()).toBe(false); expect(wrapper.text()).toContain('<script>原始用户内容</script>');
  });
  it('rejects promoting AI text into facts and fences writes for historical staff', async () => {
    panel(); await flushPromises(); wrapper.vm.beginEdit(); wrapper.vm.sourceChoices.verified_facts = 'a1:0'; wrapper.vm.addEvidence('verified_facts'); expect(wrapper.vm.edited.verified_facts).toHaveLength(1); expect(wrapper.vm.error).toContain('AI 原文只能归入推测');
    wrapper.vm.sourceChoices.hypotheses = 'a1:0'; wrapper.vm.addEvidence('hypotheses'); expect(wrapper.vm.edited.hypotheses.at(-1).attribution).toBe('ai_inference');
    await wrapper.setProps({ writable: false }); await flushPromises(); wrapper.vm.beginEdit(); expect(wrapper.vm.editing).toBe(false); await wrapper.vm.save(); expect(api.editSummary).not.toHaveBeenCalled();
  });
  it('retains local edits when another staff member has saved a newer version', async () => {
    panel(); await flushPromises(); wrapper.vm.beginEdit(); wrapper.vm.edited.symptoms[0].text = '本地待确认草稿'; api.editSummary.mockRejectedValue({ code: 'SUMMARY_VERSION_CONFLICT', message: 'conflict' }); await wrapper.vm.save(); expect(wrapper.vm.edited.symptoms[0].text).toBe('本地待确认草稿'); expect(wrapper.vm.error).toContain('本地编辑已保留');
  });
  it('does not display a late response from another conversation or identity scope', async () => {
    let finish; api.summaries.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; })); panel(); const oldSignal = api.summaries.mock.calls[0][2]; await wrapper.setProps({ conversationId: 'c2' }); await flushPromises(); finish([{ ...revision('99'), fields: { secret: 'old' } }]); await flushPromises(); expect(oldSignal.aborted).toBe(true); expect(wrapper.vm.revisions[0].version).toBe('1'); expect(wrapper.text()).not.toContain('old');
  });
  it('links exact original message and seq rather than copying evidence into the user chat', async () => {
    const r = revision(); r.fields.user_goal = [{ text: '原文', attribution: 'user_statement', evidence: [{ message_id: 'u1', seq: '1', quote: '原始用户内容' }] }]; api.summaries.mockResolvedValue([r]); panel(); await flushPromises(); const source = wrapper.findAll('button').find(button => button.text().includes('查看原消息')); await source.trigger('click'); expect(wrapper.emitted('source')[0][0]).toEqual(r.fields.user_goal[0].evidence[0]);
  });
  it('explicitly creates a job, polls and reloads a model revision; suggestions require a click', async () => {
    const queued = { id:'job1', status:'queued', suggestions:[] };
    const suggestion = { text:'请检查温度', attribution:'ai_inference', evidence:[{ message_id:'u1', seq:'1', quote:'原文' }] };
    api.createSummaryAssistance.mockResolvedValue(queued);
    api.summaryAssistanceJob.mockResolvedValue({ ...queued, status:'completed', suggestions:[suggestion] });
    panel(); await flushPromises(); expect(api.createSummaryAssistance).not.toHaveBeenCalled();
    await wrapper.vm.createAssistance(); expect(api.createSummaryAssistance.mock.calls[0][1]).toMatchObject({base_version:'1', client_request_id:expect.any(String)});
    api.summaries.mockResolvedValue([{...revision('2'),origin:'model_assisted'}]); await wrapper.vm.pollJob(); await flushPromises();
    expect(wrapper.vm.selected.version).toBe('2'); expect(wrapper.text()).toContain('模型辅助整理'); expect(wrapper.text()).toContain('AI 推测（仅内部');
    expect(wrapper.emitted('suggestion')).toBeUndefined(); await wrapper.findAll('button').find(b=>b.text().includes('加入回复草稿')).trigger('click'); expect(wrapper.emitted('suggestion')[0]).toEqual([{conversation_id:'c1',text:'请检查温度'}]);
    await wrapper.findAll('button').find(b=>b.text().includes('查看原消息')).trigger('click'); expect(wrapper.emitted('source')[0][0]).toEqual(suggestion.evidence[0]);
  });
  it('reuses an uncertain create request ID and uses a fresh ID after an accepted job', async () => {
    api.createSummaryAssistance.mockRejectedValueOnce({code:'NETWORK',message:'网络失败'}).mockResolvedValueOnce({id:'1',status:'stale'}).mockResolvedValueOnce({id:'2',status:'stale'});
    panel(); await flushPromises(); await wrapper.vm.createAssistance(); await wrapper.vm.createAssistance(); await wrapper.vm.createAssistance();
    const ids = api.createSummaryAssistance.mock.calls.map(call=>call[1].client_request_id); expect(ids[0]).toBe(ids[1]); expect(ids[2]).not.toBe(ids[1]); expect(wrapper.text()).toContain('本次结果未写入');
  });
  it('retries failed jobs explicitly and never shows stale suggestions', async () => {
    api.summaryAssistance.mockResolvedValue({enabled:true,latest_job:{id:'1',status:'failed'}}); api.retrySummaryAssistance.mockResolvedValue({id:'1',status:'stale', suggestions:[{text:'过期建议'}]});
    panel(); await flushPromises(); expect(api.retrySummaryAssistance).not.toHaveBeenCalled(); await wrapper.vm.retryAssistance(); expect(api.retrySummaryAssistance).toHaveBeenCalledOnce(); expect(wrapper.text()).not.toContain('过期建议');
  });
  it.each([[null,'本次结果未写入'],['2','历史整理已保留']])('distinguishes stale jobs with result version %s', async (resultVersion, message) => {
    api.summaryAssistance.mockResolvedValue({enabled:true,latest_job:{id:'1',status:'stale',result_version:resultVersion}}); panel(); await flushPromises(); expect(wrapper.text()).toContain(message); expect(wrapper.text()).not.toContain(resultVersion === null ? '历史整理已保留' : '本次结果未写入');
  });
  it('hides disabled capability and fences late jobs and suggestions on conversation or permission change', async () => {
    api.summaryAssistance.mockRejectedValueOnce({status:404,message:'not found'}); panel(); await flushPromises(); expect(wrapper.text()).toContain('暂不可用'); await wrapper.vm.createAssistance(); expect(api.createSummaryAssistance).not.toHaveBeenCalled();
    let finish; api.summaryAssistance.mockReturnValueOnce(new Promise(resolve=>{finish=resolve;})); await wrapper.setProps({conversationId:'c2'}); const signal=api.summaryAssistance.mock.calls.at(-1)[1]; await wrapper.setProps({writable:false}); finish({enabled:true,latest_job:{id:'1',status:'completed',suggestions:[{text:'旧内容'}]}}); await flushPromises(); expect(signal.aborted).toBe(true); expect(wrapper.text()).not.toContain('旧内容'); expect(wrapper.vm.job).toBeNull();
  });
  it('invalidates suggestions and in-flight polling when messages change without losing local edits', async () => {
    let finish; api.summaryAssistance.mockResolvedValue({enabled:true,latest_job:{id:'1',status:'running'}});
    api.summaryAssistanceJob.mockReturnValue(new Promise(resolve=>{finish=resolve;})); panel(); await flushPromises(); wrapper.vm.beginEdit(); wrapper.vm.edited.symptoms[0].text='保留内容'; const pending=wrapper.vm.pollJob(); api.summaryAssistance.mockResolvedValue({enabled:true,latest_job:null});
    const signal=api.summaryAssistanceJob.mock.calls[0][2]; await wrapper.setProps({messages:[...messages,{id:'u2',seq:'3',sender_type:'user',parts:[]}]});
    finish({id:'1',status:'completed',suggestions:[{text:'过期内容'}]}); await pending; await flushPromises(); expect(signal.aborted).toBe(true); expect(wrapper.vm.job).toBeNull(); expect(wrapper.vm.edited.symptoms[0].text).toBe('保留内容'); expect(wrapper.text()).not.toContain('过期内容');
  });
  it('rechecks capability after history arrives and fences the earlier request; read-only staff do not query', async () => {
    let oldResult; api.summaryAssistance.mockReset().mockImplementationOnce(()=>new Promise(resolve=>{oldResult=resolve;})).mockResolvedValueOnce({enabled:true,latest_job:null});
    panel({messages:[]}); const oldSignal=api.summaryAssistance.mock.calls[0][1];
    const history=Array.from({length:5},(_,i)=>({id:`h${i}`,seq:String(i+1),sender_type:'user',parts:[]})); await wrapper.setProps({messages:history}); await flushPromises();
    expect(oldSignal.aborted).toBe(true); expect(api.summaryAssistance).toHaveBeenCalledTimes(2); expect(wrapper.vm.assistanceAvailable).toBe(true); expect(wrapper.text()).toContain('AI辅助整理');
    oldResult({enabled:false,latest_job:null}); await flushPromises(); expect(wrapper.vm.assistanceAvailable).toBe(true); expect(api.createSummaryAssistance).not.toHaveBeenCalled();
    await wrapper.setProps({writable:false}); const calls=api.summaryAssistance.mock.calls.length; await wrapper.setProps({messages:[...history,{id:'h5',seq:'6',sender_type:'user',parts:[]}]}); await flushPromises(); expect(api.summaryAssistance).toHaveBeenCalledTimes(calls);
  });
  it('does not show completed suggestions if a newer revision exists', async () => {
    api.summaryAssistance.mockResolvedValue({enabled:true,latest_job:{id:'1',status:'completed',result_version:'1',suggestions:[{text:'过期内容'}]}}); api.summaries.mockResolvedValue([revision('2')]);
    panel(); await flushPromises(); expect(wrapper.vm.job.status).toBe('stale'); expect(wrapper.text()).not.toContain('过期内容');
  });
  it('hides old suggestions on own successful edit and fences an earlier job response', async () => {
    const oldJob={id:'1',status:'completed',result_version:'1',suggestions:[{text:'旧建议',attribution:'ai_inference',evidence:[]}]};
    api.summaryAssistance.mockResolvedValueOnce({enabled:true,latest_job:oldJob}).mockResolvedValueOnce({enabled:true,latest_job:{...oldJob,status:'stale'}});
    let late; api.summaryAssistanceJob.mockReturnValue(new Promise(resolve=>{late=resolve;})); api.editSummary.mockResolvedValue({...revision('2'),origin:'staff_edit'});
    panel(); await flushPromises(); expect(wrapper.text()).toContain('旧建议'); const pending=wrapper.vm.pollJob(); const signal=api.summaryAssistanceJob.mock.calls[0][2];
    wrapper.vm.beginEdit(); await wrapper.vm.save(); expect(signal.aborted).toBe(true); expect(wrapper.text()).not.toContain('旧建议');
    late(oldJob); await pending; await flushPromises(); expect(wrapper.vm.job.status).toBe('stale'); expect(wrapper.text()).not.toContain('旧建议'); expect(wrapper.findAll('button').some(b=>b.text().includes('加入回复草稿'))).toBe(false); expect(wrapper.emitted('suggestion')).toBeUndefined(); expect(api.createSummaryAssistance).not.toHaveBeenCalled();
  });
  it.each(['completion first','initial first'])('handles concurrent initial and completed revision reads: %s', async order => {
    let initial, completed; api.summaries.mockReset().mockImplementationOnce(()=>new Promise(resolve=>{initial=resolve;})).mockImplementationOnce(()=>new Promise(resolve=>{completed=resolve;}));
    api.summaryAssistance.mockResolvedValue({enabled:true,latest_job:{id:'1',status:'completed',result_version:'2',suggestions:[]}});
    panel(); await flushPromises(); expect(api.summaries).toHaveBeenCalledTimes(2);
    const fresh=[{...revision('2'),origin:'model_assisted'},revision('1')];
    if(order === 'completion first') { completed(fresh); await flushPromises(); initial([revision('1')]); }
    else { initial([revision('1')]); await flushPromises(); completed(fresh); }
    await flushPromises(); expect(wrapper.vm.revisions.map(row=>row.version)).toEqual(['2','1']); expect(wrapper.vm.selectedVersion).toBe('2');
  });
  it.each([['INVALID_MODEL_OUTPUT','模型结果未通过证据核验'],['PROVIDER_AUTH','模型服务认证失败'],['UNEXPECTED_SECRET_CODE','整理任务未完成']])('shows friendly failure for %s', async (code, message) => {
    api.summaryAssistance.mockResolvedValue({enabled:true,latest_job:{id:'1',status:'failed',error_code:code}}); panel(); await flushPromises(); expect(wrapper.text()).toContain(message); expect(wrapper.text()).not.toContain(code);
  });
});
