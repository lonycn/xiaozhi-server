import { mount, flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Panel from '../src/components/SupportSummaryPanel.vue';
const api = vi.hoisted(() => ({ summaries: vi.fn(), prepareSummary: vi.fn(), editSummary: vi.fn() }));
vi.mock('../src/apis/module/support', () => ({ default: api }));
const keys = ['user_goal','project/equipment','symptoms','evidence_attachments','verified_facts','hypotheses','attempted_steps','missing_information','safety_flags'];
const unknown = () => ({ text: '待确认', attribution: 'unknown', evidence: [], verification_note: null });
const revision = (version = '1') => ({ version, origin: 'deterministic_excerpt', source_through_seq: '2', created_at: '2026-10-30', fields: Object.fromEntries(keys.map(key => [key,[unknown()]])) });
const messages = [ { id: 'u1', seq: '1', sender_type: 'user', parts: [{ type: 'text', text: '<script>原始用户内容</script>' }] }, { id: 'a1', seq: '2', sender_type: 'assistant', parts: [{ type: 'text', text: 'AI 推测' }] } ];
let wrapper;
function panel(props = {}) { wrapper = mount(Panel,{ props: { conversationId: 'c1', messages, writable: true, ...props }, global: { stubs: { ElButton: { template: '<button><slot /></button>' }, ElSelect: true, ElOption: true, ElInput: true } } }); return wrapper; }
beforeEach(() => { vi.resetAllMocks(); api.summaries.mockResolvedValue([revision()]); });
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
});
