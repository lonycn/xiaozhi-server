import {base64Bytes,bytesBase64} from './pcm.mjs';
import {createBrowserCapture,audioContext} from './browserMedia.mjs';
import {createPcmPlayback} from './playback.mjs';
export const REALTIME_TERMINAL=new Set(['ended','failed','expired','canceled']);
export function realtimeErrorKey(error){const code=error?.code||error?.message;if(error?.name==='NotAllowedError'||error?.name==='SecurityError')return 'realtime.permission';if(error?.status===404)return 'realtime.unavailable';if(error?.status===401||error?.status===403)return 'realtime.denied';if(code==='AUDIO_UNAVAILABLE')return 'realtime.audioUnavailable';if(error?.name==='NotFoundError')return 'realtime.deviceMissing';if(code==='OMNI_AUDIO_LIMIT')return 'ai.omniAudioLimit';if(code==='RESPONSE_IN_PROGRESS')return 'realtime.responseBusy';if(code==='INPUT_LIMIT')return 'realtime.inputLimit';if(code==='AUDIO_TURN_LIMIT')return 'realtime.turnLimit';if(['OUTPUT_LIMIT','FRAME_LIMIT','BACKPRESSURE'].includes(code))return 'realtime.resourceLimit';if(code==='TIMEOUT'||code==='PROVIDER_TIMEOUT')return 'realtime.timeout';if(code==='COMMAND_UNCONFIRMED')return 'realtime.unconfirmed';if(error?.status===409||code==='REALTIME_EPOCH_CHANGED')return 'realtime.changed';return 'realtime.failed';}
/** A separate AI provider lifecycle; never reuse a human RTC room or JWT. */
export function createRealtimeSession({client,changed=()=>{},history=()=>{},socketFactory=url=>new WebSocket(url),captureFactory=createBrowserCapture,playbackFactory=()=>createPcmPlayback(audioContext()),clock=()=>Date.now()}){
 let state={session:null,status:'idle',error:'',responseId:null,messageId:null,transcript:'',injections:[],cameraSampling:false,turnSeconds:0,turnDetectionMode:'manual',listening:false,listeningPending:false,listeningRequested:false,speechActive:false};
 let generation=0,disposed=false,controller=new AbortController(),socket=null,capture=null,playback=null,recording=false,turnBytes=0,turnStarted=0,readyTimer=null,turnTimer=null,commandTimer=null,busy=false,lastImageAt=0,commandId=null,cameraStopped=false,listeningRequested=false,listeningTimer=null,captureReady=false,listeningAcknowledged=false;const finishedResponses=new Set();
 const emit=()=>changed({...state,injections:[...state.injections]});
 const fresh=version=>!disposed&&version===generation&&!controller.signal.aborted;
 function clearTimers(){clearTimeout(readyTimer);clearInterval(turnTimer);clearTimeout(commandTimer);clearTimeout(listeningTimer);}
 function stopInput(){recording=false;listeningRequested=false;state.listeningRequested=false;captureReady=false;listeningAcknowledged=false;state.listeningPending=false;clearTimeout(listeningTimer);state.listening=false;state.speechActive=false;clearInterval(turnTimer);state.cameraSampling=false;const resource=capture;capture=null;resource?.stop().catch(()=>{});}
 function release({keepTransport=false}={}){++generation;controller.abort();controller=new AbortController();clearTimers();stopInput();playback?.close().catch(()=>{});playback=null;const old=socket;if(!keepTransport)socket=null;if(old){old.onopen=null;old.onmessage=null;old.onerror=null;old.onclose=null;if(!keepTransport)old.close(1000);}busy=false;}
 function fail(error){release();state={...state,status:'failed',error:realtimeErrorKey(error)};emit();}
 function send(frame){if(!socket||socket.readyState!==1)throw new Error('SOCKET_CLOSED');if(socket.bufferedAmount>256000)throw new Error('BACKPRESSURE');socket.send(JSON.stringify(frame));}
 function mergeInjections(items){for(const item of items){const index=state.injections.findIndex(old=>old.id===item.id);if(index<0)state.injections.push(item);else if(!['acknowledged','failed'].includes(state.injections[index].status)||item.status===state.injections[index].status)state.injections[index]={...state.injections[index],...item};}}
 function identity(frame){return state.session&&frame.session_id===state.session.id&&frame.control_epoch===state.session.control_epoch;}
 function onFrame(frame,version){
  if(!fresh(version))return;
  if(!frame||typeof frame!=='object'||Array.isArray(frame)){fail(new Error('PROTOCOL_ERROR'));return;}
  if(frame?.type==='error'&&!frame.session_id){fail({code:frame.code});return;}
  if(!identity(frame)){fail({code:'REALTIME_EPOCH_CHANGED'});return;}
  switch(frame.type){
   case 'connected':state.status='connecting';break;
   case 'listening.started':if(listeningRequested){clearTimeout(listeningTimer);listeningAcknowledged=true;state.listeningPending=!captureReady;state.listening=captureReady;}break;
   case 'listening.stopped':clearTimeout(listeningTimer);state.listeningPending=false;state.listening=false;state.speechActive=false;state.turnSeconds=0;break;
   case 'speech.started':playback?.clear();if(!listeningRequested)break;state.speechActive=true;turnStarted=clock();state.turnSeconds=0;break;
   case 'speech.stopped':state.speechActive=false;state.turnSeconds=0;break;
   case 'ready':clearTimeout(readyTimer);state.session={...state.session,status:'active'};state.status='active';break;
   case 'response.started':
    if(typeof frame.response_id!=='string'||typeof frame.message_id!=='string'||finishedResponses.has(frame.response_id)||state.status==='interrupting')return;
    if(state.turnDetectionMode!=='server_vad')stopInput();state.responseId=frame.response_id;state.messageId=frame.message_id;state.transcript='';state.status='responding';commandId=null;playback?.response(frame.response_id);history();break;
   case 'audio.delta':
    if(state.status==='interrupting'||frame.response_id!==state.responseId||frame.message_id!==state.messageId)return;
    try{playback?.append(frame.response_id,base64Bytes(frame.audio));}catch(error){fail(error);return;}break;
   case 'transcript.delta':
    if(state.status==='interrupting'||frame.response_id!==state.responseId||frame.message_id!==state.messageId||typeof frame.delta!=='string')return;
    if(state.transcript.length+frame.delta.length>128000){fail(new Error('OUTPUT_LIMIT'));return;}state.transcript+=frame.delta;break;
   case 'response.completed':
    if(frame.response_id!==state.responseId||state.status==='interrupting')return;
    state.transcript=typeof frame.text==='string'?frame.text.slice(0,128000):state.transcript;finishedResponses.add(frame.response_id);state.responseId=null;state.status='active';history();break;
   case 'response.canceled':
    if(frame.response_id!==state.responseId)return;
    playback?.clear();if(state.turnDetectionMode!=='server_vad')stopInput();
    if(frame.status==='interrupting'){state.status='interrupting';}
    else if(frame.status==='canceled'){clearTimeout(commandTimer);finishedResponses.add(frame.response_id);state.responseId=null;state.status='active';history();}else return;break;
   case 'injection.status':{
    const item=state.injections.find(item=>item.id===frame.injection_id);if(['queued','dispatched','acknowledged','failed'].includes(frame.status))mergeInjections([{...(item||{}),id:frame.injection_id,message_id:frame.message_id||item?.message_id,status:frame.status,provider_item_id:frame.provider_item_id}]);history();break;}
   case 'error':
    playback?.clear();stopInput();
    if(frame.code==='RESPONSE_IN_PROGRESS'){state.error=realtimeErrorKey(frame);break;}
    fail({code:frame.code});return;
   case 'ended':{const status=REALTIME_TERMINAL.has(frame.status)?frame.status:'ended';release();state.session={...state.session,status};state.status=status;history();break;}
   default:return;
  }
  emit();
 }
 async function pollInjections(version){
  let after=0,more=true;const items=[];
  while(more){const page=await client.injections(state.session.id,after,controller.signal);if(!fresh(version))return;items.push(...page.items);more=page.has_more;if(more&&(!Number.isSafeInteger(page.next_cursor)||page.next_cursor<=after))throw new Error('INVALID_CURSOR');after=page.next_cursor;}
  mergeInjections(items);
 }
 return {
  snapshot:()=>({...state,injections:[...state.injections]}),
  async discover(conversationId){const version=generation;try{const session=await client.current(conversationId,controller.signal);if(!fresh(version))return;state.session=session;state.turnDetectionMode=session?.turn_detection_mode||'manual';state.status=session&&!REALTIME_TERMINAL.has(session.status)?'existing':'idle';state.error='';emit();}catch(error){if(fresh(version)&&error.name!=='AbortError'){state.status=error.status===404?'unavailable':'failed';state.error=realtimeErrorKey(error);emit();}}},
  async start(conversationId,consent,clientId,turnDetectionMode='manual'){
   if(busy||disposed||!consent.microphone||!['manual','server_vad'].includes(turnDetectionMode))return;release();cameraStopped=false;finishedResponses.clear();const version=generation;busy=true;state={...state,session:null,status:'connecting',error:'',transcript:'',responseId:null,messageId:null,injections:[],turnDetectionMode,listening:false,listeningPending:false,listeningRequested:false,speechActive:false};emit();
   try{
    // Resume playback within this explicit user action; microphone permission is a
    // separate manual-recording action after the real provider ready event.
    playback=playbackFactory();await playback.resume();if(!fresh(version))return;
    const session=await client.create({conversation_id:conversationId,client_session_id:clientId,consent,turn_detection_mode:turnDetectionMode},controller.signal);if(!fresh(version))return;state.session=session;
    if((session.turn_detection_mode||'manual')!==turnDetectionMode)throw new Error('PROTOCOL_ERROR');
    if(REALTIME_TERMINAL.has(session.status))throw new Error('SESSION_TERMINAL');
    const ticket=await client.ticket(session.id,{expected_epoch:session.control_epoch},controller.signal);if(!fresh(version))return;
    const url=new URL(ticket.websocket_url);if(!['ws:','wss:'].includes(url.protocol)||url.search||url.hash||url.username||url.password||ticket.session_id!==session.id||ticket.control_epoch!==session.control_epoch)throw new Error('INVALID_CONNECT_TICKET');
    const ws=socketFactory(url.href);socket=ws;ws.onopen=()=>{if(fresh(version)&&socket===ws){try{send({type:'connect',ticket:ticket.ticket});}catch(error){fail(error);}}};
    ws.onmessage=event=>{if(!fresh(version)||typeof event.data!=='string'||event.data.length>300000){if(fresh(version))fail(new Error('OUTPUT_LIMIT'));return;}let frame;try{frame=JSON.parse(event.data);}catch{fail(new Error('PROTOCOL_ERROR'));return;}onFrame(frame,version);};
    ws.onerror=()=>{if(fresh(version))fail(new Error('SOCKET_ERROR'));};
    ws.onclose=()=>{if(!fresh(version))return;socket=null;fail(new Error('CLIENT_DISCONNECTED'));};
    readyTimer=setTimeout(()=>{if(fresh(version)&&state.status==='connecting')fail({code:'PROVIDER_TIMEOUT'});},15000);emit();
   }catch(error){if(fresh(version)&&error.name!=='AbortError')fail(error);}finally{if(fresh(version))busy=false;}
  },
  /** Called only by an explicit user action after microphone consent. */
  async startListening(){
   if(disposed||busy||recording||state.listening||state.listeningPending||state.turnDetectionMode!=='server_vad'||!state.session?.consent?.microphone||!['active','responding'].includes(state.status)||!socket||!playback)return;
   const version=generation;busy=true;state.error='';recording=true;listeningRequested=true;state.listeningRequested=true;state.listeningPending=true;turnBytes=0;lastImageAt=0;state.turnSeconds=0;
   const resource=captureFactory({onPcm:bytes=>{if(!fresh(version)||!recording||capture!==resource)return;try{if(!bytes.length||bytes.length%2||bytes.length>65536)throw new Error('INPUT_LIMIT');send({type:'audio.append',audio:bytesBase64(bytes)});turnBytes+=bytes.length;}catch(error){fail(error);}},onFrame:image=>{if(!fresh(version)||!recording||capture!==resource||cameraStopped||!state.session.consent.camera||!turnBytes||clock()-lastImageAt<1000)return false;try{if(image.length>256000)throw new Error('FRAME_LIMIT');send({type:'frame.append',image,mime_type:'image/jpeg'});lastImageAt=clock();return true;}catch(error){fail(error);return false;}},onError:error=>{if(fresh(version)&&capture===resource)fail(error);}});
   capture=resource;emit();
   try{
    send({type:'listening.start'});listeningTimer=setTimeout(()=>{if(fresh(version)&&state.listeningPending)fail({code:'PROVIDER_TIMEOUT'});},10000);
    await resource.start(!!state.session.consent.camera&&!cameraStopped);
    if(!fresh(version)||!recording||capture!==resource){await resource.stop();return;}
    captureReady=true;state.listening=listeningAcknowledged;state.listeningPending=!listeningAcknowledged;state.cameraSampling=!!state.session.consent.camera&&!cameraStopped;
    turnTimer=setInterval(()=>{if(!fresh(version)||!recording)return;state.turnSeconds=state.speechActive?Math.floor((clock()-turnStarted)/1000):0;if(state.turnSeconds>=60){this.pauseListening();state.error='realtime.turnLimit';}emit();},250);
   }catch(error){if(fresh(version)){await this.pauseListening();state.error=realtimeErrorKey(error);}}
   finally{if(fresh(version)){busy=false;emit();}}
  },
  async pauseListening(){
   if(state.turnDetectionMode!=='server_vad'||disposed)return;
   const hadInput=recording||state.listening;if(!hadInput)return;stopInput();emit();
   if(hadInput){state.listeningPending=true;emit();const version=generation;try{send({type:'listening.stop'});listeningTimer=setTimeout(()=>{if(fresh(version)&&state.listeningPending)fail({code:'PROVIDER_TIMEOUT'});},10000);}catch(error){fail(error);}}
  },
  async beginTurn(){
   if(disposed||busy||state.turnDetectionMode!=='manual'||state.status!=='active'||!socket||!playback)return;const version=generation;busy=true;state.error='';state.status='requesting';playback.clear();turnBytes=0;turnStarted=0;lastImageAt=0;recording=true;state.turnSeconds=0;
   const resource=captureFactory({onPcm:bytes=>{if(!fresh(version)||!recording)return;try{if(!bytes.length||bytes.length%2||bytes.length>65536)throw new Error('INPUT_LIMIT');const remaining=1920000-turnBytes,packet=bytes.subarray(0,Math.max(0,remaining));if(packet.length){if(!turnStarted)turnStarted=clock();send({type:'audio.append',audio:bytesBase64(packet)});turnBytes+=packet.length;}if(turnBytes>=1920000&&state.status==='recording')this.commitTurn();}catch(error){fail(error);}},onFrame:image=>{if(!fresh(version)||!recording||state.status!=='recording'||cameraStopped||!state.session.consent.camera||!turnBytes||clock()-lastImageAt<1000)return false;try{if(image.length>256000)throw new Error('FRAME_LIMIT');send({type:'frame.append',image,mime_type:'image/jpeg'});lastImageAt=clock();return true;}catch(error){fail(error);return false;}},onError:error=>{if(fresh(version))fail(error);}});capture=resource;emit();
   try{await resource.start(!!state.session.consent.camera&&!cameraStopped);if(!fresh(version)||!recording){await resource.stop();return;}if(!turnStarted)turnStarted=clock();state.status='recording';state.cameraSampling=!!state.session.consent.camera&&!cameraStopped;turnTimer=setInterval(()=>{if(!fresh(version)||!recording)return;state.turnSeconds=Math.floor((clock()-turnStarted)/1000);if(state.turnSeconds>=60){this.commitTurn();return;}emit();},250);}
   catch(error){if(fresh(version)){stopInput();state.status='active';state.error=realtimeErrorKey(error);}}finally{if(fresh(version)){busy=false;emit();}}
  },
  async commitTurn(){
   if(!recording||state.status!=='recording'||busy)return;busy=true;const version=generation,resource=capture;clearInterval(turnTimer);state.status='committing';emit();
   try{await resource?.stop({flush:true});if(!fresh(version))return;capture=null;recording=false;state.cameraSampling=false;if(!turnBytes){state.status='active';state.error='realtime.emptyTurn';}else{send({type:'audio.commit'});state.status='waiting';readyTimer=setTimeout(()=>{if(fresh(version)&&state.status==='waiting')fail({code:'PROVIDER_TIMEOUT'});},15000);}}
   catch(error){if(fresh(version))fail(error);}finally{if(fresh(version)){busy=false;emit();}}
  },
  stopCamera(){cameraStopped=true;capture?.stopCamera();state.cameraSampling=false;emit();},
  async interrupt(){
   if(!state.responseId||['interrupting','ending'].includes(state.status))return;const version=generation;state.status='interrupting';playback?.clear();if(state.turnDetectionMode!=='server_vad')stopInput();commandId ||= crypto.randomUUID();emit();
   try{const command=await client.interrupt(state.session.id,{expected_epoch:state.session.control_epoch,client_command_id:commandId,response_id:state.responseId},controller.signal);if(!fresh(version))return;if(command.status==='failed'){fail({code:command.error_code||'COMMAND_UNCONFIRMED'});return;}if(command.status==='acknowledged'&&state.status==='interrupting'){finishedResponses.add(state.responseId);state.responseId=null;state.status='active';history();emit();return;}commandTimer=setTimeout(()=>{if(fresh(version)&&state.status==='interrupting')fail({code:'COMMAND_UNCONFIRMED'});},15000);}
   catch(error){if(fresh(version)&&error.name!=='AbortError')fail(error);}
  },
  async refresh(){if(!state.session||disposed||state.status==='ending')return;const version=generation;try{const next=await client.get(state.session.id,controller.signal);if(!fresh(version))return;if(next.control_epoch!==state.session.control_epoch||(next.turn_detection_mode||'manual')!==state.turnDetectionMode||REALTIME_TERMINAL.has(next.status)){const changedEpoch=next.control_epoch!==state.session.control_epoch;release();state.session=next;state.status=REALTIME_TERMINAL.has(next.status)?next.status:'failed';if(changedEpoch&&!REALTIME_TERMINAL.has(next.status))state.error='realtime.changed';emit();return;}state.session=next;await pollInjections(version);if(fresh(version))emit();}catch(error){if(fresh(version)&&error.name!=='AbortError')fail(error);}},
  async end(){
   if(disposed||state.status==='ending')return;
   const session=state.session;
   // Invalidate all old API/device/socket callbacks and stop local media now.
   // Keep the worker connection alive until Java has durably ended the session,
   // otherwise CLIENT_DISCONNECTED can win the canonical terminal-state race.
   release({keepTransport:!!session});if(!session){state.status='ended';emit();return;}const version=generation;busy=true;state.status='ending';state.error='';emit();
   try{
    const ended=await client.end(session.id,{expected_epoch:session.control_epoch},controller.signal);if(!fresh(version))return;
    if(ended?.id!==session.id||!REALTIME_TERMINAL.has(ended.status))throw new Error('COMMAND_UNCONFIRMED');
    release();state.session=ended;state.status=ended.status;history();emit();
   }catch(error){if(fresh(version)){release();state.status='failed';state.error=realtimeErrorKey(error);emit();}}
  },
  dispose(){disposed=true;release();},
 };
}
