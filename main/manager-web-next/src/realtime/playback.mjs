import {pcm16Floats} from './pcm.mjs';
/** A bounded scheduler, with cancellation of every already-scheduled source. */
export function createPcmPlayback(context,{maxSeconds=12,maxBytes=65536,resumeTimeoutMs=10000}={}){
 let nextAt=context.currentTime,responseId=null,closed=false,cancelResume=null;const sources=new Set();
 const clear=()=>{for(const source of sources){source.onended=null;try{source.stop();}catch{}source.disconnect();}sources.clear();nextAt=context.currentTime;};
 return {
  response(id){if(id!==responseId){clear();responseId=id;}},
  append(id,bytes){if(closed||id!==responseId)return false;if(bytes.length>maxBytes)throw new Error('OUTPUT_LIMIT');const samples=pcm16Floats(bytes),duration=samples.length/24000,start=Math.max(context.currentTime,nextAt);if(start+duration-context.currentTime>maxSeconds)throw new Error('OUTPUT_LIMIT');const buffer=context.createBuffer(1,samples.length,24000);buffer.copyToChannel(samples,0);const source=context.createBufferSource();source.buffer=buffer;source.connect(context.destination);sources.add(source);source.onended=()=>{sources.delete(source);source.disconnect();};source.start(start);nextAt=start+duration;return true;},
  pendingSeconds:()=>Math.max(0,nextAt-context.currentTime),clear,
  async resume(){
   if(closed)return;let timer;
   try{await Promise.race([context.resume(),new Promise(resolve=>{cancelResume=resolve;}),new Promise((_resolve,reject)=>{timer=setTimeout(()=>reject(new Error('TIMEOUT')),resumeTimeoutMs);})]);}
   finally{clearTimeout(timer);cancelResume=null;}
  },
  async close(){if(closed)return;closed=true;cancelResume?.();clear();await context.close();},
 };
}
