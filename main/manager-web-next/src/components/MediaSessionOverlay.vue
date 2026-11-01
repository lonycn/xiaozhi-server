<template>
  <section class="media-workspace" :class="{ 'media-workspace--embedded': embedded }" :aria-label="$t('media.title')">
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
    <aside ref="fullscreenHost" v-if="embedded || state.session && !hidden" class="media-overlay" :aria-label="$t('media.title')">
      <header><strong>{{ $t('media.title') }}</strong><template v-if="embedded"><button type="button" :aria-label="$t(fullscreen ? 'media.exitFullscreen' : 'media.fullscreen')" aria-keyshortcuts="Alt+f" @click="toggleFullscreen">{{ $t(fullscreen ? 'media.exitFullscreen' : 'media.fullscreen') }}</button><button type="button" :aria-expanded="helpOpen" :aria-label="$t('media.shortcutHelp')" @click="helpOpen = !helpOpen">?</button></template><button v-if="!embedded" type="button" :aria-label="$t('media.minimize')" @click="minimized = !minimized">{{ minimized ? '▣' : '−' }}</button><button v-if="terminal && !embedded" type="button" :aria-label="$t('media.close')" @click="hidden = true">×</button></header>
      <p v-if="helpOpen" class="media-keyboard-help">{{ $t('media.shortcutsNote') }}<br />{{ $t('media.keyboardTools') }}</p>
      <p v-if="fullscreenError" role="alert" class="media-error">{{ $t('media.fullscreenFailed') }}</p>
      <p v-if="state.session" class="media-status" role="status">{{ statusLabel }}</p><small v-if="state.session">{{ $t('media.conversation') }} · {{ state.session.conversation_id }}</small>
      <div v-show="embedded || !minimized" class="media-session-body">
        <div ref="stageHost" class="media-stage-host" :class="{ 'media-local-main': embedded && localMain && state.hasLocalVideo }">
        <div ref="mediaHost" class="media-stage" :style="embedded && localMain && state.hasLocalVideo ? pipStyle : null" :tabindex="embedded && localMain ? 0 : -1" @keydown="pipKeydown($event, localMain)" @dblclick.stop.prevent="swapVideo" @pointerdown="startPip($event, localMain)" @pointermove="movePip" @pointerup="endPip" @pointercancel="cancelPip" :class="{ empty: !hasVideo }" :aria-label="$t('media.videoStage')"><div v-if="!hasVideo" class="media-placeholder"><span class="media-placeholder-icon" aria-hidden="true">▣</span><strong>{{ $t(state.session ? 'media.audioOnly' : 'media.waitingTitle') }}</strong><span v-if="!state.session">{{ $t('media.waitingNote') }}</span></div></div>
        <div v-if="embedded" v-show="state.hasLocalVideo" ref="localHost" class="media-local-preview" tabindex="0" :title="$t('media.movePreview')" @keydown="pipKeydown($event, !localMain)" :style="!localMain ? pipStyle : null" :aria-label="$t('media.localPreview')" @dblclick.stop.prevent="swapVideo" @pointerdown="startPip($event, !localMain)" @pointermove="movePip" @pointerup="endPip" @pointercancel="cancelPip"><span>{{ $t('media.localPreview') }}</span></div>
        <button v-if="embedded && state.hasLocalVideo && hasVideo && !captureDisabled" type="button" class="media-swap" :aria-label="$t('media.swapVideo')" aria-keyshortcuts="Alt+w" @click="swapVideo">⇄</button>
        <slot name="stage-overlay" />
        </div>
        <div v-if="state.session" class="media-session-controls">
        <p v-if="state.error || localError" class="media-error" role="alert">{{ $t(state.error || localError) }}</p>
        <p v-if="state.session.error_code" class="media-error">{{ $t('media.serverFailure') }}: {{ state.session.error_code }}</p>
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
        <button v-if="!terminal" type="button" class="media-hangup" @click="hangup">{{ $t('media.hangup') }}</button>
        <small v-if="state.session.cleanup_status && state.session.cleanup_status !== 'completed'">{{ $t('media.cleanup') }} · {{ state.session.cleanup_status }}</small>
        </div>
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
  props:{ conversationId:{type:String,default:''}, requestId:{type:String,default:''}, identity:{type:String,default:''}, userId:{type:String,default:''}, staff:Boolean, writable:Boolean, embedded:{type:Boolean,default:false}, captureDisabled:{type:Boolean,default:false} },
  emits:['changed','frame'],
  data:() => ({ state:{session:null,joined:false,busy:false,error:'',localState:'disconnected'}, controller:null, io:null, timer:null, epoch:0, disposed:false, pollBusy:false, available:false, unavailable:false, invitations:[], lastTransfer:null, inviting:false, hidden:false, minimized:false, hasVideo:false, choice:emptyChoice(), targetId:'', experts:[], localError:'', captureBusy:false, captureController:null, createId:'', createSignature:'', createTerminalId:'', transferId:'', transferSignature:'', capturePending:null, fullscreen:false, fullscreenError:false, helpOpen:false, localMain:false, pipPosition:{x:.7,y:.05}, pipDrag:null, lastTap:null, lastTouchSwap:0 }),
  computed:{
    pipStyle() { return { left: `${this.pipPosition.x * 100}%`, top: `${this.pipPosition.y * 100}%` }; },
    terminal() { return MEDIA_TERMINAL.has(this.state.session?.status); }, liveSession() { return this.state.session && !this.terminal; },
    own() { return this.state.session?.participants?.find(p => String(p.user_id) === this.userId && p.room_generation === this.state.session.current_generation); },
    transfer() { return this.state.session?.pending_transfer || this.lastTransfer; }, pendingTransfer() { return TRANSFER_PENDING.has(this.transfer?.status); },
    incoming() { return this.state.session?.status === 'invited' && (this.own?.status === 'invited' || this.invitations.some(i => i.type === 'session' && i.session_id === this.state.session.id)); },
    customerPending() { return this.own?.role === 'customer' && !this.transfer?.customer_approved; },
    targetPending() { return this.transfer?.target_agent_user_id === this.userId && !this.transfer?.target_accepted; },
    sourceAgent() { return this.staff && this.state.session?.agent_user_id === this.userId; },
    statusLabel() { const status = this.state.session?.status; if (status === 'active' && this.state.localState === 'connected' && this.state.mediaStalled) return this.$t('media.waitingForMedia'); if (status === 'active' && this.state.localState !== 'connected') return this.$t('media.locallyDisconnected'); return this.$t('media.status_' + status); },
    canCapture() { const s=this.state.session,customer=s?.participants?.find(p => p.role === 'customer' && p.room_generation === s.current_generation); return !this.captureDisabled && this.sourceAgent && s.status === 'active' && this.state.localState === 'connected' && !this.state.mediaStalled && customer?.consent?.camera && customer?.consent?.allow_snapshots && this.hasVideo; },
  },
  watch:{
    identity:{flush:'sync',handler(){this.reset();}},
    conversationId:{flush:'sync',handler(value){if(this.state.session?.conversation_id!==value)this.reset();}},
    writable(value) { if (!value && this.state.session?.conversation_id === this.conversationId && !this.pendingTransfer) this.controller?.stop(); },
  },
  mounted(){this.reset();document.addEventListener('fullscreenchange',this.fullscreenChanged);document.addEventListener('keydown',this.mediaKeydown);},
  beforeUnmount(){document.removeEventListener('fullscreenchange',this.fullscreenChanged);document.removeEventListener('keydown',this.mediaKeydown);this.disposed=true;clearInterval(this.timer);this.io?.abort();this.captureController?.abort();this.controller?.dispose();},
  methods:{
    fullscreenChanged() { this.fullscreen = document.fullscreenElement === this.$refs.fullscreenHost; this.cancelPip(); },
    async toggleFullscreen() {
      if (!this.embedded) return; this.fullscreenError = false;
      try { if (document.fullscreenElement === this.$refs.fullscreenHost) await document.exitFullscreen(); else if (this.$refs.fullscreenHost?.requestFullscreen) await this.$refs.fullscreenHost.requestFullscreen(); else throw new Error('NO_FULLSCREEN'); }
      catch { this.fullscreenError = true; } finally { this.fullscreenChanged(); }
    },
    hangup() { if (!this.embedded || window.confirm(this.$t('media.hangupConfirm'))) return this.controller?.end(); },
    mediaKeydown(event) {
      if (!this.embedded || event.defaultPrevented || event.repeat || event.isComposing || event.keyCode === 229 || event.ctrlKey || event.metaKey || event.target?.closest?.('input,textarea,select,[contenteditable]:not([contenteditable="false"]),[role="textbox"]')) return;
      if (!event.altKey) { if (event.key === '?') { event.preventDefault(); this.helpOpen = !this.helpOpen; } return; }
      const code = event.code || `Key${String(event.key).toUpperCase()}`;
      if (code === 'KeyF') { event.preventDefault(); this.toggleFullscreen(); return; }
      if (!this.state.session || this.terminal) return;
      if (code === 'KeyH') { event.preventDefault(); this.hangup(); return; }
      if (code === 'KeyW') { event.preventDefault(); this.swapVideo(); return; }
      if (code === 'KeyS') { if (this.canCapture && !this.captureBusy && !this.state.busy) { event.preventDefault(); this.capture(); } return; }
      if (!this.state.joined || this.state.busy) return;
      if (code === 'KeyM') { event.preventDefault(); this.controller.toggle('microphone'); }
      if (code === 'KeyV' && this.own?.consent?.camera) { event.preventDefault(); this.controller.toggle('camera'); }
    },
    swapVideo(event) {
      if (!this.embedded || !this.state.hasLocalVideo || !this.hasVideo || this.captureDisabled || (event?.type === 'dblclick' && Date.now() - this.lastTouchSwap < 400)) return;
      this.localMain = !this.localMain; this.cancelPip();
    },
    startPip(event, small) {
      if (!this.embedded || !this.state.hasLocalVideo || this.captureDisabled || event.button > 0 || event.isPrimary === false) return;
      if (small) { event.preventDefault(); event.currentTarget.setPointerCapture?.(event.pointerId); }
      this.pipDrag = { id:event.pointerId, x:event.clientX, y:event.clientY, position:{...this.pipPosition}, small, moved:false };
    },
    movePip(event) {
      const drag=this.pipDrag, host=this.$refs.stageHost;if (!drag || drag.id !== event.pointerId || !drag.small || !host) return;
      const rect=host.getBoundingClientRect(), pip=event.currentTarget.getBoundingClientRect();if (!rect.width || !rect.height) return;
      const dx=event.clientX-drag.x,dy=event.clientY-drag.y;drag.moved ||= Math.hypot(dx,dy)>5;
      this.pipPosition={x:Math.max(0,Math.min(1-pip.width/rect.width,drag.position.x+dx/rect.width)),y:Math.max(0,Math.min(1-pip.height/rect.height,drag.position.y+dy/rect.height))};
    },
    endPip(event) {
      const drag=this.pipDrag;if (!drag || drag.id!==event.pointerId) return;this.movePip(event);event.currentTarget.releasePointerCapture?.(event.pointerId);this.pipDrag=null;
      if (!drag.moved && event.pointerType==='touch') { const now=Date.now(),last=this.lastTap;if(last&&now-last.time<320&&Math.hypot(event.clientX-last.x,event.clientY-last.y)<24){this.swapVideo();this.lastTouchSwap=now;this.lastTap=null;}else this.lastTap={time:now,x:event.clientX,y:event.clientY}; }
    },
    pipKeydown(event, small) {
      if (!small || this.captureDisabled || event.isComposing || event.altKey || event.ctrlKey || event.metaKey) return;
      const step = { ArrowLeft:[-.05,0], ArrowRight:[.05,0], ArrowUp:[0,-.05], ArrowDown:[0,.05] }[event.key];
      if (step) { event.preventDefault(); this.pipPosition={x:Math.max(0,Math.min(.73,this.pipPosition.x+step[0])),y:Math.max(0,Math.min(.69,this.pipPosition.y+step[1]))}; }
    },
    cancelPip() { this.pipDrag=null; },
    reset(){
      ++this.epoch;this.io?.abort();this.captureController?.abort();this.io=markRaw(new AbortController());this.controller?.dispose();this.pollBusy=false;this.captureBusy=false;this.capturePending=null;this.invitations=[];this.lastTransfer=null;this.choice=emptyChoice();this.createId='';this.createSignature='';this.createTerminalId='';this.transferId='';this.transferSignature='';this.inviting=false;this.hidden=false;this.hasVideo=false;this.localError='';this.localMain=false;this.lastTap=null;this.cancelPip();
      this.controller=markRaw(createMediaSession({client:mediaApi,userId:()=>this.userId,changed:state=>{const previous=this.state.session,next=state.session;if(previous?.pending_transfer&&!next?.pending_transfer)this.refreshTransfer(previous.pending_transfer.id);if(next?.pending_transfer)this.lastTransfer=null;if(previous&&(!next||previous.id!==next.id||previous.current_generation!==next.current_generation||previous.control_epoch!==next.control_epoch||MEDIA_TERMINAL.has(next.status)||state.error==='media.denied'||(this.state.joined&&!state.joined))){this.captureController?.abort();this.capturePending=null;this.captureBusy=false;}this.state=state;this.hasVideo=state.hasVideo;if(!state.hasLocalVideo){this.localMain=false;this.cancelPip();}this.$emit('changed',state.session);},onTrack:(element,track)=>{this.$refs.mediaHost?.appendChild(element);if(track.kind==='video')this.hasVideo=true;},onLocalTrack:(element)=>{if(this.embedded)this.$refs.localHost?.appendChild(element);else element.remove();}}));
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
    async startTransfer(){if(!/^[1-9][0-9]*$/.test(this.targetId))return;const signature=this.targetId;if(this.transferSignature!==signature){this.transferId=crypto.randomUUID();this.transferSignature=signature;}await this.controller.transfer(this.targetId,this.transferId);if(!this.state.error&&signature===this.targetId){this.targetId='';this.transferId='';this.transferSignature='';}},
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

.media-workspace--embedded{display:flex;flex-direction:column;min-height:330px;height:100%;font-size:12px}.media-workspace--embedded .media-launch{padding:10px 12px;flex:0 0 auto}.media-workspace--embedded .media-launch:empty{padding:0}.media-workspace--embedded .media-overlay{position:relative;inset:auto;width:100%;max-width:none;max-height:none;flex:1 1 auto;min-height:0;z-index:auto;display:flex;flex-direction:column;padding:12px;border:0;border-radius:0;background:#f7fafb;box-shadow:none;color:#31564f}.media-workspace--embedded .media-overlay header{margin-bottom:6px}.media-workspace--embedded .media-status{margin:4px 0}.media-workspace--embedded .media-session-body{display:flex;flex-direction:column;flex:1 1 auto;min-height:0}.media-workspace--embedded .media-stage{flex:1 1 auto;min-height:230px;max-height:48dvh;width:100%;aspect-ratio:16/9;margin:10px 0;background:radial-gradient(ellipse at 50% 40%,#1c3938,#101f24);border:1px solid #283e43;border-radius:9px;overflow:hidden}.media-placeholder{display:flex;flex-direction:column;align-items:center;gap:10px;text-align:center;padding:20px;line-height:1.5}.media-placeholder-icon{font-size:34px;opacity:.6}.media-placeholder strong{font-size:14px;font-weight:500}.media-placeholder>span:last-child{max-width:260px;font-size:12px;opacity:.7}.media-session-controls{min-width:0}.media-workspace--embedded .media-session-controls{display:flex;flex-wrap:wrap;gap:8px;align-items:center}.media-workspace--embedded .media-session-controls>.media-note{flex:1 1 100%;margin:0;font-size:11px}.media-workspace--embedded .media-consent,.media-workspace--embedded .media-transfer{flex:1 1 100%;margin:0;padding:10px;background:#eef5f2;border-color:#d2e3dc}.media-workspace--embedded .media-transfer-form{flex:1 1 auto;margin:0}.media-workspace--embedded .media-transfer-form label{display:flex;align-items:center;gap:5px;flex-wrap:wrap}.media-workspace--embedded .media-hangup{margin-left:auto}.media-workspace--embedded button{color:#236655;border-color:#cbded7}.media-workspace--embedded .media-hangup{color:#b43c3b;border-color:#e8c5c5}.media-workspace--embedded .media-error{flex:1 1 100%}@media(max-width:600px){.media-workspace--embedded{min-height:280px}.media-workspace--embedded .media-stage{min-height:180px;max-height:40dvh}.media-workspace--embedded .media-overlay{padding:10px}.media-workspace--embedded .media-session-controls{gap:6px}.media-workspace--embedded .media-transfer-form{flex-basis:100%}}

.media-stage-host{position:relative;min-width:0}.media-workspace--embedded .media-stage-host{flex:1 1 auto;min-height:230px;max-height:48dvh;width:100%;aspect-ratio:16/9;margin:10px 0;border:1px solid #283e43;border-radius:9px;overflow:hidden;box-sizing:border-box}.media-workspace--embedded .media-stage{position:absolute;inset:0;margin:0;min-height:0;max-height:none;aspect-ratio:auto;width:100%;height:100%;border:0;border-radius:0;box-sizing:border-box;align-items:center;justify-content:center;color:#d2e4e0}.media-workspace--embedded .media-stage:deep(video){display:block;width:100%;height:100%;max-height:none;object-fit:contain}.media-workspace--embedded .media-hangup{width:auto;margin-top:0}.media-workspace--embedded .media-placeholder{max-width:100%;box-sizing:border-box}@media(max-width:600px){.media-workspace--embedded .media-stage-host{min-height:210px;max-height:40dvh}}

.media-keyboard-help{padding:9px;background:#e8f1ed;border-radius:6px;line-height:1.7}.media-local-preview{position:absolute;z-index:2;width:27%;height:31%;overflow:hidden;border:2px solid #cce2dd;border-radius:7px;background:#102023;box-shadow:0 3px 15px #0005;touch-action:none;cursor:grab;box-sizing:border-box}.media-local-preview:deep(video){display:block;width:100%;height:100%;object-fit:contain}.media-local-preview>span{position:absolute;bottom:3px;left:5px;color:white;text-shadow:0 1px 3px #000;font-size:10px;pointer-events:none}.media-workspace--embedded .media-local-main .media-local-preview{inset:0;width:100%;height:100%;border:0;border-radius:0;z-index:0;cursor:default}.media-workspace--embedded .media-local-main .media-stage{inset:auto;z-index:2;width:27%;height:31%;border:2px solid #cce2dd;border-radius:7px;touch-action:none;cursor:grab}.media-swap{position:absolute;right:8px;bottom:8px;z-index:3;font-size:18px}.media-overlay:fullscreen{display:flex;flex-direction:column;width:100vw;height:100dvh;max-width:none;max-height:none;box-sizing:border-box;overflow:auto;background:#f7fafb;padding:16px}.media-workspace--embedded .media-overlay:fullscreen .media-stage-host{flex:1 1 auto;min-height:230px;max-height:none;aspect-ratio:auto}.media-workspace--embedded .media-overlay:fullscreen .media-session-controls{flex:0 0 auto}
</style>
