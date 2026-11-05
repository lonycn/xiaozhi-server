<template>
 <section v-if="input?.locked" class="realtime-materials" aria-label="实时补充材料">
  <h4>暂停并补充材料</h4><p role="status">{{ phaseLabel }}</p>
  <p v-if="input.error" role="alert" class="material-error">{{ input.error }}</p>
  <p v-if="input.material" class="material-note">材料消息已保存 · {{ materialLabel }}<span v-if="input.material.context_truncated"> · 上下文已按上限截取</span></p>
  <template v-if="canEdit">
   <label class="material-label">补充说明<textarea v-model="text" :disabled="input.busy||attempted" maxlength="4000" rows="3" placeholder="补充本轮问题，最多4000字" /></label>
   <AttachmentComposer v-if="!input.busy" :key="scopeKey" v-model="rows" :conversation-id="conversationId" :identity="scopeKey" :allowed-kinds="['image','file']" :max-files="2" :disabled="attempted" limits-label="最多2份图片、PDF或UTF-8文本；各20MiB，上传和处理完成后才能提交" />
   <p v-if="rows.length&&!ready" role="status">原件仍在上传或检查，可重试失败文件；尚未提交给实时模型。</p>
   <button type="button" :disabled="!canSubmit" @click="submit">{{ attempted ? '重试原材料提交' : '保存材料并准备上下文' }}</button>
  </template>
  <p v-if="input.phase==='requested'" class="material-note">正在等待服务端确认未提交音频已清空；此时不能上传。</p>
  <p v-if="input.phase==='context_acknowledged'" class="material-note">实时模型已确认收到上下文。点击恢复只触发本轮回答；听音和摄像头仍保持关闭。</p>
  <div class="material-actions">
   <button v-if="canResume" type="button" :disabled="input.busy" @click="$emit('resume')">{{ input.phase==='resuming' ? '重试恢复确认' : input.phase==='context_acknowledged' ? '恢复并回答一次' : '结束补充，返回通话' }}</button>
   <button v-if="input.pause&&['paused','processing','prepared'].includes(input.phase)" type="button" :disabled="input.busy" @click="cancel">{{ input.phase==='prepared' ? '取消未确认材料并结束通话' : '取消本轮材料并重新清空' }}</button>
   <button type="button" :disabled="input.busy" @click="$emit('refresh')">刷新确认状态</button>
   <button v-if="input.phase==='requested'&&!input.pause" type="button" :disabled="input.busy" @click="$emit('pause')">重试暂停请求</button>
  </div>
  <p class="material-note">补材料期间不采音、不采画面，不走普通文字回答。</p>
 </section>
</template>
<script>
import AttachmentComposer from './AttachmentComposer.vue';
import {materialParts} from '../realtime/materials.mjs';
import {attachmentRowsReady} from '../utils/attachments.mjs';
const labels={requested:'等待音频清空确认',paused:'已确认暂停，可以准备材料',processing:'材料已保存，正在感知处理',prepared:'感知已完成，等待实时上下文确认',context_acknowledged:'上下文已确认，可显式恢复',resuming:'等待恢复命令及服务端确认',failed:'材料处理未完成，请取消本轮后重新准备',ended:'会话已结束',live:'已恢复，设备保持关闭'};
export default {
 name:'RealtimeMaterials',components:{AttachmentComposer},
 props:{input:{type:Object,default:null},conversationId:{type:String,default:''},identity:{type:String,default:''},sessionId:{type:String,default:''},epoch:{type:Number,default:0}},emits:['submit','resume','cancel','pause','refresh'],
 data:()=>({text:'',rows:[],attempted:false}),
 computed:{scopeKey(){return JSON.stringify([this.identity,this.conversationId,this.sessionId,this.epoch]);},phaseLabel(){return labels[this.input?.phase]||'等待权威输入状态';},materialLabel(){return ({queued:'等待处理',dispatched:'正在处理',prepared:'等待上下文确认',acknowledged:'上下文已确认',failed:'处理失败',canceled:'已取消'})[this.input?.material?.status]||'等待确认';},canEdit(){return this.input?.phase==='paused'&&(!this.input.material||['failed','canceled'].includes(this.input.material.status));},ready(){return attachmentRowsReady(this.rows);},canSubmit(){try{return this.canEdit&&!this.input.busy&&materialParts(this.text,this.rows).length>0;}catch{return false;}},canResume(){return this.input?.phase==='resuming'&&!!this.input.error||this.input?.phase==='context_acknowledged'||this.input?.phase==='paused'&&(!this.input.material||['failed','canceled'].includes(this.input.material.status));}},
 watch:{scopeKey:{flush:'sync',handler(){this.text='';this.rows=[];this.attempted=false;}},'input.phase'(phase){if(phase==='paused'&&['failed','canceled'].includes(this.input.material?.status)){this.attempted=false;this.rows=this.rows.map(row=>row.attachment?{...row,clientUploadId:crypto.randomUUID(),attachment:null,status:'paused',progress:0,error:''}:row);}},'input.locked'(value){if(!value){this.text='';this.rows=[];this.attempted=false;}}},
 methods:{submit(){if(!this.canSubmit)return;this.attempted=true;this.$emit('submit',this.text,this.rows);},cancel(){this.attempted=false;this.$emit('cancel');}},
};
</script>
<style scoped>
.realtime-materials{border-top:1px solid #dce5ee;margin-top:12px;padding-top:10px;line-height:1.6}.realtime-materials h4{margin:0;font-size:var(--admin-font-base)}.material-label{display:block}.material-label textarea{display:block;box-sizing:border-box;resize:vertical;max-height:160px;width:100%;font:inherit;padding:8px;border:1px solid #ccd9e7;border-radius:6px}.material-note{color:#65758b;font-size:var(--admin-font-xs)}.material-error{color:#b13d30}.material-actions{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}.realtime-materials button{font:inherit;border:1px solid #ccd9e7;border-radius:6px;padding:7px 9px;background:#f6f9ff;color:#345583}.realtime-materials button:disabled{opacity:.5}.realtime-materials textarea:focus-visible,.realtime-materials button:focus-visible{outline:2px solid #83a5f8;outline-offset:2px}
</style>
