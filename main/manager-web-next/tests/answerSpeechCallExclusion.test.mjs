import {describe,it,expect,vi,afterEach} from 'vitest';
import {mount} from '@vue/test-utils';
import AnswerSpeech from '../src/components/AnswerSpeech.vue';
import playback from '../src/speech/answerSpeech.mjs';
vi.mock('../src/speech/answerSpeech.mjs',()=>({default:{start:vi.fn(),stop:vi.fn(),invalidate:vi.fn()}}));
afterEach(()=>vi.clearAllMocks());
describe('answer speech and calls',()=>{
 it('stops the current reading when a call starts and preserves completed audio for explicit replay',async()=>{
  playback.start.mockImplementation(({changed})=>changed({status:'playing',job:{id:'j',status:'completed'}}));
  const wrapper=mount(AnswerSpeech,{props:{conversationId:'c',message:{id:'m',status:'completed',parts:[{type:'text',text:'answer'}]},identity:'opaque'}});
  await wrapper.get('button').trigger('click');
  expect(wrapper.text()).toContain('停止朗读');
  await wrapper.setProps({blocked:true});
  expect(playback.invalidate).toHaveBeenCalledWith(JSON.stringify(['opaque','c','m']));
  expect(wrapper.get('button').element.disabled).toBe(true);
  wrapper.vm.play();expect(playback.start).toHaveBeenCalledTimes(1);
  await wrapper.setProps({blocked:false});await wrapper.get('button').trigger('click');
  expect(playback.start).toHaveBeenLastCalledWith(expect.objectContaining({job:{id:'j',status:'completed'}}));
  wrapper.unmount();
 });
});
