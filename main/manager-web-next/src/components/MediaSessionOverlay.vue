<template>
  <section class="media-workspace" :aria-label="$t('media.title')">
    <div class="media-launch">
      <button v-if="available && writable && requestId && !liveSession" type="button" :disabled="state.busy" @click="inviting = !inviting">{{ $t('media.invite') }}</button>
      <small v-if="unavailable">{{ $t('media.unavailable') }}</small>
      <button v-for="invite in invitations" :key="`${invite.type}:${invite.id}`" type="button" :disabled="state.busy" @click="openInvitation(invite)">{{ $t(invite.type === 'transfer' ? 'media.transferInvitation' : 'media.incoming') }} · {{ invite.conversation_id }}</button>
    </div>
    <p v-if="localError && !state.session && !unavailable" class="media-error" role="alert">{{ $t(localError) }}</p>
    <div v-if="inviting" class="media-consent">
      <p>{{ $t('media.consentNote') }}</p><ConsentChoices v-model="choice" :staff="staff" />
      <button type="button" :disabled="!choice.microphone || state.busy" @click="invite">{{ $t('media.sendInvitation') }}</button><button type="button" @click="inviting = false">{{ $t('media.close') }}</button>
    </div>
    <aside v-if="state.session && !hidden" class="media-overlay" :aria-label="$t('media.title')">
      <header><strong>{{ $t('media.title') }}</strong><button type="button" :aria-label="$t('media.minimize')" @click="minimized = !minimized">{{ minimized ? '▣' : '−' }}</button><button v-if="terminal" type="button" :aria-label="$t('media.close')" @click="hidden = true">×</button></header>
      <p class="media-status" role="status">{{ statusLabel }}</p><small>{{ $t('media.conversation') }} · {{ state.session.conversation_id }}</small>
      <div v-show="!minimized">
        <p v-if="state.error || localError" class="media-error" role="alert">{{ $t(state.error || localError) }}</p>
        <p v-if="state.session.error_code" class="media-error">{{ $t('media.serverFailure') }}: {{ state.session.error_code }}</p>
        <div ref="mediaHost" class="media-stage" :class="{ empty: !hasVideo }"><span v-if="!hasVideo">{{ $t('media.audioOnly') }}</span></div>
        <p v-if="!terminal" class="media-note">{{ $t('media.textContinues') }}</p><p v-if="!terminal && own?.role === 'customer' && own?.consent?.allow_snapshots" class="media-note">{{ $t('media.snapshotConsentActive') }}</p>
        <div v-if="incoming" class="media-consent"><p>{{ $t('media.consentNote') }}</p><ConsentChoices v-model="choice" :staff="staff" /><button type="button" :disabled="!choice.microphone || state.busy" @click="accept">{{ $t('media.accept') }}</button><button type="button" :disabled="state.busy" @click="controller.reject()">{{ $t('media.reject') }}</button></div>
        <div v-else-if="!terminal && (!state.joined || state.localState === 'disconnected') && own?.consent?.microphone && !targetPending" class="media-consent"><p>{{ $t('media.reconnectNote') }}</p><ConsentChoices v-model="choice" :staff="staff" :snapshots-available="false" /><button type="button" :disabled="!choice.microphone || state.busy" @click="controller.reconnect(choice)">{{ $t('media.reconnect') }}</button></div>
        <div v-if="state.joined && !terminal" class="media-actions">
          <button type="button" :aria-pressed="state.microphone" :disabled="state.busy" @click="controller.toggle('microphone')">{{ $t(state.microphone ? 'media.mute' : 'media.unmute') }}</button>
          <button v-if="own?.consent?.camera" type="button" :aria-pressed="state.camera" :disabled="state.busy" @click="controller.toggle('camera')">{{ $t(state.camera ? 'media.cameraOff' : 'media.cameraOn') }}</button>
          <button v-if="state.playbackBlocked" type="button" :disabled="state.busy" @click="controller.resumeAudio()">{{ $t('media.playAudio') }}</button>
        </div>
        <div v-if="transfer" class="media-transfer"><strong>{{ $t('media.transfer') }} · {{ $t('media.transfer_' + transfer.status) }}</strong>
          <template v-if="transfer.status === 'invited' && (customerPending || targetPending)"><p>{{ $t('media.transferConsent') }}</p><ConsentChoices v-model="choice" :staff="staff" /><button type="button" :disabled="!choice.microphone || state.busy" @click="controller.transferAction(customerPending ? 'approve' : 'accept', choice)">{{ $t(customerPending ? 'media.approveTransfer' : 'media.acceptTransfer') }}</button><button type="button" :disabled="state.busy" @click="controller.transferAction('reject')">{{ $t('media.reject') }}</button></template>
          <p v-if="['preparing','ready'].includes(transfer.status)">{{ $t('media.transferPreparing') }}</p><p v-if="transfer.status === 'committing'">{{ $t('media.transferCommitting') }}</p>
          <button v-if="sourceAgent && pendingTransfer" type="button" :disabled="state.busy" @click="controller.transferAction('cancel')">{{ $t('media.cancelTransfer') }}</button>
        </div>
        <form v-if="sourceAgent && !terminal && !pendingTransfer" class="media-transfer-form" @submit.prevent="startTransfer"><label>{{ $t('media.targetId') }} <select v-model="targetId" :disabled="state.busy" @focus="loadExperts"><option value="">{{ $t('media.chooseExpert') }}</option><option v-for="expert in experts" :key="expert.user_id" :value="expert.user_id" :disabled="!expert.available || expert.user_id === userId">{{ expert.display_name }} · {{ $t(expert.available ? 'media.expertAvailable' : 'media.expertUnavailable') }}</option></select></label><button type="submit" :disabled="state.busy || !/^[1-9][0-9]*$/.test(targetId)">{{ $t('media.transfer') }}</button></form>
        <button v-if="canCapture" type="button" :disabled="captureBusy || state.busy" @click="capture">{{ $t(captureBusy ? 'media.capturing' : 'media.capture') }}</button>
        <p v-if="canCapture" class="media-note">{{ $t('media.captureNote') }}</p>
        <button v-if="!terminal" type="button" class="media-hangup" @click="controller.end()">{{ $t('media.hangup') }}</button>
        <small v-if="state.session.cleanup_status && state.session.cleanup_status !== 'completed'">{{ $t('media.cleanup') }} · {{ state.session.cleanup_status }}</small>
      </div>
    </aside>
  </section>
