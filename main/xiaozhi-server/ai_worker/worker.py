import asyncio
import base64
import re
import logging
from .provider import ProviderFailure
from .transport import ControlError, LeaseLost

logger = logging.getLogger('ai_worker')


def actual_usage(usage):
    if usage is None:
        return None
    values = [getattr(usage, name, None) for name in ('prompt_tokens', 'completion_tokens', 'total_tokens')]
    if any(type(value) is not int or value < 0 for value in values):
        return None
    return dict(zip(('input_tokens', 'output_tokens', 'total_tokens'), values))


class Worker:
    def __init__(self, config, manager, provider):
        self.config, self.manager, self.provider = config, manager, provider

    async def heartbeat(self, task):
        while True:
            await asyncio.sleep(self.config.heartbeat_seconds)
            try:
                ack = await self.manager.callback(task, 'heartbeat')
            except (ControlError, LeaseLost):
                raise LeaseLost() from None
            if not isinstance(ack, dict) or ack.get('active') is not True:
                raise LeaseLost()

    async def prepare(self, task):
        messages = task.get('messages')
        if not isinstance(messages, list):
            raise ProviderFailure('MEDIA_INPUT_UNAVAILABLE')
        prepared, count, image_bytes, text_chars = [], 0, 0, 0
        for index, message in enumerate(messages):
            refs = message.get('attachments')
            # Keep the B2 plain-text wire format and strip internal-only properties.
            clean = {'role': message.get('role'), 'content': message.get('content', '')}
            if not refs:
                prepared.append(clean)
                continue
            if not isinstance(refs, list) or index != len(messages) - 1 or clean['role'] != 'user':
                raise ProviderFailure('MEDIA_INPUT_UNAVAILABLE')
            count += len(refs)
            if count > 16:
                raise ProviderFailure('MEDIA_INPUT_UNAVAILABLE')
            content = [{'type': 'text', 'text': clean['content']}]
            for ref in refs:
                if not isinstance(ref, dict):
                    raise ProviderFailure('MEDIA_INPUT_UNAVAILABLE')
                variant, mime, kind = ref.get('variant'), ref.get('mime_type'), ref.get('kind')
                image = variant == 'display' or bool(isinstance(variant, str) and re.fullmatch(r'frame_[0-5]', variant))
                textual = variant in ('text', 'transcript')
                if (kind not in ('image', 'video', 'audio', 'file') or not (image or textual)
                        or (image and mime != 'image/jpeg') or (textual and mime != 'text/plain')
                        or (variant == 'display' and kind != 'image')
                        or (variant == 'text' and kind != 'file')
                        or (variant == 'transcript' and kind not in ('audio', 'video'))):
                    raise ProviderFailure('MEDIA_INPUT_UNAVAILABLE')
                if image and task.get('model') != getattr(self.config, 'vision_model', ''):
                    raise ProviderFailure('MODEL_CAPABILITY_UNAVAILABLE')
                if image and variant != 'display':
                    timestamp = ref.get('time_ms')
                    # The manager's global Long serializer emits decimal strings.
                    # Normalize only this bounded measurement, never identifiers.
                    if type(timestamp) is str and re.fullmatch(r'(0|[1-9][0-9]{0,5})', timestamp):
                        timestamp = int(timestamp)
                    if kind != 'video' or type(timestamp) is not int or not 0 <= timestamp <= 180000:
                        raise ProviderFailure('MEDIA_INPUT_UNAVAILABLE')
                data = await self.manager.media(task, ref, (8 << 20) if image else (256 << 10))
                if image:
                    image_bytes += len(data)
                    if not data or image_bytes > 24 << 20:
                        raise ProviderFailure('MEDIA_INPUT_UNAVAILABLE')
                    note = f"Verified attachment {ref.get('attachment_id')}"
                    if kind == 'video':
                        note += f" video frame at {timestamp} ms"
                    content.append({'type': 'text', 'text': note})
                    content.append({'type': 'image_url', 'image_url': {'url':
                        'data:image/jpeg;base64,' + base64.b64encode(data).decode('ascii')}})
                else:
                    try:
                        text = data.decode('utf-8')
                    except UnicodeError:
                        raise ProviderFailure('MEDIA_INPUT_UNAVAILABLE') from None
                    text_chars += len(text)
                    if text_chars > 60000 or '\x00' in text:
                        raise ProviderFailure('MEDIA_INPUT_UNAVAILABLE')
                    note = f"Verified {variant} for attachment {ref.get('attachment_id')}:\n"
                    if not text.strip():
                        text = '[No extractable text is available; do not infer unread scanned pages or speech.]'
                    content.append({'type': 'text', 'text': note + text})
            clean['content'] = content
            prepared.append(clean)
        return {**task, 'messages': prepared}

    async def generate(self, task):
        sequence = 0
        usage, finish_reason, request_id = None, None, None
        try:
            if any(message.get('attachments') for message in task.get('messages', [])):
                prepared = await asyncio.wait_for(self.prepare(task), getattr(self.config, 'provider_timeout_seconds', 60))
            else:
                prepared = await self.prepare(task)
        except asyncio.TimeoutError:
            raise ProviderFailure('MEDIA_INPUT_UNAVAILABLE') from None
        async with self.provider.stream(prepared) as stream:
            response = getattr(stream, 'response', None)
            headers = getattr(response, 'headers', {})
            request_id = headers.get('x-request-id') or headers.get('x-dashscope-request-id')
            async for chunk in stream:
                reported = actual_usage(getattr(chunk, 'usage', None))
                if reported is not None:
                    usage = reported
                for choice in chunk.choices or []:
                    if choice.finish_reason:
                        finish_reason = choice.finish_reason
                    content = getattr(choice.delta, 'content', None)
                    if content:
                        # Serialized callbacks provide backpressure; no unbounded token queue.
                        for start in range(0, len(content), 4096):
                            sequence += 1
                            await self.manager.callback(task, 'delta', sequence=sequence, text=content[start:start + 4096])
            if finish_reason is None:
                raise ProviderFailure('PROVIDER_ERROR')
        await self.manager.callback(task, 'complete', last_sequence=sequence,
                                    finish_reason=finish_reason, usage=usage,
                                    provider_request_id=request_id)

    async def execute(self, task):
        if task.get('model') not in (self.config.model, getattr(self.config, 'vision_model', '') or self.config.model):
            code = 'MODEL_CAPABILITY_UNAVAILABLE' if getattr(self.config, 'vision_model', '') else 'MODEL_MISMATCH'
            await self.manager.callback(task, 'fail', error_code=code)
            return
        # Reject leases too short for a heartbeat plus bounded transport timeout.
        if not isinstance(task.get('lease_seconds'), (int, float)) or task['lease_seconds'] < 30:
            raise LeaseLost()
        generation = asyncio.create_task(self.generate(task))
        heartbeat = asyncio.create_task(self.heartbeat(task))
        try:
            done, _ = await asyncio.wait((generation, heartbeat), return_when=asyncio.FIRST_COMPLETED)
            # Heartbeat failure wins even if generation becomes ready simultaneously.
            if heartbeat in done:
                await heartbeat
            await generation
        except ProviderFailure as exc:
            await self.manager.callback(task, 'fail', error_code=exc.code)
        finally:
            for job in (generation, heartbeat):
                job.cancel()
            await asyncio.gather(generation, heartbeat, return_exceptions=True)

    async def run(self):
        while True:
            try:
                task = await self.manager.claim()
                if task is not None:
                    await self.execute(task)
                    continue
            except (ControlError, LeaseLost):
                logger.warning('worker authority or transport unavailable')
            except Exception:
                # Never log provider messages, prompts, auth headers, URLs, or exception repr.
                logger.error('worker task failed')
            await asyncio.sleep(self.config.poll_seconds)

    async def close(self):
        await self.provider.close()
        await self.manager.close()
