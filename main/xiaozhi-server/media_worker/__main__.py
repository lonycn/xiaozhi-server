import argparse
import asyncio
import logging
import shutil
import signal
from .config import Config
from .processor import Processor
from .transcription import Transcriber
from .transport import Manager
from .worker import Worker


async def main(config):
    worker = Worker(config, Manager(config), Processor(config), Transcriber(config))
    running = asyncio.create_task(worker.run())
    loop = asyncio.get_running_loop()
    for signum in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(signum, running.cancel)
    try:
        await running
    except asyncio.CancelledError:
        pass
    finally:
        await worker.close()


def cli():
    parser = argparse.ArgumentParser(description='Independent private-media HTTP worker')
    parser.add_argument('--config', help='Private JSON configuration, chmod 600')
    parser.add_argument('--check', action='store_true', help='Check dependencies without contacting any service')
    args = parser.parse_args()
    logging.basicConfig(level=logging.WARNING)
    for name in ('httpx', 'httpcore', 'PIL', 'pypdf'):
        library = logging.getLogger(name)
        library.handlers = [logging.NullHandler()]
        library.propagate = False
    try:
        config = Config.load(args.config)
        if not shutil.which(config.ffmpeg) or not shutil.which(config.ffprobe):
            parser.exit(2, 'ffmpeg and ffprobe are required\n')
    except Exception:
        parser.exit(2, 'invalid worker configuration or missing dependency\n')
    if args.check:
        print('media worker dependencies and configuration available; no service contacted')
        return
    asyncio.run(main(config))


if __name__ == '__main__':
    cli()
