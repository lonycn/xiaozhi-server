<template>
 <section class="realtime-workspace" :aria-label="$t('realtime.title')">
  <button v-if="allowed && !sessionLive && ['idle','ended','failed','canceled','expired'].includes(state.status)" type="button" @click="consentVisible = !consentVisible">{{ $t('realtime.open') }}</button>
  <small v-if="state.status === 'unavailable'">{{ $t('realtime.unavailable') }}</small>
  <div v-if="consentVisible && allowed" class="realtime-consent"><p>{{ $t('realtime.consentNote') }}</p><label><input v-model="microphone" type="checkbox" />{{ $t('realtime.microphoneConsent') }}</label><label><input v-model="camera" type="checkbox" />{{ $t('realtime.cameraConsent') }}</label><button type="button" :disabled="!microphone" @click="start">{{ $t('realtime.start') }}</button><button type="button" @click="consentVisible = false">{{ $t('realtime.close') }}</button></div>
  <aside v-if="visible" class="realtime-overlay" :aria-label="$t('realtime.title')">
   <header><strong>{{ $t('realtime.title') }}</strong><button type="button" :aria-label="$t('realtime.minimize')" @click="minimized = !minimized">{{ minimized ? '▣' : '−' }}</button><button v-if="terminal" type="button" :aria-label="$t('realtime.close')" @click="dismissed = true">×</button></header>
   <p role="status">{{ $t('realtime.status_' + state.status) }}</p>
   <div v-show="!minimized">
    <p v-if="state.error" class="realtime-error" role="alert">{{ $t(state.error) }}</p>
    <p class="realtime-note">{{ $t('realtime.manualNote') }}</p>
    <p v-if="state.session?.consent?.camera" class="realtime-note">{{ $t('realtime.frameNote') }}</p>
    <p v-if="state.cameraSampling">{{ $t('realtime.sampling') }}<button type="button" @click="controller.stopCamera()">{{ $t('realtime.stopCamera') }}</button></p>
    <p v-if="state.status === 'recording'">{{ state.turnSeconds }} / 60 {{ $t('realtime.seconds') }}</p>
    <p v-if="state.transcript" class="realtime-transcript">{{ state.transcript }}</p>
    <div class="realtime-actions">
     <button v-if="state.status === 'active'" type="button" @click="controller.beginTurn()">{{ $t('realtime.record') }}</button>
     <button v-if="state.status === 'recording'" type="button" @click="controller.commitTurn()">{{ $t('realtime.commit') }}</button>
     <button v-if="state.responseId && state.status !== 'interrupting'" type="button" @click="controller.interrupt()">{{ $t('realtime.interrupt') }}</button>
     <button v-if="sessionLive" type="button" class="realtime-end" @click="controller.end()">{{ $t('realtime.end') }}</button>
    </div>
    <p v-if="state.injections.length" class="realtime-note">{{ $t('realtime.injectionNote') }}</p>
    <ul v-if="state.injections.length" class="realtime-injections"><li v-for="item in state.injections.slice(-5)" :key="item.id">{{ $t('realtime.savedMessage') }} {{ item.message_id }} · {{ $t('realtime.injection_' + item.status) }}</li></ul>
    <p class="realtime-note">{{ $t('realtime.draftNote') }}</p>
   </div>
  </aside>
 </section>
</template>
<script>
import {markRaw} from 'vue';
import realtimeApi from '../apis/module/realtime';
import {createRealtimeSession,REALTIME_TERMINAL} from '../realtime/session.mjs';
export default {
 name:'AIRealtimeOverlay',props:{conversationId:{type:String,default:''},identity:{type:String,default:''},allowed:Boolean},emits:['state','history'],
 data:()=>({controller:null,state:{status:'idle',session:null,error:'',injections:[]},microphone:false,camera:false,consentVisible:false,minimized:false,dismissed:false,clientId:'',signature:'',timer:null,disposed:false}),
 computed:{context(){return JSON.stringify([this.conversationId,this.identity,this.allowed]);},sessionLive(){return !!this.state.session&&!REALTIME_TERMINAL.has(this.state.session.status);},terminal(){return REALTIME_TERMINAL.has(this.state.status)&&!this.sessionLive;},visible(){return !this.dismissed&&!['idle','unavailable'].includes(this.state.status);}},
 watch:{context:{flush:'sync',handler(){this.reset();}}},
 mounted(){this.reset();},beforeUnmount(){this.disposed=true;clearInterval(this.timer);this.controller?.dispose();},
 methods:{
  reset(){clearInterval(this.timer);this.controller?.dispose();this.microphone=false;this.camera=false;this.consentVisible=false;this.dismissed=false;this.clientId='';this.signature='';this.controller=markRaw(createRealtimeSession({client:realtimeApi,changed:state=>{this.state=state;this.$emit('state',state);},history:()=>this.$emit('history')}));this.state=this.controller.snapshot();this.$emit('state',this.state);if(this.allowed&&this.conversationId&&this.identity){this.controller.discover(this.conversationId);this.timer=setInterval(()=>this.controller.refresh(),2000);}},
  shutdown(){return this.controller?.end();},
  async start(){if(!this.allowed||!this.microphone)return;const consent={microphone:this.microphone,camera:this.camera},signature=JSON.stringify([this.conversationId,consent]);if(signature!==this.signature||this.terminal){this.signature=signature;this.clientId=crypto.randomUUID();}this.consentVisible=false;this.dismissed=false;await this.controller.start(this.conversationId,consent,this.clientId);},
 },
};
</script>
<style scoped>
.realtime-workspace{font-size:12px;color:#344968;margin:8px 0;text-align:left}.realtime-workspace button{font:inherit;border:1px solid #ccd9e7;border-radius:6px;padding:7px 10px;background:#f6f9ff;color:#345583;cursor:pointer}.realtime-workspace button:disabled{opacity:.5;cursor:default}.realtime-consent{padding:10px;border:1px solid #dce5f0;border-radius:8px;background:#fff}.realtime-consent label{display:block;margin:9px 0;line-height:1.6}.realtime-consent input{margin-right:7px}.realtime-overlay{position:fixed;right:24px;bottom:24px;z-index:1850;width:330px;max-width:calc(100vw - 24px);max-height:calc(100dvh - 90px);overflow:auto;padding:14px;box-sizing:border-box;border:1px solid #ccdae9;border-radius:14px;background:#fff;box-shadow:0 8px 40px #25486a26}.realtime-overlay header{display:flex;align-items:center;gap:7px}.realtime-overlay header strong{flex:1}.realtime-note{line-height:1.6;color:#667a91;font-size:12px}.realtime-error{color:#b13d30;line-height:1.6}.realtime-transcript{white-space:pre-wrap;overflow-wrap:anywhere;max-height:180px;overflow:auto;padding:8px;background:#f3f7fc;border-radius:6px;line-height:1.6}.realtime-actions{display:flex;flex-wrap:wrap;gap:7px}.realtime-workspace .realtime-end{background:#fff1ef;color:#b13d30;border-color:#f2c1b9}.realtime-injections{padding-left:18px;font-size:11px;line-height:1.6;overflow-wrap:anywhere;max-height:100px;overflow:auto}button:focus-visible{outline:2px solid #83a5f8;outline-offset:2px}@media(max-width:600px){.realtime-overlay{right:12px;bottom:12px;width:290px}}
</style>
