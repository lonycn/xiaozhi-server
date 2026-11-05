import {Pcm16Framer} from './pcm.mjs';
class RealtimePcmCapture extends AudioWorkletProcessor{
 constructor(){super();this.framer=new Pcm16Framer(sampleRate);this.active=true;this.port.onmessage=event=>{if(event.data?.type==='flush'){this.active=false;const bytes=this.framer.flush();if(bytes)this.port.postMessage({type:'chunk',pcm:bytes.buffer},[bytes.buffer]);this.port.postMessage({type:'flushed'});}};}
 process(inputs,outputs){for(const output of outputs)for(const channel of output)channel.fill(0);if(this.active&&inputs[0]?.length){const channels=inputs[0],mono=new Float32Array(channels[0].length);for(const channel of channels)for(let i=0;i<mono.length;i++)mono[i]+=channel[i]/channels.length;for(const bytes of this.framer.push(mono))this.port.postMessage({type:'chunk',pcm:bytes.buffer},[bytes.buffer]);}return this.active;}
}
registerProcessor('realtime-pcm-capture',RealtimePcmCapture);