</template>
<script>
import { defineComponent, h, markRaw } from 'vue';
import mediaApi from '../apis/module/media';
import attachmentApi from '../apis/module/attachment';
import { fileSha256 } from '../utils/attachments.mjs';
import { abortableDelay } from '../utils/aiStream.mjs';
import { createMediaSession, MEDIA_TERMINAL, TRANSFER_PENDING, mediaErrorKey } from '../media/mediaSession.mjs';
const emptyChoice = () => ({ microphone:false, camera:false, allow_snapshots:false });
const ConsentChoices = defineComponent({
  props: { modelValue:Object, staff:Boolean, snapshotsAvailable:{type:Boolean,default:true} }, emits:['update:modelValue'],
  render() { return h('div', { class:'media-choices' }, ['microphone','camera',...(this.snapshotsAvailable && !this.staff && this.modelValue.camera ? ['allow_snapshots'] : [])].map(key => h('label', [h('input', { type:'checkbox', checked:this.modelValue[key], onChange:event => { const next = { ...this.modelValue, [key]:event.target.checked }; if (key === 'camera' && !next.camera) next.allow_snapshots = false; this.$emit('update:modelValue', next); } }), this.$t('media.consent_' + key)]))); },
});
export default {
  name:'MediaSessionOverlay', components:{ ConsentChoices },
  props:{ conversationId:{type:String,default:''}, requestId:{type:String,default:''}, identity:{type:String,default:''}, userId:{type:String,default:''}, staff:Boolean, writable:Boolean },
  emits:['changed','frame'],
  data:() => ({ state:{session:null,joined:false,busy:false,error:'',localState:'disconnected'}, controller:null, io:null, timer:null, epoch:0, disposed:false, pollBusy:false, available:false, unavailable:false, invitations:[], lastTransfer:null, inviting:false, hidden:false, minimized:false, hasVideo:false, choice:emptyChoice(), targetId:'', experts:[], localError:'', captureBusy:false, captureController:null, createId:'', createSignature:'', createTerminalId:'', transferId:'', transferSignature:'', capturePending:null }),
  computed:{
    terminal() { return MEDIA_TERMINAL.has(this.state.session?.status); }, liveSession() { return this.state.session && !this.terminal; },
    own() { return this.state.session?.participants?.find(p => String(p.user_id) === this.userId && p.room_generation === this.state.session.current_generation); },
    transfer() { return this.state.session?.pending_transfer || this.lastTransfer; }, pendingTransfer() { return TRANSFER_PENDING.has(this.transfer?.status); },
    incoming() { return this.state.session?.status === 'invited' && (this.own?.status === 'invited' || this.invitations.some(i => i.type === 'session' && i.session_id === this.state.session.id)); },
    customerPending() { return this.own?.role === 'customer' && !this.transfer?.customer_approved; },
    targetPending() { return this.transfer?.target_agent_user_id === this.userId && !this.transfer?.target_accepted; },
    sourceAgent() { return this.staff && this.state.session?.agent_user_id === this.userId; },
    statusLabel() { const status = this.state.session?.status; if (status === 'active' && this.state.localState !== 'connected') return this.$t('media.locallyDisconnected'); return this.$t('media.status_' + status); },
    canCapture() { const s=this.state.session,customer=s?.participants?.find(p => p.role === 'customer' && p.room_generation === s.current_generation); return this.sourceAgent && s.status === 'active' && this.state.localState === 'connected' && customer?.consent?.camera && customer?.consent?.allow_snapshots && this.hasVideo; },
  },
  watch:{
    identity:{flush:'sync',handler(){this.reset();}},
    conversationId:{flush:'sync',handler(value){if(this.state.session?.conversation_id!==value)this.reset();}},
    writable(value) { if (!value && this.state.session?.conversation_id === this.conversationId && !this.pendingTransfer) this.controller?.stop(); },
  },
  mounted(){this.reset();},
  beforeUnmount(){this.disposed=true;clearInterval(this.timer);this.io?.abort();this.captureController?.abort();this.controller?.dispose();},
  methods:{
    reset(){
      ++this.epoch;this.io?.abort();this.captureController?.abort();this.io=markRaw(new AbortController());this.controller?.dispose();this.pollBusy=false;this.captureBusy=false;this.capturePending=null;this.invitations=[];this.lastTransfer=null;this.choice=emptyChoice();this.createId='';this.createSignature='';this.createTerminalId='';this.transferId='';this.transferSignature='';this.inviting=false;this.hidden=false;this.hasVideo=false;this.localError='';
      this.controller=markRaw(createMediaSession({client:mediaApi,userId:()=>this.userId,changed:state=>{const previous=this.state.session,next=state.session;if(previous?.pending_transfer&&!next?.pending_transfer)this.refreshTransfer(previous.pending_transfer.id);if(next?.pending_transfer)this.lastTransfer=null;if(previous&&(!next||previous.id!==next.id||previous.current_generation!==next.current_generation||previous.control_epoch!==next.control_epoch||MEDIA_TERMINAL.has(next.status)||state.error==='media.denied'||(this.state.joined&&!state.joined))){this.captureController?.abort();this.capturePending=null;this.captureBusy=false;}this.state=state;this.hasVideo=state.hasVideo;this.$emit('changed',state.session);},onTrack:(element,track)=>{this.$refs.mediaHost?.appendChild(element);if(track.kind==='video')this.hasVideo=true;}}));
      this.state=this.controller.snapshot();clearInterval(this.timer);this.timer=this.userId?setInterval(() => this.poll(),2500):null;this.poll();
    },
    async poll(){
      if(this.disposed || this.pollBusy || !this.userId)return;this.pollBusy=true;const epoch=this.epoch,signal=this.io.signal;
      try{
        const items=[];let after='0',more=true;
        while(more){const page=await mediaApi.invitations(after,signal);if(epoch!==this.epoch || signal.aborted)return;items.push(...page.items);more=page.has_more;if(more&&(!/^\d+$/.test(String(page.next_cursor))||BigInt(page.next_cursor)<=BigInt(after)))throw new Error('INVALID_CURSOR');after=String(page.next_cursor);}
        this.invitations=items.filter(i=>!this.conversationId||this.staff||i.conversation_id===this.conversationId);this.available=true;this.unavailable=false;
        if(!this.state.session&&this.conversationId){const session=await mediaApi.current(this.conversationId,signal);if(epoch!==this.epoch)return;if(session){await this.controller.load(session);this.hidden=false;}}
        if(epoch===this.epoch){await this.controller.refresh();if(this.sourceAgent&&!this.pendingTransfer&&!this.experts.length)await this.loadExperts();}
      }catch(error){if(epoch===this.epoch&&error.name!=='AbortError'){this.localError=mediaErrorKey(error);if(error.status===404){this.available=false;this.unavailable=true;}if([401,403].includes(error.status))this.controller.stop();}}
      finally{if(epoch===this.epoch)this.pollBusy=false;}
    },
    async openInvitation(invite){const epoch=this.epoch;try{const session=await mediaApi.get(invite.session_id,this.io.signal);if(epoch!==this.epoch)return;await this.controller.load(session);this.choice=emptyChoice();this.hidden=false;this.minimized=false;}catch(error){if(epoch===this.epoch)this.localError=mediaErrorKey(error);}},
    async invite(){
      if(!this.choice.microphone||!this.requestId)return;
      // The controller clears a terminal session before creating. Retain its ID
      // across an uncertain response so a retry remains the same create intent.
      const terminalId=this.terminal ? this.state.session.id : (!this.state.session ? this.createTerminalId : '');
      const signature=JSON.stringify([this.requestId,this.choice,terminalId]);
      if(this.createSignature!==signature){this.createId=crypto.randomUUID();this.createSignature=signature;this.createTerminalId=terminalId;}
      const epoch=this.epoch,controller=this.controller;
      await controller.create(this.requestId,this.choice,this.createId);
      if(epoch===this.epoch && controller===this.controller && !this.state.error){this.inviting=false;this.hidden=false;}
    },
    async accept(){await this.controller.accept(this.choice);},
    async refreshTransfer(id){const epoch=this.epoch;try{const transfer=await mediaApi.transferGet(id,this.io.signal);if(epoch===this.epoch)this.lastTransfer=transfer;}catch(error){if(epoch===this.epoch&&error.name!=='AbortError')this.localError=mediaErrorKey(error);}},
    async loadExperts(){const epoch=this.epoch;try{const items=[];let after='0',more=true;while(more){const page=await mediaApi.experts(after,this.io.signal);if(epoch!==this.epoch)return;items.push(...page.items);more=page.has_more;if(more&&(!/^\d+$/.test(String(page.next_cursor))||BigInt(page.next_cursor)<=BigInt(after)))throw new Error('INVALID_CURSOR');after=String(page.next_cursor);}this.experts=items;}catch(error){if(epoch===this.epoch&&error.name!=='AbortError')this.localError=mediaErrorKey(error);}},
    async startTransfer(){if(!/^[1-9][0-9]*$/.test(this.targetId))return;const signature=this.targetId;if(this.transferSignature!==signature){this.transferId=crypto.randomUUID();this.transferSignature=signature;}await this.controller.transfer(this.targetId,this.transferId);if(!this.state.error&&signature===this.targetId){this.targetId='';this.transferId='';}},
    async capture(){
      if(!this.canCapture||this.captureBusy)return;this.captureBusy=true;this.localError='';this.captureController?.abort();this.captureController=markRaw(new AbortController());const epoch=this.epoch,signal=this.captureController.signal,s=this.state.session;
      try{
        const track=this.controller.videoTrack();if(!track?.mediaStreamTrack||track.mediaStreamTrack.readyState!=='live')throw new Error('NO_VIDEO');
        // Keep the exact retry body/attachment after network failure. A new capture is
        // only made after the prior operation completes or its generation is obsolete.
        if(!this.capturePending||this.capturePending.generation!==s.current_generation){
          const video=this.$refs.mediaHost?.querySelector('video');if(!video||video.readyState<2||!video.videoWidth)throw new Error('NO_VIDEO');
          const canvas=document.createElement('canvas');const scale=Math.min(1,1920/video.videoWidth,1080/video.videoHeight);canvas.width=Math.round(video.videoWidth*scale);canvas.height=Math.round(video.videoHeight*scale);canvas.getContext('2d').drawImage(video,0,0);
          const blob=await new Promise((resolve,reject)=>canvas.toBlob(value=>value?resolve(value):reject(new Error('CAPTURE_FAILED')),'image/jpeg',.9));
          if(epoch!==this.epoch||signal.aborted)return;
          this.capturePending={generation:s.current_generation,id:crypto.randomUUID(),uploadId:crypto.randomUUID(),file:new File([blob],'customer-live-frame.jpg',{type:'image/jpeg'}),capturedAt:new Date().toISOString(),attachment:null};
        }
        const pending=this.capturePending;
        if(!pending.attachment){const file=pending.file,sha256=await fileSha256(file);if(epoch!==this.epoch||signal.aborted)return;let attachment=await attachmentApi.init({conversation_id:s.conversation_id,client_upload_id:pending.uploadId,filename:file.name,mime_type:file.type,size:file.size,sha256},signal);if(attachment.status==='pending_upload'){await attachmentApi.upload(attachment,file,signal);attachment=await attachmentApi.complete(attachment.id,signal);}else if(attachment.status==='uploaded'){attachment=await attachmentApi.complete(attachment.id,signal);}pending.attachment=attachment;}
        let attachment=pending.attachment;
        while(!['ready','failed','expired'].includes(attachment.status)){await abortableDelay(1500,signal);attachment=await attachmentApi.get(attachment.id,signal);if(epoch!==this.epoch)return;pending.attachment=attachment;}
        if(attachment.status!=='ready'){this.capturePending=null;throw new Error('INVALID_CAPTURE');}
        if(epoch!==this.epoch||signal.aborted||!this.canCapture||this.state.session.current_generation!==pending.generation)return;
        const frame=await mediaApi.capture(s.id,{expected_epoch:this.state.session.control_epoch,room_generation:pending.generation,client_frame_id:pending.id,source_attachment_id:attachment.id,captured_at:pending.capturedAt},signal);
        if(epoch!==this.epoch||signal.aborted)return;this.capturePending=null;this.$emit('frame',{frame,conversationId:s.conversation_id});
      }catch(error){if(epoch===this.epoch&&error.name!=='AbortError')this.localError=mediaErrorKey(error);}
      finally{if(epoch===this.epoch)this.captureBusy=false;}
    },
  },
};
</script>
<style scoped>
.media-workspace{font-size:12px;text-align:left}.media-launch,.media-actions{display:flex;flex-wrap:wrap;gap:7px;align-items:center}.media-launch:empty{display:none}.media-overlay{position:fixed;right:24px;bottom:24px;width:320px;max-width:calc(100vw - 32px);max-height:calc(100vh - 100px);overflow:auto;z-index:1900;padding:14px;box-sizing:border-box;border:1px solid #ccdae9;border-radius:14px;background:#fff;box-shadow:0 8px 40px #25486a26;color:#344968}.media-overlay header{display:flex;gap:8px;align-items:center}.media-overlay header strong{flex:1}.media-workspace button{border:1px solid #ccd9e7;border-radius:6px;padding:6px 9px;background:#f6f9ff;color:#345583;font:inherit;cursor:pointer}.media-workspace button:disabled{opacity:.5;cursor:default}.media-status{font-weight:600;margin:10px 0 4px}.media-note,.media-overlay small{color:#647991;line-height:1.6}.media-stage{display:flex;flex-direction:column;gap:6px;margin:10px 0;background:#f0f5fa;border-radius:8px;overflow:hidden}.media-stage.empty{padding:18px;text-align:center}.media-stage:deep(video){width:100%;max-height:220px;object-fit:contain}.media-consent{padding:9px 0}.media-consent p{line-height:1.6}.media-consent:deep(label){display:block;margin:9px 0;line-height:1.6}.media-consent:deep(input){margin-right:6px}.media-transfer{border-top:1px solid #e1e8f1;margin-top:10px;padding-top:10px}.media-transfer-form{display:flex;gap:8px;align-items:end;margin-top:12px}.media-transfer-form select{width:100%;box-sizing:border-box;border:1px solid #ccdae9;padding:6px;border-radius:5px}.media-workspace .media-hangup{display:block;width:100%;margin-top:14px;background:#fff1ef;border-color:#f2c1b9;color:#b13d30}.media-error{color:#b13d30;line-height:1.6}@media(max-width:600px){.media-overlay{right:12px;bottom:12px;width:290px}}
</style>
