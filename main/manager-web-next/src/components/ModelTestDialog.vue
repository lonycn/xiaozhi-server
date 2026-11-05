<template>
  <el-dialog v-model="open" :title="$t('modelTest.title')" width="850px" :style="{ maxWidth: 'calc(100vw - 28px)' }" append-to-body class="model-test-dialog" :close-on-click-modal="false" @closed="dispose">
    <el-alert :title="$t('modelTest.notice')" type="info" :closable="false" show-icon />
    <div class="test-heading"><div><strong>{{ modelName }}</strong><span class="test-type">{{ type }}</span></div>
      <el-button :loading="starting" :disabled="busy || !modelId" type="primary" @click="start">{{ $t('modelTest.start') }}</el-button></div>
    <el-form label-position="top">
      <el-form-item v-if="!['ASR','VAD'].includes(type)" :label="$t('modelTest.input')">
        <el-input v-model="text" type="textarea" :rows="2" maxlength="500" show-word-limit :placeholder="$t('modelTest.defaultInput')" :disabled="busy" />
      </el-form-item>
      <el-form-item v-if="type === 'RAG'" :label="$t('modelTest.dataset')"><el-input v-model="datasetId" maxlength="100" :placeholder="$t('modelTest.datasetHint')" :disabled="busy" /></el-form-item>
      <el-form-item v-if="['ASR','VLLM'].includes(type)" :label="$t(type === 'ASR' ? 'modelTest.audioInput' : 'modelTest.imageInput')">
        <input type="file" :accept="type === 'ASR' ? '.wav,.mp3' : '.png,.jpg,.jpeg'" :disabled="busy" @change="selectFile" />
        <div class="file-note">{{ $t(type === 'ASR' ? 'modelTest.audioHint' : 'modelTest.imageHint') }}</div>
      </el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="test-error" />
    <section v-if="run" class="test-result" :data-status="run.status" aria-live="polite">
      <div class="result-heading"><el-tag :type="statusColor">{{ $t(`modelTest.status.${run.status}`) }}</el-tag>
        <span v-if="run.durationMs != null">{{ run.durationMs }} ms</span><span>{{ $t('modelTest.scope') }}：{{ $t(`modelTest.scope.${run.scope || 'none'}`) }}</span></div>
      <div v-if="run.result?.configuration?.model" class="file-note"><code>{{ run.result.configuration.model }}</code><span v-if="run.result.configuration.voice"> · <code>{{ run.result.configuration.voice }}</code></span></div>
      <p>{{ run.summary }}</p><p v-if="run.errorCode" class="error-code">{{ $t('modelTest.errorCode') }}：{{ run.errorCode }}</p>
      <pre v-if="run.result?.output" class="model-output">{{ run.result.output }}</pre>
      <div v-if="run.audioAvailable" class="test-audio"><el-button v-if="!audioUrl" :loading="loadingAudio" @click="loadAudio">{{ $t('modelTest.audioResult') }}</el-button><audio v-else :src="audioUrl" controls preload="metadata" /></div>
      <el-collapse v-model="expanded"><el-collapse-item :title="$t('modelTest.logs')" name="logs">
        <div class="run-id">{{ $t('modelTest.runId') }}：{{ run.id }}</div>
        <div v-for="(log,index) in run.logs" :key="index" class="test-log" :class="{ 'log-error': log.level === 'error' }"><span>{{ log.elapsedMs }} ms</span><strong>{{ log.stage }}</strong><span>{{ log.message }}</span></div>
        <pre v-if="run.result?.usage" class="model-output">{{ JSON.stringify(run.result.usage,null,2) }}</pre>
      </el-collapse-item></el-collapse>
    </section>
    <div class="history-heading"><strong>{{ $t('modelTest.history') }}</strong><el-button text :loading="loadingHistory" @click="loadHistory">{{ $t('modelTest.refresh') }}</el-button></div>
    <el-empty v-if="!history.length && !loadingHistory" :description="$t('modelTest.noHistory')" :image-size="45" />
    <button v-for="item in history" :key="item.id" class="history-item" :disabled="busy" @click="selectRun(item)"><span>{{ formatTime(item.createdAt) }}</span><span>{{ $t(`modelTest.status.${item.status}`) }}</span><span>{{ item.summary }}</span></button>
    <template #footer><el-button @click="open = false">{{ $t('modelTest.close') }}</el-button></template>
  </el-dialog>
