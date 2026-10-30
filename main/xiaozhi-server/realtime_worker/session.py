"""One serialized, bounded provider session per consumed Java lease."""
import asyncio
import base64
import binascii
from collections import OrderedDict
import hashlib
import io
import json
import re
import time
import warnings
from PIL import Image
from .config import MODEL
from .control import AuthorityLost, Fault, ResponseInterrupted
from .provider import provider_error


class TerminalReceipt(Exception):
    def __init__(self, status):
        self.status = status
        super().__init__('Realtime session ended')


def integer(value, lower, upper):
    if type(value) is not int or not lower <= value <= upper:
        raise Fault('WORKER_ERROR')
    return value


def safe_id(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_.:-]{1,200}', value):
        raise Fault('PROVIDER_ERROR')
    return value


def decoded(value, maximum):
    if not isinstance(value, str) or len(value) > 4 * ((maximum + 2) // 3):
        raise Fault('INPUT_LIMIT')
    try:
        data = base64.b64decode(value, validate=True)
    except (ValueError, binascii.Error):
        raise Fault('INPUT_LIMIT') from None
    if not data or len(data) > maximum:
        raise Fault('INPUT_LIMIT')
    return data


def text_units(text):
    # Java String length is UTF-16 units, including surrogate pairs.
    try: return len(text.encode('utf-16-le')) // 2
    except UnicodeError: raise Fault('INPUT_LIMIT') from None


def actual_usage(usage):
    keys = ('input_tokens','output_tokens','total_tokens')
    if not isinstance(usage, dict) or any(type(usage.get(key)) is not int or usage[key] < 0 for key in keys):
        return None
    return {key:usage[key] for key in keys}


def validate_task(task, config):
    if not isinstance(task, dict) or task.get('model') != MODEL:
        raise Fault('WORKER_ERROR')
    for key in ('session_id', 'conversation_id'):
        if not isinstance(task.get(key), str) or not re.fullmatch(r'[a-f0-9]{32}', task[key]):
            raise Fault('WORKER_ERROR')
    if task.get('server_id') != config.server_id: raise Fault('WORKER_ERROR')
    safe_id(task.get('lease_token'))
    integer(task.get('control_epoch'), 0, 2**63-1)
    integer(task.get('lease_seconds'), 6, 12)
    limits = task.get('limits')
    bounds = {'heartbeat_seconds': 2, 'max_session_seconds': config.max_session_seconds,
              'max_audio_chunk_bytes': 65536, 'max_audio_turn_bytes': 1920000,
              'max_frame_bytes': 190000, 'max_frame_pixels': 921600,
              'max_event_text_chars': 16000, 'max_output_chars': 128000}
    if not isinstance(limits, dict): raise Fault('WORKER_ERROR')
    for key, maximum in bounds.items():
        integer(limits.get(key), 1, maximum)
    integer(limits.get('min_frame_interval_ms'), 1000, 60000)
    if task.get('input_audio') != dict(encoding='s16le', sample_rate=16000, channels=1): raise Fault('WORKER_ERROR')
    if task.get('output_audio') != dict(encoding='s16le', sample_rate=24000, channels=1): raise Fault('WORKER_ERROR')
    consent = task.get('consent')
    if (not isinstance(consent, dict) or consent.get('microphone') is not True
            or type(consent.get('camera')) is not bool): raise Fault('WORKER_ERROR')
    context = task.get('context')
    if not isinstance(context, list) or len(context) > 40: raise Fault('WORKER_ERROR')
    total = 0
    for message in context:
        if (not isinstance(message, dict) or message.get('role') not in ('user', 'assistant')
                or not isinstance(message.get('content'), str)):
            raise Fault('WORKER_ERROR')
        total += text_units(message['content'])
    if total > 64000: raise Fault('WORKER_ERROR')
    integer(task.get('context_seq'), 0, 2**63-1)
    return task


class Session:
    def __init__(self, config, manager, provider, browser, task):
        self.config, self.manager, self.provider_factory, self.browser = config, manager, provider, browser
        self.task = validate_task(task, config)
        self.limits = task['limits']
        self.sequence = 0
        self.queue = asyncio.Queue(maxsize=config.queue_size)
        self.failure = None
        self.provider = None
        self.ready = False
        self.closed = False
        self.created = False
        self.updated = False
        self.provider_session_id = None
        self.started = time.monotonic()
        self.last_authorized = self.started
        self.setup_deadline = self.started + 20
        self.waiting_commit = False
        self.waiting_response = False
        self.pending_since = None
        self.turn_audio = self.input_audio = self.output_audio = self.frame_bytes = 0
        self.last_frame = -10000
        self.response = None
        self.responses = set()
        self.terminal_responses = {}
        self.interrupt = None
        self.pending_injection = None
        self.injections = set()
        self.context_index = 0
        self.context_pending = None
        self.seen = OrderedDict()
        self.early = []
        self.early_since = None
        self.tasks = []

    def busy(self):
        return bool(not self.ready or self.turn_audio or self.waiting_commit or self.waiting_response
                    or self.response or self.pending_injection)

    def enqueue(self, kind, value):
        try:
            self.queue.put_nowait((kind, value))
        except asyncio.QueueFull:
            self.failure = Fault('INPUT_LIMIT')

    async def browser_read(self):
        try:
            async for message in self.browser:
                from aiohttp import WSMsgType
                if message.type != WSMsgType.TEXT:
                    self.enqueue('fault', Fault('INPUT_LIMIT')); return
                try:
                    event = json.loads(message.data)
                except (ValueError, TypeError):
                    self.enqueue('fault', Fault('INPUT_LIMIT')); return
                if not self.ready:
                    self.enqueue('fault', Fault('INPUT_LIMIT')); return
                self.enqueue('client', event)
                if self.failure: return
        except Exception:
            pass
        self.enqueue('fault', Fault('CLIENT_DISCONNECTED'))

    async def provider_read(self):
        try:
            async for raw in self.provider:
                if not isinstance(raw, str): raise Fault('PROVIDER_ERROR')
                try: event = json.loads(raw)
                except (ValueError, TypeError): raise Fault('PROVIDER_ERROR') from None
                self.enqueue('provider', event)
                if self.failure: return
            self.enqueue('fault', Fault('PROVIDER_UNAVAILABLE'))
        except asyncio.CancelledError:
            raise
        except Exception as error:
            self.enqueue('fault', error if isinstance(error, Fault) else Fault('PROVIDER_UNAVAILABLE'))

    async def pulse(self):
        while True:
            await asyncio.sleep(self.limits['heartbeat_seconds'])
            # Ticks don't consume space needed for provider/client error handling.
            if not self.queue.full(): self.enqueue('tick', None)

    async def send_provider(self, event):
        if self.closed: raise AuthorityLost()
        if self.failure: raise self.failure
        try:
            await asyncio.wait_for(self.provider.send(json.dumps(event, separators=(',', ':'))), .5)
        except asyncio.TimeoutError:
            raise Fault('PROVIDER_TIMEOUT') from None
        except Exception:
            raise Fault('PROVIDER_UNAVAILABLE') from None

    async def authorize(self, force_busy=False):
        if self.closed: raise AuthorityLost()
        state = await self.manager.heartbeat(self.task, self.busy() or force_busy)
        self.last_authorized = time.monotonic()
        commands, injections = state.get('commands', []), state.get('injections', [])
        if not isinstance(commands, list) or not isinstance(injections, list) or len(commands) > 1 or len(injections) > 1:
            raise Fault('WORKER_ERROR')
        for command in commands:
            if not isinstance(command, dict) or command.get('type') != 'interrupt': raise Fault('WORKER_ERROR')
            safe_id(command.get('id'))
            rid = safe_id(command.get('response_id'))
            if rid in self.terminal_responses:
                completed = self.terminal_responses[rid]
                await self.canonical(completed['source'], 'command.ack', command_id=command['id'], response_id=rid)
                await self.send_browser('response.canceled', authorize=False, response_id=rid,
                    message_id=completed.get('message_id'), status='canceled', clear_audio=True)
                continue
            if not self.response or rid != self.response['id'] or self.interrupt: raise Fault('WORKER_ERROR')
            self.interrupt = command
            await self.send_provider({'type': 'response.cancel'})
            self.pending_since = time.monotonic()
            await self.send_browser('response.canceled', authorize=False, response_id=self.response['id'],
                                    status='interrupting', clear_audio=True)
        for injection in injections:
            if self.busy() or force_busy: raise Fault('WORKER_ERROR')
            if (not isinstance(injection, dict) or not isinstance(injection.get('text'), str)
                    or not 0 < text_units(injection['text']) <= 8000): raise Fault('WORKER_ERROR')
            for key in ('id', 'message_id', 'provider_item_id'): safe_id(injection.get(key))
            if injection['id'] in self.injections: raise Fault('WORKER_ERROR')
            self.injections.add(injection['id'])
            self.pending_injection = injection
            self.pending_since = time.monotonic()
            await self.send_provider({'type':'conversation.item.create', 'item': {
                'id':injection['provider_item_id'], 'type':'message', 'role':'user',
                'content':[{'type':'input_text','text':injection['text']}]}})

    async def canonical(self, source, kind, **payload):
        seed = json.dumps([source.get('event_id', source), kind], sort_keys=True, separators=(',', ':'))
        event_id = 'bridge_' + hashlib.sha256(seed.encode()).hexdigest()
        result = await self.manager.event(self.task, self.sequence + 1, event_id, kind, **payload)
        self.sequence += 1
        state = result.get('session')
        if isinstance(state, dict) and state.get('status') in ('ended','failed','expired','canceled') and kind != 'session.error':
            raise TerminalReceipt(state['status'])
        return result

    async def send_browser(self, kind, authorize=True, terminal=False, **payload):
        if authorize: await self.authorize(force_busy=True)
        if self.closed or self.failure and not terminal: raise AuthorityLost()
        if (self.interrupt or self.response and self.response.get('suppressed')) and kind in ('audio.delta', 'transcript.delta', 'response.started'):
            return
        if self.response and payload.get('response_id') == self.response['id'] and self.response.get('message_id'):
            payload['message_id'] = self.response['message_id']
        try:
            await asyncio.wait_for(self.browser.send_json(dict(type=kind, session_id=self.task['session_id'],
                control_epoch=self.task['control_epoch'], **payload)), .5)
        except Exception:
            raise Fault('CLIENT_DISCONNECTED') from None

    async def seed_context(self, source):
        if self.context_index < len(self.task['context']):
            message = self.task['context'][self.context_index]
            item_id = 'ctx_' + hashlib.sha256((self.task['session_id']+':'+str(self.context_index)).encode()).hexdigest()[:28]
            self.context_pending = item_id
            self.context_index += 1
            await self.send_provider({'type':'conversation.item.create', 'item':dict(
                id=item_id, type='message', role=message['role'], content=[{
                'type':'input_text' if message['role']=='user' else 'text', 'text':message['content']}])})
        else:
            await self.canonical(source, 'session.ready', provider_session_id=self.provider_session_id)
            self.ready = True
            self.pending_since = None
            await self.send_browser('ready')

    async def client_event(self, event):
        if not isinstance(event, dict): raise Fault('INPUT_LIMIT')
        kind = event.get('type')
        allowed = {'audio.append': {'type','audio'}, 'audio.commit': {'type'},
                   'frame.append': {'type','image','mime_type'}}
        if kind not in allowed or set(event) != allowed[kind]: raise Fault('INPUT_LIMIT')
        await self.authorize(force_busy=True)
        if self.response or self.waiting_response or self.waiting_commit or self.pending_injection:
            await self.send_browser('error', authorize=False, code='RESPONSE_IN_PROGRESS')
            return
        if kind == 'audio.append':
            data = decoded(event['audio'], self.limits['max_audio_chunk_bytes'])
            if len(data) % 2: raise Fault('INPUT_LIMIT')
            self.turn_audio += len(data); self.input_audio += len(data)
            if self.turn_audio > self.limits['max_audio_turn_bytes'] or self.input_audio > self.config.max_input_audio_bytes:
                raise Fault('INPUT_LIMIT')
            await self.send_provider({'type':'input_audio_buffer.append','audio':event['audio']})
        elif kind == 'audio.commit':
            if not self.turn_audio: raise Fault('INPUT_LIMIT')
            self.waiting_commit = True
            self.pending_since = time.monotonic()
            await self.send_provider({'type':'input_audio_buffer.commit'})
        else:
            if (not self.task['consent']['camera'] or not self.turn_audio or event['mime_type'] != 'image/jpeg'
                    or not isinstance(event['image'], str) or len(event['image']) > 256000): raise Fault('INPUT_LIMIT')
            data = decoded(event['image'], self.limits['max_frame_bytes'])
            now = time.monotonic()
            if (now - self.last_frame) * 1000 < self.limits['min_frame_interval_ms']: raise Fault('INPUT_LIMIT')
            try:
                with warnings.catch_warnings():
                    warnings.simplefilter('error', Image.DecompressionBombWarning)
                    with Image.open(io.BytesIO(data)) as image:
                        if (image.format != 'JPEG' or image.width < 1 or image.height < 1
                                or image.width*image.height > self.limits['max_frame_pixels']): raise ValueError()
                        image.load()
            except Exception:
                raise Fault('INPUT_LIMIT') from None
            self.frame_bytes += len(data)
            if self.frame_bytes > self.config.max_frame_total_bytes: raise Fault('INPUT_LIMIT')
            self.last_frame = now
            await self.send_provider({'type':'input_image_buffer.append','image':event['image']})

    async def provider_event(self, event):
        if not isinstance(event, dict) or not isinstance(event.get('type'), str):
            raise Fault(provider_error(event) if isinstance(event, dict) else 'PROVIDER_ERROR')
        kind = event['type']
        if kind == 'error': raise Fault(provider_error(event))
        # Duplicate wire events cannot append text or usage twice. Changed reuse fails closed.
        event_id = event.get('event_id')
        if event_id is not None:
            safe_id(event_id)
            digest = hashlib.sha256(json.dumps(event, sort_keys=True).encode()).digest()
            if event_id in self.seen:
                if self.seen[event_id] != digest: raise Fault('PROVIDER_ERROR')
                return
            self.seen[event_id] = digest
            if len(self.seen) > 2048: self.seen.popitem(last=False)
        await self.authorize(force_busy=True)
        if kind == 'session.created':
            if self.created: raise Fault('PROVIDER_ERROR')
            self.created = True
            await self.send_provider(self.provider_factory.session_update())
        elif kind == 'session.updated':
            if not self.created or self.updated: raise Fault('PROVIDER_ERROR')
            self.updated = True
            self.provider_session_id = safe_id(event.get('session', {}).get('id'))
            await self.seed_context(event)
        elif kind == 'conversation.item.created':
            item = event.get('item', {})
            item_id = item.get('id')
            if self.context_pending == item_id and item_id is not None:
                self.context_pending = None
                await self.seed_context(event)
            elif self.pending_injection and self.pending_injection['provider_item_id'] == item_id:
                injection = self.pending_injection
                await self.canonical(event, 'injection.ack', injection_id=injection['id'], provider_item_id=item_id)
                self.pending_injection = None
                self.waiting_response = True
                self.pending_since = time.monotonic()
                await self.send_browser('injection.status', injection_id=injection['id'], message_id=injection['message_id'], status='acknowledged', provider_item_id=item_id)
                await self.send_provider({'type':'response.create'})
            elif item.get('type') in ('function_call', 'mcp_approval_request', 'mcp_call'):
                raise Fault('UNSUPPORTED_TOOL')
        elif kind == 'input_audio_buffer.committed':
            if not self.waiting_commit: raise Fault('PROVIDER_ERROR')
            await self.canonical(event, 'input.committed', provider_item_id=safe_id(event.get('item_id')))
            self.waiting_commit = False; self.turn_audio = 0
            self.waiting_response = True
            self.pending_since = time.monotonic()
            await self.send_provider({'type':'response.create'})
        elif kind == 'conversation.item.input_audio_transcription.failed':
            raise Fault('PROVIDER_ERROR')
        elif kind == 'conversation.item.input_audio_transcription.completed':
            text = event.get('transcript')
            if not isinstance(text, str) or text_units(text) > self.limits['max_event_text_chars']: raise Fault('INPUT_LIMIT')
            await self.canonical(event, 'input.transcript', provider_item_id=safe_id(event.get('item_id')), text=text)
        elif kind == 'response.created':
            rid = safe_id(event.get('response', {}).get('id'))
            if not self.ready or not self.waiting_response or self.response or rid in self.responses or len(self.responses) >= self.config.max_turns:
                raise Fault('PROVIDER_ERROR')
            self.responses.add(rid)
            self.response = dict(id=rid, channel=None, text='', final=None)
            self.waiting_response = False
            self.pending_since = time.monotonic()
            receipt = await self.canonical(event, 'response.started', response_id=rid)
            self.response['message_id'] = receipt.get('message_id')
            await self.send_browser('response.started', response_id=rid)
            early, self.early = self.early, []
            self.early_since = None
            for pending in early: await self.response_event(pending)
        elif kind.startswith('response.function_call') or kind.startswith('response.mcp'):
            raise Fault('UNSUPPORTED_TOOL')
        elif kind in ('response.audio.delta','response.text.delta','response.audio_transcript.delta',
                      'response.text.done','response.audio_transcript.done','response.done'):
            await self.response_event(event)
        elif kind in ('response.output_item.added','response.output_item.done'):
            if event.get('item', {}).get('type') in ('function_call', 'mcp_call', 'mcp_approval_request'):
                raise Fault('UNSUPPORTED_TOOL')
        # Provider metadata, reasoning, partial input ASR and other noncanonical events are not relayed.

    async def response_event(self, event):
        kind = event['type']
        rid = event.get('response', {}).get('id') if kind == 'response.done' else event.get('response_id')
        safe_id(rid)
        if rid in self.terminal_responses: return
        if not self.response:
            if rid in self.responses: return  # Late output from an already terminal turn.
            if len(self.early) >= 8 or sum(len(json.dumps(e)) for e in self.early) + len(json.dumps(event)) > 512000:
                raise Fault('PROVIDER_ERROR')
            self.early.append(event)
            self.early_since = self.early_since or time.monotonic()
            return
        if rid != self.response['id']: raise Fault('PROVIDER_ERROR')
        self.pending_since = time.monotonic()
        if kind == 'response.audio.delta':
            if self.interrupt or self.response.get('suppressed'): return
            data = decoded(event.get('delta'), 512000)
            if len(data) % 2: raise Fault('PROVIDER_ERROR')
            self.output_audio += len(data)
            if self.output_audio > self.config.max_output_audio_bytes: raise Fault('INPUT_LIMIT')
            await self.send_browser('audio.delta', response_id=rid, audio=event['delta'])
        elif kind in ('response.text.delta','response.audio_transcript.delta'):
            if self.interrupt or self.response.get('suppressed'): return
            channel = 'text' if kind=='response.text.delta' else 'transcript'
            if self.response['channel'] is None: self.response['channel'] = channel
            if self.response['channel'] != channel: return
            text = event.get('delta')
            if not isinstance(text, str) or text_units(text) > self.limits['max_event_text_chars']: raise Fault('INPUT_LIMIT')
            if not text: return
            self.response['text'] += text
            if text_units(self.response['text']) > self.limits['max_output_chars']: raise Fault('INPUT_LIMIT')
            try:
                await self.canonical(event, 'response.delta', response_id=rid, text=text)
            except ResponseInterrupted:
                self.response['suppressed'] = True
                await self.authorize(force_busy=True)
                if not self.interrupt:
                    await self.send_browser('response.canceled', authorize=False, response_id=rid,
                        status='interrupting', clear_audio=True)
                return
            await self.send_browser('transcript.delta', response_id=rid, delta=text)
        elif kind in ('response.text.done','response.audio_transcript.done'):
            channel = 'text' if kind=='response.text.done' else 'transcript'
            text = event.get('text') if channel=='text' else event.get('transcript')
            if self.response['channel'] in (None, channel):
                if not isinstance(text, str) or text_units(text) > self.limits['max_output_chars']: raise Fault('INPUT_LIMIT')
                self.response['channel'] = channel
                self.response['final'] = text
        elif kind == 'response.done':
            response = event['response']
            status = response.get('status')
            if any(item.get('type') in ('function_call','mcp_call','mcp_approval_request') for item in response.get('output', []) if isinstance(item, dict)):
                raise Fault('UNSUPPORTED_TOOL')
            if self.interrupt or self.response.get('suppressed') or status in ('cancelled', 'canceled'):
                usage = actual_usage(response.get('usage'))
                await self.canonical(event, 'response.canceled', response_id=rid, usage=usage)
                self.terminal_responses[rid] = dict(source={'event_id':event.get('event_id', 'done_'+rid)}, message_id=self.response.get('message_id'))
                if self.interrupt:
                    await self.canonical(event, 'command.ack', command_id=self.interrupt['id'], response_id=rid)
                await self.send_browser('response.canceled', response_id=rid, status='canceled', clear_audio=True, usage=usage)
            elif status in ('completed','incomplete'):
                text = self.response['final']
                if text is None:
                    parts = [part for item in response.get('output', []) if isinstance(item, dict)
                             for part in item.get('content', []) if isinstance(part, dict)]
                    key = 'text' if self.response['channel']=='text' else 'transcript'
                    final_parts = [part[key] for part in parts if isinstance(part.get(key), str)]
                    text = ''.join(final_parts) if final_parts else self.response['text']
                if text_units(text) > self.limits['max_output_chars']: raise Fault('INPUT_LIMIT')
                details = response.get('status_details') or {}
                reason = details.get('reason')
                finish = 'stop' if status=='completed' else {'max_output_tokens':'length','content_filter':'content_filter'}.get(reason,'unknown')
                usage = actual_usage(response.get('usage'))
                receipt = await self.canonical(event, 'response.completed', response_id=rid, text=text, finish_reason=finish, usage=usage)
                self.terminal_responses[rid] = dict(source={'event_id':event.get('event_id', 'done_'+rid)}, message_id=self.response.get('message_id'))
                if receipt.get('message_status') == 'canceled':
                    await self.send_browser('response.canceled', response_id=rid, status='canceled', clear_audio=True, usage=usage)
                else:
                    await self.send_browser('response.completed', response_id=rid, text=text, finish_reason=finish, usage=usage)
            else:
                raise Fault(provider_error(response.get('status_details') or {}))
            self.response = None; self.interrupt = None; self.pending_since = None

    def check_deadlines(self):
        now = time.monotonic()
        if now-self.started > self.limits['max_session_seconds']: raise Fault('INPUT_LIMIT')
        if not self.ready and now > self.setup_deadline: raise Fault('PROVIDER_TIMEOUT')
        if self.early_since and now-self.early_since > 2: raise Fault('PROVIDER_ERROR')
        if self.pending_since and now-self.pending_since > self.config.ack_timeout_seconds:
            raise Fault('PROVIDER_TIMEOUT')
        # Independent of a saturated event queue: stop if authority hasn't been checked in time.
        if now-self.last_authorized > 5.5: raise AuthorityLost()

    async def run(self):
        try:
            await self.send_browser('connected', authorize=False)
            self.provider = await self.provider_factory.connect()
            self.tasks = [asyncio.create_task(self.browser_read()), asyncio.create_task(self.provider_read()), asyncio.create_task(self.pulse())]
            while True:
                self.check_deadlines()
                if self.failure: raise self.failure
                try: kind, value = await asyncio.wait_for(self.queue.get(), .25)
                except asyncio.TimeoutError: continue
                if kind == 'fault': raise value
                if kind == 'tick': await self.authorize()
                elif kind == 'client': await self.client_event(value)
                elif kind == 'provider': await self.provider_event(value)
        except TerminalReceipt as terminal:
            try: await self.send_browser('ended', authorize=False, terminal=True, status=terminal.status, clear_audio=True)
            except Fault: pass
        except AuthorityLost:
            # No callback or late playback after lease loss, even if delivery is uncertain.
            pass
        except Fault as failure:
            await self.report_failure(failure.code)
        except asyncio.CancelledError:
            raise
        except Exception:
            await self.report_failure('WORKER_ERROR')
        finally:
            self.closed = True
            for task in self.tasks: task.cancel()
            await asyncio.gather(*self.tasks, return_exceptions=True)
            if self.provider is not None:
                try: await asyncio.wait_for(self.provider.close(), .5)
                except Exception: pass
            try: await asyncio.wait_for(self.browser.close(), .5)
            except Exception: pass

    async def report_failure(self, code):
        try:
            if self.pending_injection:
                await self.canonical({'event_id':'worker_injection_failure'}, 'injection.failed',
                    injection_id=self.pending_injection['id'], error_code='INJECTION_UNCONFIRMED')
            await self.canonical({'event_id':'worker_session_failure'}, 'session.error', error_code=code)
            # The accepted terminal error may revoke the lease, so no further heartbeat is needed.
            await self.send_browser('error', authorize=False, terminal=True, code=code, clear_audio=True,
                                    **({'response_id':self.response['id']} if self.response else {}))
        except (AuthorityLost, Fault, TerminalReceipt):
            pass
