"""Loopback WebSocket edge with a single short-ticket authentication frame."""
import asyncio
import json
import re
from aiohttp import web, WSMsgType
from .control import Manager, AuthorityLost, Fault
from .provider import Provider
from .session import Session


class Server:
    def __init__(self, config, manager=None, provider=None):
        self.config = config
        self.manager = manager or Manager(config)
        self.provider = provider or Provider(config)
        self.sessions = set()
        self.connections = 0
        self.app = web.Application(client_max_size=300000)
        self.app.router.add_get('/realtime', self.websocket)
        self.app.on_shutdown.append(self.shutdown)
        self.app.on_cleanup.append(self.cleanup)

    async def websocket(self, request):
        origin = request.headers.get('Origin')
        if (not self.config.enabled or request.query_string or request.headers.get('Authorization')
                or (origin not in self.config.origins and not (origin is None and self.config.allow_missing_origin))):
            raise web.HTTPForbidden(text='Realtime connection denied')
        if self.connections >= self.config.max_connections:
            raise web.HTTPServiceUnavailable(text='Realtime capacity reached')
        self.connections += 1
        browser = web.WebSocketResponse(max_msg_size=300000, heartbeat=10, autoping=True,
                                        writer_limit=65536, compress=False)
        try:
            await browser.prepare(request)
            try:
                frame = await asyncio.wait_for(browser.receive(), self.config.auth_timeout_seconds)
                if frame.type != WSMsgType.TEXT or len(frame.data) > 1024: raise ValueError()
                event = json.loads(frame.data)
                if (not isinstance(event, dict) or set(event) != {'type','ticket'} or event.get('type') != 'connect'
                        or not isinstance(event.get('ticket'), str)
                        or not re.fullmatch(r'[A-Za-z0-9_-]{32,256}', event['ticket'])): raise ValueError()
            except (asyncio.TimeoutError, ValueError, TypeError):
                await browser.send_json({'type':'error','code':'AUTHENTICATION_REQUIRED'})
                return browser
            try:
                task = await self.manager.consume(event['ticket'])
                session = Session(self.config, self.manager, self.provider, browser, task)
            except (AuthorityLost, Fault):
                await browser.send_json({'type':'error','code':'AUTHENTICATION_REQUIRED'})
                return browser
            running = asyncio.current_task()
            self.sessions.add(running)
            try:
                await session.run()
            finally:
                self.sessions.discard(running)
            return browser
        finally:
            self.connections -= 1
            await browser.close()

    async def shutdown(self, app):
        for session in list(self.sessions): session.cancel()
        await asyncio.gather(*self.sessions, return_exceptions=True)

    async def cleanup(self, app):
        await self.manager.close()


def create_app(config, manager=None, provider=None):
    return Server(config, manager, provider).app
