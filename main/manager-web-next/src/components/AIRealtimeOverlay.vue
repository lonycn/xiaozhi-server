<template>
 <section class="realtime-workspace" :aria-label="$t('realtime.title')">
  <button v-if="allowed && !sessionLive && ['idle','ended','failed','canceled','expired'].includes(state.status)" type="button" @click="openConsent">{{ $t('realtime.open') }}</button>
  <small v-if="state.status === 'unavailable'">{{ $t('realtime.unavailable') }}</small>
  <small v-if="state.status === 'ended' && !sessionLive" role="status">{{ $t('realtime.status_ended') }}</small>
  <div v-if="consentVisible && allowed" class="realtime-consent"><fieldset><legend>{{ $t('realtime.modeLabel') }}</legend><label><input v-model="turnMode" type="radio" value="manual" :disabled="retryLocked" />{{ $t('realtime.modeManual') }}</label><label><input v-model="turnMode" type="radio" value="server_vad" :disabled="!autoAvailable || retryLocked" />{{ $t('realtime.modeAuto') }}</label><small v-if="retryLocked">{{ $t('realtime.retrySame') }}</small><small v-if="!autoAvailable">{{ $t('realtime.autoUnavailable') }}</small></fieldset><p>{{ $t('realtime.consentNote') }}</p><label><input v-model="microphone" type="checkbox" :disabled="retryLocked" />{{ $t('realtime.microphoneConsent') }}</label><label><input v-model="camera" type="checkbox" :disabled="retryLocked" />{{ $t('realtime.cameraConsent') }}</label><button type="button" :disabled="!microphone" @click="start">{{ $t('realtime.start') }}</button><button type="button" @click="consentVisible = false">{{ $t('realtime.close') }}</button></div>
  <aside v-if="visible" class="realtime-overlay" :aria-label="$t('realtime.title')">
   <header><strong>{{ $t('realtime.title') }}</strong><button type="button" :aria-label="$t('realtime.minimize')" @click="minimized = !minimized">{{ minimized ? '▣' : '−' }}</button><button v-if="terminal" type="button" :aria-label="$t('realtime.close')" @click="dismissed = true">×</button></header>
   <p role="status">{{ state.materialInput?.locked ? '实时采集已停止，正在补充材料' : $t(statusKey) }}</p>
   <div v-show="!minimized">
    <p v-if="state.listeningPending && state.status !== 'active'" role="status">{{ $t('realtime.listeningPending') }}</p>
    <p v-if="state.error" class="realtime-error" role="alert">{{ $t(state.error) }}</p>
    <p class="realtime-note">{{ $t(autoMode ? 'realtime.autoNote' : 'realtime.manualNote') }}</p><p v-if="sessionLive" class="realtime-note">{{ $t('realtime.switchModeNote') }}</p>
    <p v-if="state.session?.consent?.camera" class="realtime-note">{{ $t('realtime.frameNote') }}</p>
    <p v-if="state.cameraSampling">{{ $t('realtime.sampling') }}<button type="button" @click="controller.stopCamera()">{{ $t('realtime.stopCamera') }}</button></p>
    <p v-if="state.status === 'recording'">{{ state.turnSeconds }} / 60 {{ $t('realtime.seconds') }}</p>
    <p v-if="state.transcript" class="realtime-transcript">{{ state.transcript }}</p>
    <div class="realtime-actions">
     <button v-if="!autoMode && state.status === 'active'" type="button" :disabled="state.materialInput?.locked||state.materialResponsePending" @click="controller.beginTurn()">{{ $t('realtime.record') }}</button>
     <button v-if="!autoMode && state.status === 'recording'" type="button" @click="controller.commitTurn()">{{ $t('realtime.commit') }}</button>
     <button v-if="autoMode && ['active','responding'].includes(state.status) && !state.listening && !state.listeningRequested" type="button" :disabled="state.listeningPending||state.materialInput?.locked||state.materialResponsePending" @click="controller.startListening()">{{ $t('realtime.startListening') }}</button><button v-if="autoMode && (state.listening || state.listeningRequested)" type="button" @click="controller.pauseListening()">{{ $t('realtime.pauseListening') }}</button>
     <button v-if="materialsAvailable&&sessionLive&&!state.materialInput?.locked&&['active','responding','recording','requesting'].includes(state.status)" type="button" @click="controller.pauseForMaterials()">暂停并补充材料</button>
     <button v-if="state.responseId && state.status !== 'interrupting'" type="button" @click="controller.interrupt()">{{ $t('realtime.interrupt') }}</button>
     <button v-if="sessionLive" type="button" class="realtime-end" @click="finish">{{ $t('realtime.end') }}</button>
    </div>
    <RealtimeMaterials v-if="sessionLive&&materialsAvailable" :input="state.materialInput" :conversation-id="conversationId" :identity="identity" :session-id="state.session?.id||''" :epoch="state.session?.control_epoch||0" @pause="controller.pauseForMaterials()" @submit="(text,rows)=>controller.submitMaterials(text,rows)" @resume="controller.resumeMaterials()" @cancel="controller.cancelMaterials()" @refresh="controller.refreshMaterials()" />
    <p v-if="state.injections.length" class="realtime-note">{{ $t('realtime.injectionNote') }}</p>
    <ul v-if="state.injections.length" class="realtime-injections"><li v-for="item in state.injections.slice(-5)" :key="item.id">{{ $t('realtime.savedMessage') }} {{ item.message_id }} · {{ $t('realtime.injection_' + item.status) }}</li></ul>
    <p v-if="['failed','existing'].includes(state.status) && sessionLive" class="realtime-note">{{ $t('realtime.recoveryNote') }}</p><button v-if="['failed','existing'].includes(state.status) && sessionLive" type="button" :disabled="state.networkAvailable === false" @click="recover">{{ $t('realtime.reconnect') }}</button>
    <p class="realtime-note">{{ $t('realtime.draftNote') }}</p>
   </div>
  </aside>
 </section>
