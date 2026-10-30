import asyncio
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

    async def generate(self, task):
        sequence = 0
        usage, finish_reason, request_id = None, None, None
        async with self.provider.stream(task) as stream:
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
        if task.get('model') != self.config.model:
            await self.manager.callback(task, 'fail', error_code='MODEL_MISMATCH')
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
