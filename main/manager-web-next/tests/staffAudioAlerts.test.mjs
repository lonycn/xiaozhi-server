import { expect, it, vi } from 'vitest';
import { flushPromises } from '@vue/test-utils';
import { createStaffAudioAlerts } from '../src/support/audioAlerts.mjs';
function broker() {
 const held=new Set();return{request:vi.fn(async(name,options,run)=>{if(held.has(name))return run(null);held.add(name);try{return await run({name});}finally{held.delete(name);}})};
}
it('deduplicates a message beep between tabs using account and exact message cursor',async()=>{
 const locks=broker(),values=new Map(),storage={getItem:key=>values.get(key),setItem:(key,value)=>values.set(key,value)};
 const firstSound={play:vi.fn(()=>true)},secondSound={play:vi.fn(()=>true)};
 const first=createStaffAudioAlerts({sound:firstSound,locks,storage,userId:()=> '42',now:()=>0});
 const second=createStaffAudioAlerts({sound:secondSound,locks,storage,userId:()=> '42',now:()=>0});
 const event={kind:'message',request_id:'r',last_seq:'9007199254740999'};
 await first.beep(event);await second.beep(event);expect(firstSound.play).toHaveBeenCalledOnce();expect(secondSound.play).not.toHaveBeenCalled();
 await second.beep({...event,last_seq:'9007199254741000'});expect(secondSound.play).toHaveBeenCalledOnce();
 first.stop();second.stop();
});
it('lets one tab ring and lets another take over when the owner releases the call',async()=>{
 const locks=broker(),one={startRinging:vi.fn(()=>true),stopRinging:vi.fn()},two={startRinging:vi.fn(()=>true),stopRinging:vi.fn()};
 const first=createStaffAudioAlerts({sound:one,locks,userId:()=> '42'}),second=createStaffAudioAlerts({sound:two,locks,userId:()=> '42'});
 first.ringing(true);second.ringing(true);await flushPromises();expect(one.startRinging).toHaveBeenCalledOnce();expect(two.startRinging).not.toHaveBeenCalled();
 first.ringing(false);await flushPromises();second.ringing(true);await flushPromises();expect(two.startRinging).toHaveBeenCalledOnce();
 first.stop();second.stop();await flushPromises();expect(one.stopRinging).toHaveBeenCalled();expect(two.stopRinging).toHaveBeenCalled();
});
