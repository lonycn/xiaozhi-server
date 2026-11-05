"""One bounded job; synthesis and manager cancellation remain independent."""
import asyncio
import hashlib
import logging
import re

from .provider import MAX_AUDIO_BYTES, MAX_TEXT_CHARS, Observation, SpeechFailure, valid_text
from .transport import ControlError, LeaseLost

logger = logging.getLogger('answer_speech_worker')
ERRORS = {'SPEECH_PROVIDER_NOT_CONFIGURED', 'SPEECH_PROVIDER_AUTH', 'SPEECH_PROVIDER_ERROR',
          'SPEECH_PROVIDER_TIMEOUT', 'SPEECH_AUDIO_INVALID', 'SPEECH_LIMIT_EXCEEDED', 'SPEECH_CANCELED'}


def public_error(code):
    if code in ERRORS:
        return code
    if code in ('SPEECH_TEXT_LIMIT', 'SPEECH_AUDIO_LIMIT'):
        return 'SPEECH_LIMIT_EXCEEDED'
    return 'SPEECH_PROVIDER_ERROR'


def validate(task, config):
    if not isinstance(task, dict):
        raise SpeechFailure('SPEECH_PROVIDER_ERROR')
    for key in ('job_id', 'conversation_id', 'message_id'):
        if not isinstance(task.get(key), str) or not re.fullmatch(r'[a-f0-9]{32}', task[key]):
            raise SpeechFailure('SPEECH_PROVIDER_ERROR')
    for key in ('message_version', 'text_sha256'):
        if not isinstance(task.get(key), str) or not re.fullmatch(r'[a-f0-9]{64}', task[key]):
            raise SpeechFailure('SPEECH_PROVIDER_ERROR')
    if (type(task.get('attempt')) is not int or not 1 <= task['attempt'] <= 100
            or not isinstance(task.get('lease_token'), str) or not 16 <= len(task['lease_token']) <= 200
            or not re.fullmatch(r'[A-Za-z0-9_-]{16,200}', task['lease_token'])
            or type(task.get('lease_seconds')) is not int or not 15 <= task['lease_seconds'] <= 120):
        raise SpeechFailure('SPEECH_PROVIDER_ERROR')
    text = valid_text(task.get('text'))
    if hashlib.sha256(text.encode('utf-8')).hexdigest() != task['text_sha256']:
        raise SpeechFailure('SPEECH_PROVIDER_ERROR')
    if task.get('model') != config.model or task.get('voice') != config.voice:
        raise SpeechFailure('SPEECH_PROVIDER_NOT_CONFIGURED')
    audio = task.get('audio')
    if (not isinstance(audio, dict) or audio != {'encoding': 's16le', 'sample_rate': 24000, 'channels': 1, 'container': 'wav'}
            or type(audio.get('sample_rate')) is not int or type(audio.get('channels')) is not int):
        raise SpeechFailure('SPEECH_AUDIO_INVALID')
    policy = task.get('policy')
    caps = {'max_text_chars': MAX_TEXT_CHARS, 'max_audio_bytes': MAX_AUDIO_BYTES, 'max_duration_ms': 120000}
    if (not isinstance(policy, dict) or set(policy) != set(caps)
            or any(type(policy[name]) is not int or not 0 < policy[name] <= cap for name, cap in caps.items())
            or policy['max_audio_bytes'] <= 44 or policy['max_duration_ms'] < 1000
            or len(text.encode('utf-16-le')) // 2 > policy['max_text_chars']):
        raise SpeechFailure('SPEECH_LIMIT_EXCEEDED')
    if config.heartbeat_seconds * 2 >= task['lease_seconds']:
        raise SpeechFailure('SPEECH_PROVIDER_ERROR')
    return policy


class Worker:
    def __init__(self, config, manager, provider):
        self.config, self.manager, self.provider = config, manager, provider

    async def authorize(self, task):
        try:
            receipt = await self.manager.callback(task, 'heartbeat')
        except (ControlError, LeaseLost):
            raise LeaseLost() from None
        if not isinstance(receipt, dict) or receipt.get('active') is not True:
            raise LeaseLost()

    async def heartbeat(self, task):
        while True:
            await asyncio.sleep(self.config.heartbeat_seconds)
            await self.authorize(task)

    async def process(self, task, observation, limits):
        # Authorization before cost, before uploading, and before publishing. Java
        # repeats every version/source/lease check atomically at each operation.
        await self.authorize(task)
        audio = await self.provider.synthesize(task['text'], observation,
            max_bytes=limits['max_audio_bytes'], max_seconds=limits['max_duration_ms'] // 1000)
        if len(audio.data) > limits['max_audio_bytes'] or audio.duration_ms > limits['max_duration_ms']:
            raise SpeechFailure('SPEECH_LIMIT_EXCEEDED')
        await self.authorize(task)
        receipt = await self.manager.audio(task, audio)
        if (not isinstance(receipt, dict) or receipt.get('audio_sha256') != audio.sha256
                or receipt.get('audio_bytes') not in (len(audio.data), str(len(audio.data)))
                or receipt.get('duration_ms') not in (audio.duration_ms, str(audio.duration_ms))):
            raise LeaseLost()
        await self.authorize(task)
        await self.manager.callback(task, 'complete', audio_sha256=audio.sha256,
            usage=observation.usage, provider_request_id=observation.provider_request_id)

    async def execute(self, task):
        observation, jobs, dispatched = Observation(), [], False
        try:
            limits = validate(task, self.config)
            monitor = asyncio.create_task(self.heartbeat(task))
            work = asyncio.create_task(self.process(task, observation, limits))
            jobs = [monitor, work]
            dispatched = True
            done, _ = await asyncio.wait(jobs, return_when=asyncio.FIRST_COMPLETED)
            # If authorization and work finish together, loss takes precedence.
            if monitor in done:
                await monitor
                raise LeaseLost()
            await work
        except asyncio.CancelledError:
            raise
        except (LeaseLost, ControlError):
            logger.warning('SPEECH_AUTHORITY_LOST')
        except SpeechFailure as failure:
            try:
                await self.manager.callback(task, 'fail', error_code=public_error(failure.code),
                    usage=observation.usage, provider_request_id=observation.provider_request_id)
            except Exception:
                pass
        except Exception:
            # Never log exception objects, task text, URL headers, credentials or audio.
            logger.warning('SPEECH_WORKER_ERROR')
            try:
                await self.manager.callback(task, 'fail', error_code='SPEECH_PROVIDER_ERROR',
                    usage=observation.usage, provider_request_id=observation.provider_request_id)
            except Exception:
                pass
        finally:
            for job in jobs:
                if not job.done():
                    job.cancel()
            if jobs:
                await asyncio.gather(*jobs, return_exceptions=True)
            if dispatched:
                try:
                    await self.manager.callback(task, 'usage', usage=observation.usage,
                        provider_request_id=observation.provider_request_id)
                except (ControlError, LeaseLost):
                    logger.warning('SPEECH_USAGE_AUDIT_UNAVAILABLE')

    async def run(self):
        if not self.config.enabled:
            return
        while True:
            try:
                task = await self.manager.claim()
                if task is not None:
                    await self.execute(task)
                    continue
            except (ControlError, LeaseLost):
                logger.warning('SPEECH_MANAGER_UNAVAILABLE')
                # A claim might have succeeded while its ACK was lost.
                await asyncio.sleep(45)
            await asyncio.sleep(self.config.poll_seconds)

    async def close(self):
        await self.manager.close()