</template>
<script>
import {markRaw} from 'vue';
import realtimeApi from '../apis/module/realtime';
import {createRealtimeSession,REALTIME_TERMINAL} from '../realtime/session.mjs';
import {realtimeMaterialsAllowed} from '../realtime/materials.mjs';
import RealtimeMaterials from './RealtimeMaterials.vue';
export default {
 name:'AIRealtimeOverlay',components:{RealtimeMaterials},props:{conversationId:{type:String,default:''},identity:{type:String,default:''},allowed:Boolean},emits:['state','history'],
 data:()=>({controller:null,state:{status:'idle',session:null,error:'',injections:[]},microphone:false,camera:false,turnMode:'manual',autoAvailable:false,materialsAvailable:false,capabilityRequest:null,consentVisible:false,minimized:false,dismissed:false,clientId:'',signature:'',timer:null,disposed:false}),
 computed:{retryLocked(){return !!this.signature&&this.state.status==='failed'&&!this.state.session;},autoMode(){return (this.state.turnDetectionMode || this.state.session?.turn_detection_mode) === 'server_vad';},statusKey(){if(this.autoMode && this.state.listeningPending && this.state.status === 'active')return 'realtime.listeningPending';if(this.autoMode && this.state.status === 'active')return this.state.listening ? (this.state.speechActive ? 'realtime.speechActive' : 'realtime.listening') : 'realtime.autoReady';return 'realtime.status_' + this.state.status;},context(){return JSON.stringify([this.conversationId,this.identity,this.allowed]);},sessionLive(){return !!this.state.session&&!REALTIME_TERMINAL.has(this.state.session.status);},terminal(){return REALTIME_TERMINAL.has(this.state.status)&&!this.sessionLive;},visible(){return !this.dismissed&&!['idle','unavailable','ended'].includes(this.state.status);}},
 watch:{context:{flush:'sync',handler(){this.reset();}}},
 mounted(){this.reset();},beforeUnmount(){this.disposed=true;this.capabilityRequest?.abort();clearInterval(this.timer);this.controller?.dispose();},
 methods:{
  reset(){this.capabilityRequest?.abort();this.autoAvailable=false;this.materialsAvailable=false;clearInterval(this.timer);this.controller?.dispose();this.microphone=false;this.camera=false;this.turnMode='manual';this.consentVisible=false;this.dismissed=false;this.clientId='';this.signature='';this.controller=markRaw(createRealtimeSession({client:realtimeApi,changed:state=>{this.state=state;this.$emit('state',state);},history:()=>this.$emit('history')}));this.state=this.controller.snapshot();this.$emit('state',this.state);if(this.allowed&&this.conversationId&&this.identity){this.controller.discover(this.conversationId);const request=new AbortController();this.capabilityRequest=request;Promise.all([realtimeApi.capabilities(request.signal),realtimeApi.appCapabilities?.(request.signal).catch(()=>null)||Promise.resolve(null)]).then(([data,app])=>{if(request.signal.aborted)return;this.autoAvailable=Array.isArray(data?.turn_detection_modes)&&data.turn_detection_modes.includes('server_vad');this.materialsAvailable=realtimeMaterialsAllowed(app,data);this.controller.configureMaterials?.(this.materialsAvailable);}).catch(()=>{});this.timer=setInterval(()=>this.controller.refresh(),2000);}},
  async finish(){const owner=this.controller;await owner?.end();if(!this.disposed&&owner===this.controller&&this.state.status==='ended'&&!this.sessionLive)this.dismissed=true;},
  shutdown(){return this.finish();},
  stopLocalForIdentity(){this.capabilityRequest?.abort();clearInterval(this.timer);this.controller?.dispose();},
  openConsent(){const opening=!this.consentVisible;if(opening&&this.state.session&&REALTIME_TERMINAL.has(this.state.session.status)){this.microphone=false;this.camera=false;this.clientId='';this.signature='';}this.consentVisible=opening;},
  async recover(){if(await this.controller.recover()){this.microphone=false;this.camera=false;this.clientId='';this.signature='';this.consentVisible=true;this.dismissed=false;}},
  async start(){if(!this.allowed||!this.microphone||this.sessionLive||(this.turnMode==='server_vad'&&!this.autoAvailable))return;const consent={microphone:this.microphone,camera:this.camera},signature=JSON.stringify([this.conversationId,consent,this.turnMode]);if(signature!==this.signature||(this.state.session&&REALTIME_TERMINAL.has(this.state.session.status))){this.signature=signature;this.clientId=crypto.randomUUID();}this.consentVisible=false;this.dismissed=false;await this.controller.start(this.conversationId,consent,this.clientId,this.turnMode);},
 },
};
</script>
<style scoped>
.realtime-workspace{font-size:var(--admin-font-xs);color:#344968;margin:8px 0;text-align:left}.realtime-workspace button{font:inherit;border:1px solid #ccd9e7;border-radius:6px;padding:7px 10px;background:#f6f9ff;color:#345583;cursor:pointer}.realtime-workspace button:disabled{opacity:.5;cursor:default}.realtime-consent{padding:10px;border:1px solid #dce5f0;border-radius:8px;background:#fff}.realtime-consent label{display:block;margin:9px 0;line-height:1.6}.realtime-consent input{margin-right:7px}.realtime-overlay{position:fixed;right:24px;bottom:24px;z-index:1850;width:330px;max-width:calc(100vw - 24px);max-height:calc(100dvh - 90px);overflow:auto;padding:14px;box-sizing:border-box;border:1px solid #ccdae9;border-radius:14px;background:#fff;box-shadow:0 8px 40px #25486a26}.realtime-overlay header{display:flex;align-items:center;gap:7px}.realtime-overlay header strong{flex:1}.realtime-note{line-height:1.6;color:#667a91;font-size:var(--admin-font-xs)}.realtime-error{color:#b13d30;line-height:1.6}.realtime-transcript{white-space:pre-wrap;overflow-wrap:anywhere;max-height:180px;overflow:auto;padding:8px;background:#f3f7fc;border-radius:6px;line-height:1.6}.realtime-actions{display:flex;flex-wrap:wrap;gap:7px}.realtime-workspace .realtime-end{background:#fff1ef;color:#b13d30;border-color:#f2c1b9}.realtime-injections{padding-left:18px;font-size:var(--admin-font-xs);line-height:1.6;overflow-wrap:anywhere;max-height:100px;overflow:auto}button:focus-visible{outline:2px solid #83a5f8;outline-offset:2px}@media(max-width:600px){.realtime-overlay{right:12px;bottom:12px;width:290px}}
</style>
