/** Provider credentials never enter the browser. Tickets are single-use body values. */
export function createRealtimeClient({baseUrl,getToken,fetchImpl=globalThis.fetch,onUnauthorized=()=>{}}){
 const root=`${baseUrl.replace(/\/$/,'')}/api/v1/ai/realtime`,key=value=>encodeURIComponent(String(value));
 async function request(path,body,signal){
  const controller=new AbortController(),abort=()=>controller.abort();if(signal?.aborted)abort();else signal?.addEventListener('abort',abort,{once:true});const timer=setTimeout(abort,15000);
  try{const response=await fetchImpl(root+path,{method:body===undefined?'GET':'POST',signal:controller.signal,headers:{'Content-Type':'application/json',Authorization:`Bearer ${getToken()||''}`},...(body===undefined?{}:{body:JSON.stringify(body)})});const result=await response.json().catch(()=>null);if(response.status===401||result?.code===401)onUnauthorized();if(!response.ok||result?.code!==0)throw Object.assign(new Error('REALTIME_REQUEST_FAILED'),{status:response.status,code:result?.msg});return result.data;}
  catch(error){if(error.name==='AbortError'&&!signal?.aborted)throw Object.assign(new Error('TIMEOUT'),{code:'TIMEOUT'});throw error;}finally{clearTimeout(timer);signal?.removeEventListener('abort',abort);}
 }
 const action=(id,name,body,signal)=>request(`/sessions/${key(id)}/${name}`,body,signal);
 return {capabilities:signal=>request('/capabilities',undefined,signal),create:(body,signal)=>request('/sessions',body,signal),current:(id,signal)=>request(`/sessions/current?conversation_id=${key(id)}`,undefined,signal),get:(id,signal)=>request(`/sessions/${key(id)}`,undefined,signal),ticket:(id,body,signal)=>action(id,'connect-ticket',body,signal),send:(id,body,signal)=>action(id,'messages',body,signal),injections:(id,after=0,signal)=>request(`/sessions/${key(id)}/injections?after=${key(after)}&limit=50`,undefined,signal),interrupt:(id,body,signal)=>action(id,'interrupt',body,signal),end:(id,body,signal)=>action(id,'end',body,signal)};
}
