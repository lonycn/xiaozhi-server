"""Pause/clear -> bounded observation -> exact context ACK -> explicit resume."""
import asyncio
import hashlib
import re
import time
from .control import Fault,AuthorityLost
from .perception import Perception,PerceptionFailure
from .tools import wire,identifier

CAPS=dict(max_images=2,max_image_bytes=1048576,max_text_chars=12000,max_session_materials=6,max_session_tokens=20000)
MAX_CAPTURE_TAIL_CHUNKS=64
MAX_CAPTURE_TAIL_BYTES=2*1024*1024


class Materials:
    def __init__(self,session,perception=None):
        self.session=session;policy=session.task.get('material_policy')
        if not isinstance(policy,dict) or type(session.task.get('turn_generation')) is not int:raise Fault('MATERIAL_INPUT_UNAVAILABLE')
        self.limits={}
        for name,maximum in CAPS.items():
            value=policy.get(name,maximum)
            if type(value) is not int or value<1:raise Fault('MATERIAL_LIMIT')
            self.limits[name]=min(value,maximum)
        self.allowed_model=policy.get('perception_model') or ''
        if not isinstance(self.allowed_model,str):raise Fault('MATERIAL_INPUT_UNAVAILABLE')
        self.generation=session.task['turn_generation'];self.paused=False;self.pending_clear=None
        self.clear_ids=set();self.resume_ids=set();self.material_ids=set();self.context=None
        self.perception=perception or Perception(session.config)
        self.job=None;self.jobs=set();self.audit_jobs=set();self.current=None;self.known_tokens=0
        self.awaiting_reply=False;self.reply_generation=None
        self.acknowledged_material=None
        self.discarded_input_chunks=0;self.discarded_input_bytes=0
        self.total_discarded_input_chunks=0;self.total_discarded_input_bytes=0

    def busy(self):return bool(self.paused or self.current or self.context or self.awaiting_reply)

    def discard_capture_tail(self,size):
        # HTTP pause and queued WebSocket capture race independently. Only a
        # bounded tail is absorbed while locked; it cannot start a new generation.
        if (type(size) is not int or size<0 or self.discarded_input_chunks>=MAX_CAPTURE_TAIL_CHUNKS
                or self.discarded_input_bytes+size>MAX_CAPTURE_TAIL_BYTES):raise Fault('INPUT_LIMIT')
        self.discarded_input_chunks+=1;self.discarded_input_bytes+=size
        self.total_discarded_input_chunks+=1;self.total_discarded_input_bytes+=size

    def synchronize(self,generation):
        if type(generation) is not int or generation<self.generation:raise Fault('MATERIAL_INPUT_UNAVAILABLE')
        if generation==self.generation:return
        uncertain=self.context is not None
        if self.job and not self.job.done():self.job.cancel()
        self.current=None;self.awaiting_reply=False;self.reply_generation=None
        self.acknowledged_material=None
        self.generation=generation
        if uncertain:raise Fault('MATERIAL_CONTEXT_UNCONFIRMED')

    async def control(self,control):
        if control is None:return
        if self.session.capture is None:raise Fault('MATERIAL_CAPTURE_PROTOCOL_REQUIRED')
        if not isinstance(control,dict):raise Fault('MATERIAL_INPUT_UNAVAILABLE')
        self.synchronize(control.get('turn_generation'))
        pause_id=identifier(control.get('pause_id'));action=control.get('action')
        if action=='pause_clear':
            request_id=identifier(control.get('clear_request_id'))
            if request_id in self.clear_ids:return
            if len(self.clear_ids)>=24:raise Fault('MATERIAL_LIMIT')
            if self.pending_clear is not None:raise Fault('MATERIAL_CONTEXT_UNCONFIRMED')
            if self.session.waiting_response and self.session.response is None:raise Fault('MATERIAL_CONTEXT_UNCONFIRMED')
            self.discarded_input_chunks=0;self.discarded_input_bytes=0
            self.paused=True;self.pending_clear=dict(pause_id=pause_id,request_id=request_id,generation=self.generation)
            self.clear_ids.add(request_id)
            s=self.session;s.listening=False;s.speech_active=False;s.speech_started=None
            s.waiting_commit=False;s.turn_audio=0;s.turn_has_frame=False;s.clearing_input=True;s.pending_since=time.monotonic()
            if s.response and s.response['id'] not in s.terminal_responses:
                s.response['suppressed']=True
                await s.send_provider({'type':'response.cancel'})
            await s.send_provider({'type':'input_audio_buffer.clear','event_id':request_id})
            await s.send_browser('material.pausing',authorize=False,pause_id=pause_id,clear_audio=True)
        elif action=='resume':
            request_id=identifier(control.get('resume_request_id'))
            if request_id in self.resume_ids:return
            if self.pending_clear or self.current or self.context or not self.paused:raise Fault('MATERIAL_CONTEXT_UNCONFIRMED')
            if type(control.get('respond')) is not bool:raise Fault('MATERIAL_INPUT_UNAVAILABLE')
            if control['respond'] and self.acknowledged_material!=(control.get('material_id'),self.generation):
                raise Fault('MATERIAL_CONTEXT_UNCONFIRMED')
            result=await self.session.manager.material_control(self.session.task,pause_id,'resumed',self.generation,
                                                              resume_request_id=request_id)
            if (not isinstance(result,dict) or result.get('turn_generation')!=self.generation
                    or result.get('respond') is not control['respond'] or result.get('pause_id')!=pause_id
                    or result['respond'] and result.get('material_id')!=control.get('material_id')):
                raise Fault('MATERIAL_CONTEXT_UNCONFIRMED')
            self.resume_ids.add(request_id);self.paused=False;self.session.listening=False
            self.acknowledged_material=None
            if result['respond']:
                self.awaiting_reply=True;self.reply_generation=self.generation
                self.session.waiting_response=True;self.session.pending_since=time.monotonic()
            await self.session.send_browser('material.resume_ready',authorize=False,pause_id=pause_id,
                material_id=control.get('material_id') if result['respond'] else None,turn_generation=self.generation,
                responding=result['respond'],listening_allowed=not result['respond'],requires_user_action=True,
                **self.session.capture.rotate())
            if result['respond']:await self.session.send_provider({'type':'response.create'})
        else:raise Fault('MATERIAL_INPUT_UNAVAILABLE')

    async def provider_event(self,event):
        if event['type']=='input_audio_buffer.cleared' and self.pending_clear:
            pending=self.pending_clear
            if pending['generation']!=self.generation:raise Fault('MATERIAL_CONTEXT_UNCONFIRMED')
            result=await self.session.manager.material_control(self.session.task,pending['pause_id'],'cleared',self.generation,
                provider_session_id=self.session.provider_session_id,provider_event_id=identifier(event.get('event_id')),
                clear_request_id=pending['request_id'])
            if (not isinstance(result,dict) or result.get('id')!=pending['pause_id'] or result.get('session_id')!=self.session.task['session_id']
                    or result.get('status')!='paused' or result.get('turn_generation')!=self.generation):
                raise Fault('MATERIAL_CONTEXT_UNCONFIRMED')
            self.pending_clear=None;s=self.session;s.clearing_input=False;s.waiting_commit=False
            s.turn_audio=0;s.turn_has_frame=False;s.pending_since=None
            await s.send_browser('material.paused',authorize=False,pause_id=pending['pause_id'],clear_audio=True)
            return True
        item=event.get('item')
        if event['type']=='conversation.item.created' and self.context:
            expected=self.context
            if (not isinstance(item,dict) or item.get('type')!='message' or item.get('role')!='user'
                    or not isinstance(item.get('content'),list) or len(item['content'])!=1
                    or item['content'][0].get('type')!='input_text' or item['content'][0].get('text')!=expected['text']
                    or event.get('previous_item_id')!=expected['anchor'] or expected['generation']!=self.generation):
                raise Fault('MATERIAL_CONTEXT_UNCONFIRMED')
            item_id=identifier(item.get('id'))
            await self.session.manager.material_callback(self.session.task,expected['id'],'context-ack',self.generation,
                provider_session_id=self.session.provider_session_id,provider_event_id=identifier(event.get('event_id')),
                request_item_id=expected['request_id'],provider_item_id=item_id)
            self.session.remember_item(item_id,hashlib.sha256(wire([item,event.get('previous_item_id')]).encode()).digest())
            self.acknowledged_material=(expected['id'],self.generation)
            self.context=None;self.current=None;self.session.pending_since=None
            await self.session.send_browser('material.context_accepted',authorize=False,material_id=expected['id'],message_id=expected['message_id'])
            return True
        return False

    def dispatch(self,material):
        if material is None:return
        if not isinstance(material,dict):raise Fault('MATERIAL_INPUT_UNAVAILABLE')
        mid=identifier(material.get('id'))
        if mid in self.material_ids:return
        if not self.paused or self.pending_clear or self.current or self.context or self.job and not self.job.done():
            raise Fault('MATERIAL_CONTEXT_UNCONFIRMED')
        if material.get('turn_generation')!=self.generation:raise Fault('MATERIAL_INPUT_UNAVAILABLE')
        identifier(material.get('message_id'))
        images=material.get('images') or []
        if not isinstance(images,list) or len(images)>self.limits['max_images']:raise Fault('MATERIAL_LIMIT')
        prompt=material.get('prompt') or ''
        if not isinstance(prompt,str) or len(prompt.encode('utf-16-le'))//2>self.limits['max_text_chars']:raise Fault('MATERIAL_LIMIT')
        ids=set()
        for image in images:
            aid=image.get('attachment_id') if isinstance(image,dict) else None
            if (not isinstance(aid,str) or not re.fullmatch(r'[1-9][0-9]{0,18}',aid)
                    or image.get('variant')!='display' or aid in ids):raise Fault('MATERIAL_INPUT_UNAVAILABLE')
            ids.add(aid)
        if len(self.material_ids)>=self.limits['max_session_materials']:raise Fault('MATERIAL_LIMIT')
        self.material_ids.add(mid);captured=dict(material);self.current=captured
        async def execute():
            observed=[];results=[];error=None
            try:
                if images and (material.get('perception_model')!=self.allowed_model or not self.allowed_model):
                    raise Fault('MATERIAL_PERCEPTION_NOT_CONFIGURED')
                for image in images:
                    report=dict(attachment_id=image['attachment_id'],model=self.allowed_model,usage=None,provider_request_id=None)
                    data=await self.session.manager.material_media(self.session.task,mid,image['attachment_id'],self.limits['max_image_bytes'])
                    observed.append(report)
                    try:result=await self.perception.describe(data,image['attachment_id'],self.allowed_model,prompt)
                    except (PerceptionFailure,asyncio.CancelledError) as failure:
                        report.update(getattr(failure,'observed',{}));raise
                    report.update({k:v for k,v in result.items() if k!='text'})
                    if result['usage'] is not None:
                        self.known_tokens+=result['usage']['total_tokens']
                        if self.known_tokens>self.limits['max_session_tokens']:raise Fault('MATERIAL_LIMIT')
                    results.append(result)
                prepared=await self.session.manager.material_callback(self.session.task,mid,'prepared',captured['turn_generation'],images=results)
                if captured['turn_generation']==self.generation:self.session.enqueue('material_result',(captured,prepared,None))
            except asyncio.CancelledError:raise
            except Exception as failure:
                error=failure if isinstance(failure,(Fault,AuthorityLost)) else Fault('PERCEPTION_ERROR')
                if captured['turn_generation']==self.generation:self.session.enqueue('material_result',(captured,None,error))
            finally:
                if observed:
                    async def audit():
                        try:await self.session.manager.material_callback(self.session.task,mid,'perception-usage',captured['turn_generation'],images=observed)
                        except (AuthorityLost,Fault):pass
                    audit_job=asyncio.create_task(audit());self.audit_jobs.add(audit_job);audit_job.add_done_callback(self.audit_jobs.discard)
                    await asyncio.shield(audit_job)
        async def bounded():
            try:await asyncio.wait_for(execute(),45)
            except asyncio.TimeoutError:
                if captured['turn_generation']==self.generation:
                    self.session.enqueue('material_result',(captured,None,Fault('PERCEPTION_TIMEOUT')))
        self.job=asyncio.create_task(bounded());self.jobs.add(self.job);self.job.add_done_callback(self.jobs.discard)

    async def result(self,captured,prepared,error):
        if captured['turn_generation']!=self.generation or self.current!=captured:return
        await self.session.authorize(force_busy=True)
        if captured['turn_generation']!=self.generation:return
        if error:
            if isinstance(error,AuthorityLost):raise error
            await self.session.manager.material_callback(self.session.task,captured['id'],'fail',self.generation,error_code=error.code)
            self.current=None
            await self.session.send_browser('material.failed',authorize=False,material_id=captured['id'],code=error.code)
            return
        text=prepared.get('context_text') if isinstance(prepared,dict) else None
        if (not isinstance(prepared,dict) or prepared.get('material_id')!=captured['id']
                or prepared.get('message_id')!=captured['message_id'] or prepared.get('turn_generation')!=self.generation):
            raise Fault('MATERIAL_CONTEXT_UNCONFIRMED')
        if not isinstance(text,str) or not text.strip() or len(text.encode('utf-16-le'))//2>self.limits['max_text_chars']:
            raise Fault('MATERIAL_LIMIT')
        request_id=identifier(prepared.get('context_item_id'))
        self.context=dict(id=captured['id'],message_id=captured['message_id'],request_id=request_id,text=text,
            anchor=self.session.last_provider_item_id,generation=self.generation)
        await self.session.send_provider({'type':'conversation.item.create','item':dict(id=request_id,type='message',role='user',content=[dict(type='input_text',text=text)])})
        self.session.pending_since=time.monotonic()

    def response_done(self,generation):
        if self.awaiting_reply and self.reply_generation==generation:
            self.awaiting_reply=False;self.reply_generation=None

    async def close(self):
        for job in self.jobs:job.cancel()
        await asyncio.gather(*self.jobs,return_exceptions=True)
        await asyncio.gather(*self.audit_jobs,return_exceptions=True)
        await self.perception.close()
