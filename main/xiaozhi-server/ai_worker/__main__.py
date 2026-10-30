import argparse
import asyncio
import logging
import signal
from .config import Config
from .provider import Bailian
from .transport import Manager
from .worker import Worker


async def main(config):
    worker = Worker(config, Manager(config), Bailian(config))
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
    parser = argparse.ArgumentParser(description='Independent Bailian text worker')
    parser.add_argument('--config', help='Private worker JSON file')
    args = parser.parse_args()
    # Third-party request logs may contain URLs/headers. Keep only sanitized worker codes.
    logging.basicConfig(level=logging.WARNING)
    for name in ('openai', 'httpx', 'httpcore'):
        library_logger = logging.getLogger(name)
        library_logger.handlers = [logging.NullHandler()]
        library_logger.propagate = False
    try:
        config = Config.load(args.config)
    except Exception:
        parser.exit(2, 'invalid worker configuration\n')
    asyncio.run(main(config))


if __name__ == '__main__':
    cli()
