/** 只在内存比较实际凭证与不可变身份；不把此key传入DOM、URL、日志或私人草稿键。 */
export function realtimeAuthorityKey(state){
 let dto;try{dto=typeof state?.token==='string'?JSON.parse(state.token):state?.token;}catch{dto=null;}
 const bearer=typeof dto?.token==='string'?dto.token:'',user=state?.userInfo||{},tenant=user.tenant||dto?.tenant||{},scope=user.scope||dto?.scope||{};
 return JSON.stringify([bearer,user.id||user.user_id||user.userId||'',user.issuer||dto?.issuer||'',user.external_subject||dto?.external_subject||'',tenant.type||'',tenant.id||'',user.source_session_id||dto?.source_session_id||'',user.source_id||dto?.source_id||'',user.token_id||dto?.token_id||'',scope.project_ids||[],scope.device_ids||[]]);
}
