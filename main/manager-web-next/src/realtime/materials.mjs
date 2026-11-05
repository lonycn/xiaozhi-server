import {attachmentRowsReady,mixedMessageParts} from '../utils/attachments.mjs';

const phases=new Set(['live','requested','paused','processing','prepared','context_acknowledged','resuming','failed','ended']);
const terminalMaterial=new Set(['failed','canceled']);
const phaseOrder={requested:0,paused:1,processing:2,prepared:3,context_acknowledged:4,resuming:5,live:6};
const protocol=()=>Object.assign(new Error('MATERIAL_PROTOCOL_ERROR'),{code:'MATERIAL_PROTOCOL_ERROR'});
export function realtimeMaterialsAllowed(app,realtime){const item=app?.interaction?.capabilities?.realtime_materials;return app?.features?.realtime_materials===true&&item?.implementation==='implemented'&&['configured','verification_required'].includes(item.status)&&realtime?.supports_materials===true;}
export function materialParts(text,rows){
 if(typeof text!=='string'||text.length>4000||!Array.isArray(rows)||rows.length>2||rows.some(row=>!['image','file'].includes(row.kind))||!attachmentRowsReady(rows))throw Object.assign(new Error('MATERIAL_NOT_READY'),{code:'MATERIAL_NOT_READY'});
 const parts=mixedMessageParts(text,rows);if(!parts.length||parts.length>3||parts.some(part=>part.type!=='text'&&(typeof part.attachment_id!=='string'||!part.attachment_id)))throw protocol();return parts;
}
export function validateMaterialInput(value,session){
 if(!value||!phases.has(value.phase))throw protocol();
 for(const row of [value.pause,value.material])if(row!=null&&(typeof row.id!=='string'||row.session_id!==session.id||!Number.isSafeInteger(row.turn_generation)||row.turn_generation<0))throw protocol();
 if(value.material&&(!value.pause||value.material.pause_id!==value.pause.id||value.material.turn_generation!==value.pause.turn_generation&&!(terminalMaterial.has(value.material.status)&&value.material.turn_generation<value.pause.turn_generation)))throw protocol();
 if(value.phase==='context_acknowledged'&&(!value.pause||value.pause.status!=='context_acknowledged'||value.material?.status!=='acknowledged'))throw protocol();
 return {phase:value.phase,pause:value.pause||null,material:value.material||null};
}
/** 独立输入事务；HTTP保存不等于Worker清空/感知/上下文或恢复ACK。 */
export function createRealtimeMaterials({client,session,available=()=>true,stopLocal=()=>{},changed=()=>{},history=()=>{},denied=()=>{},id=()=>crypto.randomUUID()}){
 let state={enabled:false,phase:'live',pause:null,material:null,busy:false,locked:false,error:'',resumeAcknowledged:false},generation=0,controller=new AbortController(),polling=false,pauseBody=null,submission=null,resumeBody=null,cancelBody=null,expectedResume=false,pendingLive=null;
 const emit=()=>changed({...state});
 const scope=()=>{const s=session();if(!s||!Number.isSafeInteger(s.control_epoch)||s.control_epoch<0)throw protocol();return {id:s.id,epoch:s.control_epoch,key:`${s.id}:${s.control_epoch}`};};
 const fresh=(version,key)=>version===generation&&!controller.signal.aborted&&session()&&`${session().id}:${session().control_epoch}`===key;
 function error(value){if(value?.name==='AbortError')return;state.error=[401,403,404,409,410].includes(value?.status)?'材料权限或会话状态已变化，请刷新权威状态；本地采集已停止':'材料操作未确认，原件与待提交内容已保留，请重试';if([401,403,410].includes(value?.status))denied(value);}
 function apply(value,s){
  const next=validateMaterialInput(value,{id:s.id});
  if(state.pause&&next.pause&&state.pause.id===next.pause.id&&next.pause.turn_generation<state.pause.turn_generation)return;
  if(state.pause&&next.pause&&state.pause.id===next.pause.id&&state.pause.turn_generation===next.pause.turn_generation&&!terminalMaterial.has(next.material?.status)&&phaseOrder[next.phase]<phaseOrder[state.phase])return;
  // 一次pause请求未确认时，旧live快照不能重新开放设备或普通文字入口。
  if(state.locked&&next.phase==='live'){
   if(!expectedResume||!next.pause||next.pause.id!==state.pause?.id||next.pause.status!=='resumed')return;
   if(!state.resumeAcknowledged){pendingLive={value,s};return;}
  }
  if(state.pause&&next.pause&&next.pause.id!==state.pause.id&&state.locked)throw protocol();
  const wasLocked=state.locked;
  if(state.material&&next.material?.id===state.material.id&&!terminalMaterial.has(state.material.status)&&terminalMaterial.has(next.material.status))submission=null;
  state={...state,...next,locked:next.phase!=='live'};
  if(state.locked)stopLocal();
  if(wasLocked&&!state.locked){pauseBody=null;submission=null;resumeBody=null;cancelBody=null;expectedResume=false;pendingLive=null;state.error='';history();}
  emit();
 }
 async function refresh(){if(!state.enabled||polling||!session()||!available())return;const s=scope(),version=generation;polling=true;try{const value=await client.inputState(s.id,controller.signal);if(fresh(version,s.key))apply(value,s);}catch(e){if(fresh(version,s.key)){error(e);emit();}}finally{if(fresh(version,s.key))polling=false;}}
 async function action(work){if(state.busy||!state.enabled||!session()||!available())return;const s=scope(),version=generation;state.busy=true;state.error='';emit();try{const result=await work(s,controller.signal);if(fresh(version,s.key))return result;}catch(e){if(fresh(version,s.key)){error(e);emit();}}finally{if(fresh(version,s.key)){state.busy=false;emit();}}}
 return {
  snapshot:()=>({...state}),
  configure(enabled){state.enabled=enabled===true;emit();},
  reset(){generation++;controller.abort();controller=new AbortController();polling=false;pauseBody=null;submission=null;resumeBody=null;cancelBody=null;expectedResume=false;pendingLive=null;state={enabled:state.enabled,phase:'live',pause:null,material:null,busy:false,locked:false,error:'',resumeAcknowledged:false};emit();},
  async pause(){if(state.locked&&(!pauseBody||state.pause&&state.phase!=='requested'))return;if(!state.enabled||!session()||!available())return;if(!state.locked){state.pause=null;state.material=null;state.resumeAcknowledged=false;}stopLocal();state.locked=true;state.phase='requested';pauseBody||={expected_epoch:scope().epoch,client_pause_id:id()};emit();const result=await action((s,signal)=>client.pauseInput(s.id,pauseBody,signal));if(result){const s=scope();validateMaterialInput({phase:'requested',pause:result,material:null},{id:s.id});state.pause=result;emit();await refresh();}},
  async submit(text,rows){if(state.phase!=='paused'||!state.pause||state.material&&!terminalMaterial.has(state.material.status))return;const parts=materialParts(text,rows),signature=JSON.stringify(parts);if(submission&&submission.signature!==signature){state.error='上次材料提交未确认，请重试原内容或取消本轮后重新准备';emit();return;}
   submission||={signature,body:{expected_epoch:scope().epoch,pause_id:state.pause.id,client_material_id:id(),parts}};
   const result=await action((s,signal)=>client.submitMaterial(s.id,submission.body,signal));if(result){validateMaterialInput({phase:'processing',pause:state.pause,material:result.material},{id:scope().id});state.material=result.material;state.phase='processing';emit();history();await refresh();}
  },
  async resume(){if(!state.pause||!['paused','context_acknowledged','resuming'].includes(state.phase)||state.phase==='resuming'&&!resumeBody||state.phase==='paused'&&state.material&&!terminalMaterial.has(state.material.status))return;stopLocal();resumeBody||={expected_epoch:scope().epoch,client_resume_id:id()};expectedResume=true;state.phase='resuming';state.locked=true;emit();const result=await action((s,signal)=>client.resumeInput(s.id,state.pause.id,resumeBody,signal));if(result&&state.locked){emit();await refresh();}},
  async cancel(){if(!state.pause||['live','ended','resuming'].includes(state.phase))return;stopLocal();cancelBody||={expected_epoch:scope().epoch,client_cancel_id:id()};const result=await action((s,signal)=>client.cancelInput(s.id,state.pause.id,cancelBody,signal));if(result){state.pause=result;state.phase='requested';state.material=null;submission=null;resumeBody=null;cancelBody=null;emit();await refresh();}},
  resumeReady(frame,beforeAccept=()=>true){const materialId=state.material?.status==='acknowledged'?state.material.id:null,s=session();if(!expectedResume||!s||frame.session_id!==s.id||frame.control_epoch!==s.control_epoch||!state.pause||frame.pause_id!==state.pause.id||frame.material_id!==materialId||frame.turn_generation!==state.pause.turn_generation)return false;if(!beforeAccept())return false;state.resumeAcknowledged=true;emit();if(pendingLive){const value=pendingLive;pendingLive=null;apply(value.value,value.s);}else void refresh();return true;},
  refresh,
 };
}