</template>
<script>
import { startModelTest, getModelTest, getModelTestHistory, getModelTestAudio } from '../apis/module/modelTest';
export default {
  name:'ModelTestDialog', emits:['update:visible'],
  props:{ visible:Boolean, modelId:String, modelName:String, modelType:{type:String,default:''}, configJson:Object },
  data:()=>({text:'',datasetId:'',media:null,run:null,history:[],error:'',starting:false,readingFile:false,loadingHistory:false,loadingAudio:false,audioUrl:'',expanded:['logs'],sequence:0,timer:null}),
  computed:{
    open:{get(){return this.visible;},set(value){this.$emit('update:visible',value);}},
    type(){return this.modelType.toUpperCase();},
    busy(){return this.starting||this.readingFile||['queued','running'].includes(this.run?.status);},
    statusColor(){return {succeeded:'success',failed:'danger',checked:'warning',unsupported:'info'}[this.run?.status]||'primary';},
  },
  watch:{visible(value){if(value){this.dispose();this.run=null;this.history=[];this.error='';this.media=null;this.text='';this.datasetId='';this.loadHistory();}else this.dispose();}},
  beforeUnmount(){this.dispose();},
  methods:{
    dispose(){this.sequence++;clearTimeout(this.timer);this.timer=null;this.starting=false;this.readingFile=false;this.loadingAudio=false;this.loadingHistory=false;this.releaseAudio();},
    releaseAudio(){if(this.audioUrl)URL.revokeObjectURL(this.audioUrl);this.audioUrl='';},
    async start(){
      if(this.busy||!this.modelId)return;
      const sequence=++this.sequence;this.starting=true;this.run=null;this.error='';this.releaseAudio();
      try{
        const body={requestId:crypto.randomUUID(),configJson:this.configJson,text:this.text,datasetId:this.datasetId};
        if(this.media)Object.assign(body,this.type==='ASR'?{audioBase64:this.media.data,audioMime:this.media.mime}:{imageBase64:this.media.data,imageMime:this.media.mime});
        const run=await startModelTest(this.modelId,body);
        if(sequence!==this.sequence||!this.visible)return;
        this.run=run;this.starting=false;this.poll(sequence);
      }catch(error){if(sequence===this.sequence){this.error=error.message;this.starting=false;}}
    },
    poll(sequence){
      if(sequence!==this.sequence||!this.visible)return;
      if(!['queued','running'].includes(this.run?.status)){this.loadHistory();return;}
      this.timer=setTimeout(async()=>{try{const run=await getModelTest(this.modelId,this.run.id);if(sequence!==this.sequence||!this.visible)return;this.run=run;this.poll(sequence);}catch(error){if(sequence===this.sequence)this.error=error.message;}},800);
    },
    async loadHistory(){const sequence=this.sequence;this.loadingHistory=true;try{const history=await getModelTestHistory(this.modelId);if(sequence===this.sequence&&this.visible){this.history=history;const current=history.find(item=>item.id===this.run?.id);if(current&&['queued','running'].includes(this.run?.status)){this.run=current;this.error='';}}}catch(error){if(sequence===this.sequence)this.error=error.message;}finally{if(sequence===this.sequence)this.loadingHistory=false;}},
    selectRun(run){if(this.busy)return;this.sequence++;clearTimeout(this.timer);this.releaseAudio();this.error='';this.run=run;this.poll(this.sequence);},
    async loadAudio(){const sequence=this.sequence;this.loadingAudio=true;try{const blob=await getModelTestAudio(this.modelId,this.run.id);if(sequence===this.sequence&&this.visible)this.audioUrl=URL.createObjectURL(blob);}catch(error){if(sequence===this.sequence)this.error=this.$t('modelTest.audioFailed');}finally{if(sequence===this.sequence)this.loadingAudio=false;}},
    async selectFile(event){
      const file=event.target.files?.[0];this.media=null;if(!file)return;
      const max=this.type==='ASR'?1024*1024:2*1024*1024,ext=file.name.split('.').pop().toLowerCase();
      const mime=this.type==='ASR'?({wav:'audio/wav',mp3:'audio/mpeg'})[ext]:({png:'image/png',jpg:'image/jpeg',jpeg:'image/jpeg'})[ext];
      if(!mime||file.size>max){this.error=this.$t('modelTest.invalidFile');event.target.value='';return;}
      const sequence=this.sequence;
      this.readingFile=true;
      const data=await new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(',')[1]);reader.onerror=reject;reader.readAsDataURL(file);}).catch(()=>null);
      if(sequence===this.sequence&&this.visible){this.readingFile=false;if(data){this.media={mime,data};this.error='';}else this.error=this.$t('modelTest.invalidFile');}
    },
    formatTime(value){return new Date(value).toLocaleString();},
  },
};
</script>
<style scoped>
.test-heading,.result-heading,.history-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:20px 0 14px;flex-wrap:wrap}.test-type{color:var(--el-text-color-secondary);margin-left:12px;font-size:12px}.file-note,.run-id{font-size:12px;color:var(--el-text-color-secondary);margin:8px 0;overflow-wrap:anywhere}.test-result{border:1px solid var(--el-border-color-lighter);border-radius:10px;padding:0 16px 12px}.result-heading{justify-content:flex-start;font-size:12px}.test-error{margin:12px 0}.error-code,.log-error{color:var(--el-color-danger)}.model-output{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--el-fill-color-light);padding:12px;border-radius:6px;max-height:240px;overflow:auto}.test-log{display:grid;grid-template-columns:74px 100px minmax(0,1fr);gap:8px;margin:9px 0;font-size:12px;line-height:1.7;overflow-wrap:anywhere}.test-audio{margin:12px 0}.test-audio audio{width:100%}.history-item{border:0;border-bottom:1px solid var(--el-border-color-lighter);background:transparent;width:100%;text-align:left;display:grid;grid-template-columns:160px 90px minmax(0,1fr);gap:12px;padding:10px 0;color:var(--el-text-color-regular);cursor:pointer}.history-item:hover{color:var(--el-color-primary)}.history-item:disabled{cursor:default;opacity:.6}@media(max-width:600px){.test-log{grid-template-columns:58px 1fr}.test-log span:last-child{grid-column:1/-1}.history-item{grid-template-columns:1fr 90px}.history-item span:last-child{grid-column:1/-1}}
.history-item span { overflow-wrap:anywhere; }
:global(.model-test-dialog) { display:flex;flex-direction:column;max-height:calc(100vh - 40px);margin-top:20px !important; }
:global(.model-test-dialog .el-dialog__body) { min-height:0;overflow-y:auto; }
:global(.model-test-dialog .el-dialog__header),:global(.model-test-dialog .el-dialog__footer) { flex-shrink:0; }
</style>
