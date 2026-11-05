import { expect, it, vi } from 'vitest';
import { createStaffSound } from '../src/support/sound.mjs';
it('requires a user unlock, schedules one short message beep and throttles repeated reminders',async()=>{
  let time=0;const nodes=[];
  class Context {
    state='suspended';currentTime=1;destination={};
    resume=vi.fn(async()=>{this.state='running';});
    close=vi.fn(async()=>{this.state='closed';});
    createOscillator(){const node={frequency:{},connect:vi.fn(),disconnect:vi.fn(),start:vi.fn(),stop:vi.fn()};nodes.push(node);return node;}
    createGain(){return{gain:{setValueAtTime:vi.fn(),linearRampToValueAtTime:vi.fn(),exponentialRampToValueAtTime:vi.fn()},connect:vi.fn(),disconnect:vi.fn()};}
  }
  const sound=createStaffSound({AudioContextImpl:Context,now:()=>time});
  expect(sound.play()).toBe(false);expect(await sound.unlock()).toBe(true);
  expect(sound.play()).toBe(true);expect(nodes.map(node=>node.frequency.value)).toEqual([920]);expect(sound.play()).toBe(false);
  time=3000;expect(sound.play()).toBe(true);sound.close();expect(sound.play()).toBe(false);
});
it('keeps notifications usable when browser audio cannot be resumed',async()=>{
  class Context {state='suspended';resume=vi.fn().mockRejectedValue(new Error('blocked'));}
  const sound=createStaffSound({AudioContextImpl:Context});expect(await sound.unlock()).toBe(false);expect(sound.play()).toBe(false);
});
it('rings repeatedly for calls, keeps message beeps quiet during ringing and cancels scheduled tones on stop',async()=>{
 vi.useFakeTimers();const nodes=[];
 class Context{state='running';currentTime=0;destination={};close=vi.fn(async()=>{this.state='closed';});createOscillator(){const node={frequency:{},connect:vi.fn(),disconnect:vi.fn(),start:vi.fn(),stop:vi.fn()};nodes.push(node);return node;}createGain(){return{gain:{setValueAtTime:vi.fn(),linearRampToValueAtTime:vi.fn(),exponentialRampToValueAtTime:vi.fn()},connect:vi.fn(),disconnect:vi.fn()};}}
 const sound=createStaffSound({AudioContextImpl:Context});
 try{
  expect(sound.startRinging()).toBe(false);await sound.unlock();expect(sound.startRinging()).toBe(true);expect(nodes).toHaveLength(4);
  expect(sound.startRinging()).toBe(true);expect(vi.getTimerCount()).toBe(1);expect(sound.play()).toBe(false);
  await vi.advanceTimersByTimeAsync(2600);expect(nodes).toHaveLength(8);sound.stopRinging();expect(vi.getTimerCount()).toBe(0);expect(nodes.every(node=>node.stop.mock.calls.length>=2)).toBe(true);
  expect(sound.play()).toBe(true);sound.startRinging();sound.close();expect(vi.getTimerCount()).toBe(0);
 }finally{sound.close();vi.useRealTimers();}
});
