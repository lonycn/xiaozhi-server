import asyncio
import base64
import re
import logging
import io
import wave

# Official inline audio cap is encoded Base64 < 10 MB, not the B3 upload cap.
AUDIO_PREFIX = 'data:audio/wav;base64,'
MAX_AUDIO_BYTES = ((10_000_000 - 1 - len(AUDIO_PREFIX)) // 4) * 3
from .provider import ProviderFailure
from .transport import ControlError, LeaseLost

logger = logging.getLogger('ai_worker')


def actual_usage(usage):
    if usage is None:
        return None
    values = [getattr(usage, name, None) for name in ('prompt_tokens', 'completion_tokens', 'total_tokens')]
    if any(type(value) is not int or value < 0 for value in values):
        return None
    result = dict(zip(('input_tokens', 'output_tokens', 'total_tokens'), values))
    audio = getattr(getattr(usage, 'prompt_tokens_details', None), 'audio_tokens', None)
    if type(audio) is int and 0 <= audio <= values[0]:
        result['input_audio_tokens'] = audio
    return result


def aggregate_usage(requests):
    if not requests or any(record['usage'] is None for record in requests):
        return None
    keys = set.intersection(*(set(record['usage']) for record in requests))
    return {key: sum(record['usage'][key] for record in requests) for key in keys}


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
                if (not isinstance(ref, dict) or not isinstance(ref.get('attachment_id'), str)
                        or not re.fullmatch(r'[0-9]{1,20}', ref['attachment_id'])):
                    raise ProviderFailure('MEDIA_INPUT_UNAVAILABLE')
                variant, mime, kind = ref.get('variant'), ref.get('mime_type'), ref.get('kind')
                image = variant == 'display' or bool(isinstance(variant, str) and re.fullmatch(r'frame_[0-5]', variant))
                textual = variant in ('text', 'transcript')
                audio = variant == 'audio'
                if (kind not in ('image', 'video', 'audio', 'file') or not (image or textual or audio)
                        or (image and mime != 'image/jpeg') or (textual and mime != 'text/plain')
                        or (audio and (mime != 'audio/wav' or kind not in ('audio', 'video')))
                        or (variant == 'display' and kind != 'image')
                        or (variant == 'text' and kind != 'file')
                        or (variant == 'transcript' and kind not in ('audio', 'video'))):
                    raise ProviderFailure('MEDIA_INPUT_UNAVAILABLE')
                if audio and (not getattr(self.config, 'omni_model', '') or task.get('model') != getattr(self.config, 'omni_model', '')):
                    raise ProviderFailure('MODEL_CAPABILITY_UNAVAILABLE')
                if image and task.get('model') not in (getattr(self.config, 'vision_model', ''), getattr(self.config, 'omni_model', '') or None):
                    raise ProviderFailure('MODEL_CAPABILITY_UNAVAILABLE')
                if image and variant != 'display':
                    timestamp = ref.get('time_ms')
                    # The manager's global Long serializer emits decimal strings.
                    # Normalize only this bounded measurement, never identifiers.
                    if type(timestamp) is str and re.fullmatch(r'(0|[1-9][0-9]{0,5})', timestamp):
                        timestamp = int(timestamp)
                    if kind != 'video' or type(timestamp) is not int or not 0 <= timestamp <= 180000:
                        raise ProviderFailure('MEDIA_INPUT_UNAVAILABLE')
                data = await self.manager.media(task, ref, MAX_AUDIO_BYTES if audio else (8 << 20) if image else (256 << 10))
                if audio:
                    if not data or len(data) > MAX_AUDIO_BYTES:
                        raise ProviderFailure('OMNI_AUDIO_LIMIT')
                    try:
                        with wave.open(io.BytesIO(data), 'rb') as wav:
                            if (wav.getnchannels(), wav.getframerate(), wav.getsampwidth(), wav.getcomptype()) != (1, 16000, 2, 'NONE'):
                                raise ValueError()
                            frames = wav.getnframes()
                            if frames <= 0 or frames > (180 if kind == 'video' else 600) * 16000:
                                raise ValueError()
                            if len(wav.readframes(frames)) != frames * 2:
                                raise ValueError()
                    except (wave.Error, EOFError, ValueError):
                        raise ProviderFailure('MEDIA_INPUT_UNAVAILABLE') from None
                    content.append({'type': 'text', 'text':
                        f"Verified normalized audio for attachment {ref.get('attachment_id')}. "
                        "Describe audible evidence separately from hypotheses. Environmental/mechanical sounds alone "
                        "cannot establish a definite fault or safe operating condition; ask for measurements and expert confirmation."})
                    content.append({'type': 'input_audio', 'input_audio': {'data':
                        AUDIO_PREFIX + base64.b64encode(data).decode('ascii'), 'format': 'wav'}})
                elif image:
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
        from .tools import policy
        selected = policy(task)
        if selected is not None:
            await self.generate_tools(task, *selected)
            return
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
                    if getattr(choice.delta, 'tool_calls', None) or getattr(choice.delta, 'function_call', None):
                        raise ProviderFailure('UNSUPPORTED_TOOL')
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

    async def generate_tools(self, task, names, limits):
        from .tools import Proposals, encoded, result_messages
        sequence, tool_count, output_chars = 0, 0, 0
        requests, seen = [], set()

        async def run():
            nonlocal sequence, tool_count, output_chars
            prepared = await self.prepare(task)
            result_bytes = 0
            for round_number in range(1, limits['max_rounds'] + 2):
                proposals = Proposals(names, limits)
                content_parts = []
                record = dict(round=round_number, provider_request_id=None, usage=None, finish_reason=None)
                requests.append(record)
                async with self.provider.stream(prepared) as stream:
                    headers = getattr(getattr(stream, 'response', None), 'headers', {})
                    record['provider_request_id'] = headers.get('x-request-id') or headers.get('x-dashscope-request-id')
                    async for chunk in stream:
                        usage = actual_usage(getattr(chunk, 'usage', None))
                        if usage is not None:
                            record['usage'] = usage
                        for choice in chunk.choices or []:
                            if getattr(choice.delta, 'function_call', None):
                                raise ProviderFailure('UNSUPPORTED_TOOL')
                            if getattr(choice, 'index', 0) != 0:
                                raise ProviderFailure('TOOL_PROTOCOL_ERROR')
                            if choice.finish_reason:
                                if record['finish_reason'] and record['finish_reason'] != choice.finish_reason:
                                    raise ProviderFailure('TOOL_PROTOCOL_ERROR')
                                record['finish_reason'] = choice.finish_reason
                            fragments = getattr(choice.delta, 'tool_calls', None)
                            if fragments:
                                proposals.add(fragments)
                            content = getattr(choice.delta, 'content', None)
                            if content:
                                if not isinstance(content, str):
                                    raise ProviderFailure('TOOL_PROTOCOL_ERROR')
                                output_chars += len(content)
                                if output_chars > limits['max_output_chars']:
                                    raise ProviderFailure('TOOL_LIMIT_EXCEEDED')
                                content_parts.append(content)
                                for start in range(0, len(content), 4096):
                                    sequence += 1
                                    await self.manager.callback(task, 'delta', sequence=sequence, text=content[start:start + 4096])
                finish = record['finish_reason']
                if finish is None:
                    raise ProviderFailure('PROVIDER_ERROR')
                if not proposals.parts:
                    if finish in ('tool_calls', 'function_call'):
                        raise ProviderFailure('TOOL_PROTOCOL_ERROR')
                    return record
                if finish != 'tool_calls':
                    raise ProviderFailure('TOOL_PROTOCOL_ERROR')
                calls = proposals.complete(seen)
                if round_number > limits['max_rounds'] or tool_count + len(calls) > limits['max_calls']:
                    raise ProviderFailure('TOOL_LIMIT_EXCEEDED')
                seen.update(call['provider_call_id'] for call in calls)
                tool_count += len(calls)
                response = await self.manager.tools(task, round_number, calls, limits['max_result_bytes'] + 4096)
                messages = result_messages(response, calls, limits['max_result_bytes'])
                result_bytes += sum(len(message['content'].encode('utf-8')) for message in messages)
                if result_bytes > limits['max_result_bytes']:
                    raise ProviderFailure('TOOL_LIMIT_EXCEEDED')
                # Revalidate authority after waiting, before starting another provider request.
                ack = await self.manager.callback(task, 'heartbeat')
                if not isinstance(ack, dict) or ack.get('active') is not True:
                    raise LeaseLost()
                prepared['messages'].append(dict(role='assistant', content=''.join(content_parts) or None,
                    tool_calls=[dict(id=call['provider_call_id'], type='function', function=dict(
                        name=call['name'], arguments=encoded(call['arguments']))) for call in calls]))
                prepared['messages'].extend(messages)
        try:
            final = await asyncio.wait_for(run(), limits['timeout_seconds'])
        except asyncio.TimeoutError:
            error = ProviderFailure('TOOL_TIMEOUT')
            error.tool_metadata = dict(provider_requests=requests, tool_count=tool_count, usage=aggregate_usage(requests))
            raise error from None
        except ProviderFailure as error:
            error.tool_metadata = dict(provider_requests=requests, tool_count=tool_count, usage=aggregate_usage(requests))
            raise
        # Terminal callback has its own bounded transport. Do not cancel an uncertain
        # completion ACK at the loop deadline and then send a conflicting failure.
        await self.manager.callback(task, 'complete', last_sequence=sequence, finish_reason=final['finish_reason'],
            usage=aggregate_usage(requests), provider_request_id=final['provider_request_id'],
            provider_requests=requests, tool_count=tool_count)

    async def execute(self, task):
        if task.get('model') not in (self.config.model, getattr(self.config, 'vision_model', '') or self.config.model, getattr(self.config, 'omni_model', '') or self.config.model):
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
            await self.manager.callback(task, 'fail', error_code=exc.code, **getattr(exc, 'tool_metadata', {}))
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
