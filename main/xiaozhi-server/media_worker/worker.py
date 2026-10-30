import asyncio
import logging
import tempfile
from pathlib import Path
from .transport import ControlError, LeaseLost, MediaFailure

logger = logging.getLogger('media_worker')


class Worker:
    def __init__(self, config, manager, processor, transcriber):
        self.config, self.manager, self.processor, self.transcriber = config, manager, processor, transcriber

    async def heartbeat(self, task):
        while True:
            await asyncio.sleep(self.config.heartbeat_seconds)
            try:
                response = await self.manager.callback(task, 'heartbeat')
            except (ControlError, LeaseLost):
                raise LeaseLost() from None
            if not isinstance(response, dict) or response.get('active') is not True:
                raise LeaseLost()

    async def process(self, task):
        # All temp files are server-independent, random, private, and owned by this attempt.
        with tempfile.TemporaryDirectory(prefix='xiaozhi-media-', dir=self.config.temp_root or None) as name:
            directory = Path(name)
            source = directory / 'input.bin'
            async with asyncio.timeout(self.config.process_timeout_seconds):
                await self.manager.download(task, source)
            if task.get('job_type') == 'inspect_media':
                metadata, artifacts = await self.processor.inspect(task, source, directory)
            elif task.get('job_type') == 'audio_transcription':
                if task.get('mime_type') != 'audio/wav':
                    raise MediaFailure('TYPE_MISMATCH')
                metadata, artifacts = await self.transcriber.transcribe(source, directory)
            else:
                raise MediaFailure('PROCESSING_FAILED')
            for kind, mime, path in artifacts:
                await self.manager.upload(task, kind, mime, path)
            await self.manager.callback(task, 'complete', metadata=metadata)

    async def execute(self, task):
        if (not isinstance(task, dict) or type(task.get('attempt')) is not int or not 1 <= task['attempt'] <= 3
                or not isinstance(task.get('lease_token'), str) or not task['lease_token']
                or type(task.get('lease_seconds')) not in (int, float) or task['lease_seconds'] < 30):
            raise LeaseLost()
        processing = asyncio.create_task(self.process(task))
        heartbeat = asyncio.create_task(self.heartbeat(task))
        try:
            done, _ = await asyncio.wait((processing, heartbeat), return_when=asyncio.FIRST_COMPLETED)
            if heartbeat in done:
                await heartbeat
            await processing
        except MediaFailure as failure:
            await self.manager.callback(task, 'fail', error_code=failure.code)
        except TimeoutError:
            await self.manager.callback(task, 'fail', error_code='PROCESSING_TIMEOUT')
        except (LeaseLost, ControlError):
            raise
        except Exception:
            await self.manager.callback(task, 'fail', error_code='PROCESSING_FAILED')
        finally:
            for pending in (processing, heartbeat):
                pending.cancel()
            await asyncio.gather(processing, heartbeat, return_exceptions=True)

    async def run(self):
        # Sequential claim loop: exactly one in-flight task, never an unbounded queue.
        while True:
            try:
                task = await self.manager.claim()
                if task is not None:
                    await self.execute(task)
                    continue
            except (LeaseLost, ControlError):
                logger.warning('media worker authority or transport unavailable')
            except Exception:
                logger.error('media worker task failed')
            await asyncio.sleep(self.config.poll_seconds)

    async def close(self):
        await self.transcriber.close()
        await self.manager.close()
