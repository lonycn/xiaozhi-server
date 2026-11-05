<template>
  <section v-if="conversationId && identity" class="tool-results" aria-live="polite">
    <details><summary>工具结果 <span v-if="items.length">({{ items.length }})</span></summary>
      <p v-if="error" role="alert">{{ errorLabel }} <button v-if="error === 'network'" type="button" @click="refresh">重试</button></p>
      <p v-else-if="loading && !items.length">正在读取工具结果…</p><p v-else-if="!items.length">暂无工具结果</p>
      <ol><li v-for="item in items" :key="item.sequence">
        <strong>{{ safeText(item.name) }}</strong> · {{ item.status === 'succeeded' ? '成功' : item.status === 'failed' ? '失败' : '未知状态' }}
        <small>{{ item.execution_type === 'realtime' ? '实时' : '会话' }} · {{ safeText(item.created_at) }}</small>
        <small v-if="item.error_code">错误代码：{{ safeText(item.error_code) }}</small>
        <p v-if="item.result_access === 'restricted'">结果受限，需重新授权</p>
        <template v-else-if="validKind(item.result)">
          <small>{{ kindLabel(item.result.kind) }}<template v-if="item.result.observed_at"> · 采样时间 {{ safeText(item.result.observed_at) }}</template><template v-if="item.result.stale"> · 可能已过期</template><template v-if="item.result.truncated || item.result.data?.truncated"> · 内容已截断</template></small>
          <p v-for="line in resultLines(item.result)" :key="line">{{ line }}</p>
          <ul v-if="item.result.citations?.length"><li v-for="(citation, i) in item.result.citations" :key="i">出处：{{ safeText(citation.title) }} <span v-if="citation.locator?.page"> · 第 {{ citation.locator.page }} 页</span><span v-if="Number.isInteger(citation.locator?.time_ms)"> · {{ citation.locator.time_ms }} ms</span><small>{{ sourceLabel(citation.source_ref) }}</small></li></ul>
        </template>
      </li></ol>
      <button v-if="hasMore" type="button" :disabled="loading" @click="loadMore">加载更早结果</button>
    </details>
  </section>
</template>
<script>
import api from '../apis/module/toolResults';
const decimal = value => typeof value === 'string' && /^\d+$/.test(value);
const blocked = /(?:url|uri|token|secret|password|argument|result_json|provider|authorization|address|endpoint)/i;
function clean(value) { return typeof value === 'string' && !/(?:https?:\/\/|www\.|\b(?:\d{1,3}\.){3}\d{1,3}\b)/i.test(value) ? value.slice(0, 2000) : ''; }
export default {
  name:'ToolResults', props:{conversationId:{type:String,default:''},identity:{type:String,default:''},refreshKey:{type:Number,default:0}},
  data:()=>({items:[],cursor:'0',hasMore:false,error:'',loading:false,epoch:0,controller:null,pendingRefresh:false}),
  computed:{errorLabel(){return {disabled:'工具结果功能未启用',denied:'无权查看工具结果或会话不可见',auth:'登录已失效，请重新登录',network:'工具结果读取失败，请检查网络后重试'}[this.error];}},
  watch:{conversationId:'reset',identity:'reset',refreshKey(){this.refresh();}}, mounted(){this.reset();}, beforeUnmount(){this.epoch++;this.controller?.abort();},
  methods:{
    safeText:clean, validKind:r=>!!r&&['technical','business','conversation_evidence'].includes(r.kind), kindLabel:k=>({technical:'技术结果（非业务事实）',business:'业务结果',conversation_evidence:'会话证据'}[k]),
    sourceLabel(ref){if(!ref || typeof ref!=='object')return '';return ['conversation_id','message_id','attachment_id','resource_type','record_id'].filter(k=>clean(ref[k])).map(k=>`${k}: ${clean(ref[k])}`).join(' · ');},
    resultLines(result){const data=result.data||{}, lines=[];const add=(key,value)=>{if(blocked.test(key)||!clean(key))return;if(typeof value==='string'){const text=clean(value);if(text)lines.push(`${key}: ${text}`);}else if(typeof value==='number'&&Number.isFinite(value)||typeof value==='boolean')lines.push(`${key}: ${value}`);};
      for(const key of ['message','text','limitation','evidence_type','resource_type','record_id','protocol_version','read_only','technical_acceptance_only','business_records_included','content_available'])add(key,data[key]);
      if(result.kind==='business' && data.fields && typeof data.fields==='object' && !Array.isArray(data.fields))for(const [key,value] of Object.entries(data.fields))add(key,value);
      return lines.slice(0,50);},
    reset(){this.epoch++;this.controller?.abort();this.items=[];this.cursor='0';this.hasMore=false;this.error='';this.loading=false;this.pendingRefresh=false;if(this.conversationId&&this.identity)this.refresh();},
    refresh(){if(this.loading){this.pendingRefresh=true;return;}return this.read(false);}, loadMore(){if(this.hasMore)return this.read(true);},
    async read(older){if(this.loading||!this.identity||!this.conversationId)return;const epoch=this.epoch,controller=new AbortController(),before=older?this.cursor:'0';this.controller=controller;this.loading=true;
      try{const page=await api.list(this.conversationId,before,controller.signal);if(epoch!==this.epoch||controller.signal.aborted)return;
        if(!page||!Array.isArray(page.items)||!decimal(page.next_cursor)||typeof page.has_more!=='boolean'||page.items.some(x=>!decimal(x.sequence))||(page.has_more&&(page.items.length===0||BigInt(page.next_cursor)>=BigInt(before==='0'?'9223372036854775808':before))))throw new Error('INVALID_PAGE');
        if(older){const seen=new Set(this.items.map(x=>x.sequence));this.items=[...this.items,...page.items.filter(x=>!seen.has(x.sequence))];}else this.items=page.items;
        this.cursor=page.next_cursor;this.hasMore=page.has_more;this.error='';
      }catch(e){if(epoch!==this.epoch||controller.signal.aborted)return;this.error=e.code==='TOOL_RESULTS_DISABLED'?'disabled':e.status===401?'auth':e.status===403||e.status===404?'denied':'network';if(this.error!=='network'){this.items=[];this.hasMore=false;}}
      finally{if(epoch===this.epoch){this.loading=false;this.controller=null;if(this.pendingRefresh){this.pendingRefresh=false;this.refresh();}}}
    },
  },
};
</script>
<style scoped>
.tool-results{border:1px solid #d6e9de;border-radius:8px;background:#f7fcf8;padding:8px 12px;color:#27583b;font-size:var(--admin-font-base)}summary{cursor:pointer;font-weight:600}ol,ul{list-style:none;padding:0;margin:8px 0}ol{max-height:260px;overflow:auto}li{padding:6px 0;border-top:1px solid #e2eee6;overflow-wrap:anywhere}small{display:block;color:#617b69;margin-top:3px}p{margin:6px 0;white-space:pre-wrap}button{background:none;border:0;color:#20814a;cursor:pointer}
</style>
