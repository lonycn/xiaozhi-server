import argparse
import logging
from aiohttp import web
from .config import Config
from .server import create_app


def main():
    parser = argparse.ArgumentParser(description='Opt-in loopback realtime AI worker')
    parser.add_argument('--config', required=True, help='Private mode-0600 JSON configuration')
    args = parser.parse_args()
    try:
        config = Config.load(args.config)
        if not config.enabled:
            raise ValueError('Realtime worker is disabled')
    except (ValueError, OSError, TypeError):
        parser.exit(2, 'Invalid or disabled private realtime configuration\n')
    # Never log tickets, prompts, PCM, images, API keys or raw provider exceptions.
    logging.basicConfig(level=logging.WARNING)
    web.run_app(create_app(config), host=config.host, port=config.port, access_log=None, print=None,
                shutdown_timeout=1)


if __name__ == '__main__': main()
