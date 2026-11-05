import argparse
import asyncio
import logging
import signal

from .config import Config
from .provider import CosyVoice
from .transport import Manager
from .worker import Worker


async def main(config):
    worker = Worker(config, Manager(config), CosyVoice(config))
    job = asyncio.create_task(worker.run())
    loop = asyncio.get_running_loop()
    for signum in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(signum, job.cancel)
    try:
        await job
    except asyncio.CancelledError:
        pass
    finally:
        await worker.close()


def cli():
    parser = argparse.ArgumentParser(description='Independent fixed-text answer speech worker')
    parser.add_argument('--config', required=True, help='Private mode-0600 worker JSON')
    args = parser.parse_args()
    logging.basicConfig(level=logging.WARNING)
    for name in ('httpx', 'httpcore', 'websockets'):
        library = logging.getLogger(name)
        library.handlers = [logging.NullHandler()]
        library.propagate = False
    try:
        config = Config.load(args.config)
    except Exception:
        parser.exit(2, 'invalid worker configuration\n')
    asyncio.run(main(config))


if __name__ == '__main__':
    cli()
