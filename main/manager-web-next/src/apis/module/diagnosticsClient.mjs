/** Read-only administrative diagnostics with identity fencing. */
export function createDiagnosticsClient({baseUrl,getToken,fetchImpl=globalThis.fetch,onUnauthorized=()=>{}}){
 return {async read(signal){const token=getToken();const controller=new AbortController(),abort=()=>controller.abort();if(signal?.aborted)abort();else signal?.addEventListener('abort',abort,{once:true});const timeout=setTimeout(abort,15000);
  try{const response=await fetchImpl(`${baseUrl.replace(/\/$/,'')}/api/v1/admin/diagnostics/ai`,{signal:controller.signal,headers:{Authorization:`Bearer ${token||''}`},cache:'no-store'});const result=await response.json().catch(()=>null);if(controller.signal.aborted)throw new DOMException('Aborted','AbortError');if(token!==getToken())throw Object.assign(new Error('STALE_IDENTITY'),{code:'STALE_IDENTITY'});if((response.status===401||result?.code===401))onUnauthorized();if(!response.ok||result?.code!==0)throw Object.assign(new Error('DIAGNOSTICS_UNAVAILABLE'),{status:response.ok?result?.code:response.status});return result.data;
  }catch(error){if(error.name==='AbortError'&&!signal?.aborted)throw Object.assign(new Error('TIMEOUT'),{code:'TIMEOUT'});throw error;}finally{clearTimeout(timeout);signal?.removeEventListener('abort',abort);}}
 };
}
