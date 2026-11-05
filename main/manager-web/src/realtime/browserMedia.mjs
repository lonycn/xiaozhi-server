import captureWorkletUrl from './capture.worklet.js?worker&url';
import {bytesBase64} from './pcm.mjs';
export function audioContext(){const Constructor=globalThis.AudioContext||globalThis.webkitAudioContext;if(!Constructor)throw new Error('AUDIO_UNAVAILABLE');return new Constructor();}
/** No permission request happens until this explicitly-invoked capture operation. */
export function createBrowserCapture({onPcm,onFrame,onError,contextFactory=audioContext,devices=globalThis.navigator?.mediaDevices,startupTimeoutMs=10000}){
 let closed=false,stream=null,context=null,source=null,node=null,video=null,timer=null,flushDone=null,cameraSampling=false,sampling=false,lastFrame=0,closePromise=null,cancelStartup=null;
 const stoppedTracks=new Set(),canceled=Symbol('capture canceled');
 const stopTracks=tracks=>{for(const track of tracks||[]){if(stoppedTracks.has(track))continue;stoppedTracks.add(track);track.stop();}};
 const stopCamera=()=>{cameraSampling=false;clearInterval(timer);stopTracks(stream?.getVideoTracks());if(video){video.pause();video.srcObject=null;video=null;}};
 function cleanup(){
  if(closed)return closePromise;
  closed=true;cancelStartup?.();flushDone?.();stopCamera();stopTracks(stream?.getTracks());source?.disconnect();node?.disconnect();
  if(node){node.port.onmessage=null;node.port.close?.();}
  closePromise=context&&context.state!=='closed'?Promise.resolve(context.close()).catch(()=>{}):Promise.resolve();return closePromise;
 }
 // Permission prompts intentionally have no deadline. Only device startup after
 // consent is bounded, and stop() releases a stalled worklet/play without waiting.
 async function startup(operation){
  if(closed)throw canceled;let timeout;
  try{
   const result=await Promise.race([operation,new Promise(resolve=>{cancelStartup=()=>resolve(canceled);}),new Promise((_resolve,reject)=>{timeout=setTimeout(()=>reject(new Error('TIMEOUT')),startupTimeoutMs);})]);
   if(closed||result===canceled)throw canceled;
  }finally{clearTimeout(timeout);cancelStartup=null;}
 }
 return {
  async start(camera){
   try{
    if(!devices?.getUserMedia)throw new Error('AUDIO_UNAVAILABLE');
    stream=await devices.getUserMedia({audio:{channelCount:1,echoCancellation:true,noiseSuppression:true},video:camera?{width:{ideal:1280},height:{ideal:720}}:false});
    if(closed){stopTracks(stream.getTracks());return;}
    context=contextFactory();if(!context.audioWorklet?.addModule||!globalThis.AudioWorkletNode)throw new Error('AUDIO_UNAVAILABLE');await startup(context.audioWorklet.addModule(captureWorkletUrl));if(closed)return;
    source=context.createMediaStreamSource(stream);node=new AudioWorkletNode(context,'realtime-pcm-capture',{numberOfInputs:1,numberOfOutputs:1,outputChannelCount:[1]});
    node.port.onmessage=event=>{if(event.data?.type==='flushed'){flushDone?.();return;}if(!closed&&event.data?.type==='chunk')onPcm(new Uint8Array(event.data.pcm));};source.connect(node);node.connect(context.destination);await startup(context.resume());if(closed)return;
    if(camera){video=document.createElement('video');video.muted=true;video.playsInline=true;video.srcObject=new MediaStream(stream.getVideoTracks());await startup(video.play());if(closed)return;cameraSampling=true;timer=setInterval(async()=>{if(closed||!cameraSampling||sampling||!video.videoWidth||performance.now()-lastFrame<1000)return;sampling=true;try{const image=await boundedJpeg(video);if(!closed&&cameraSampling){const sent=onFrame(image);if(sent)lastFrame=performance.now();}}catch(error){if(!closed)onError(error);}finally{sampling=false;}},1000);}
   }catch(error){cleanup();if(error!==canceled)throw error;}
  },
  stopCamera,
  async stop({flush=false}={}){
   if(closed)return closePromise;stopCamera();
   if(flush&&node){await new Promise(resolve=>{const timeout=setTimeout(resolve,250);flushDone=()=>{clearTimeout(timeout);resolve();};node.port.postMessage({type:'flush'});});}
   return cleanup();
  },
 };
}
export async function boundedJpeg(video){
 const canvas=document.createElement('canvas');let scale=Math.min(1,1280/video.videoWidth,720/video.videoHeight);if(!(scale>0))throw new Error('INVALID_FRAME');
 for(const quality of [.7,.5,.3,.2]){canvas.width=Math.max(1,Math.floor(video.videoWidth*scale));canvas.height=Math.max(1,Math.floor(video.videoHeight*scale));canvas.getContext('2d').drawImage(video,0,0,canvas.width,canvas.height);const blob=await new Promise((resolve,reject)=>canvas.toBlob(value=>value?resolve(value):reject(new Error('FRAME_FAILED')),'image/jpeg',quality));if(blob.size<=190000&&canvas.width*canvas.height<=921600)return bytesBase64(new Uint8Array(await blob.arrayBuffer()));scale*=.8;}
 throw new Error('FRAME_LIMIT');
}
