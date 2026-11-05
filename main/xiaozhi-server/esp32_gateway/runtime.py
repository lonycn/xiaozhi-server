"""Per-connection lifecycle. No device-supplied internal endpoint or identity."""
import asyncio
import json
import time
import uuid
from collections import deque
import aiohttp
from .client import GatewayClient, GatewayError
from .mcp_results import normalize
from .role_source import RoleSource
from .voice_controls import classify as classify_voice_control, normalized

ALLOWED_TOOLS = {"self.get_device_status", "self.audio_speaker.set_volume"}
TEXT_INPUT = "gateway.text_input"


def deadline_millis(value):
    """JDBC BIGINT may be rendered as decimal text by the Java mapper."""
    if isinstance(value, str):
        if not value.isascii() or not value.isdecimal() or not 1 <= len(value) <= 16:
            raise ValueError('invalid command deadline')
        value = int(value)
    if type(value) is not int or not 0 < value <= 9999999999999999:
        raise ValueError('invalid command deadline')
    return value


class GatewayRuntime:
    def __init__(self, conn, config):
        self.conn, self.config = conn, config
        self.mode = config.get('execution_mode', 'ai_worker')
        if self.mode not in ('legacy_record_only', 'ai_worker') or not config.get('gateway_id'):
            raise ValueError('invalid ESP32 gateway configuration')
        # Each explicitly configured device keeps its own verified role cache.
        role_sources = config.get('role_sources', {})
        if not isinstance(role_sources, dict):
            raise ValueError('invalid ESP32 role source configuration')
        role_config = role_sources.get(conn.headers.get('device-id'), config.get('role_source'))
        self.role_source = RoleSource(role_config) if role_config and self.mode == 'ai_worker' else None
        self.fence = self.session_id = self.client = self.http = None
        self.tasks = set()
        self.active = False
        self.current_turn = None
        self.current_control_version = None
        self.playback_authority = None
        self.playback_started = False
        self.pending_playback_subtitle = None
        self.played_turns = deque(maxlen=128)
        self.turn_task = None
        self.abort_task = None
        self.closed = False
        self.input_generation = 0

    def spawn(self, coro):
        task = asyncio.create_task(coro)
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)
        return task

    async def start(self):
        try:
            headers = self.conn.headers
            permitted=self.config.get('allowed_devices')
            if permitted is not None and headers.get('device-id') not in permitted:
                raise GatewayError(403)
            authorization = headers.get('authorization', '')
            if not authorization.startswith('Bearer ') or not headers.get('client-id') or not headers.get('device-id'):
                raise GatewayError(401)
            mcp = getattr(self.conn, 'mcp_client', None)
            if mcp:
                # Colour-display and AFE cold starts can exceed five seconds.
                # Keep the handshake bounded while allowing the device to finish.
                for _ in range(150):
                    if await mcp.is_ready():
                        break
                    await asyncio.sleep(.1)
            if mcp and not await mcp.is_ready():
                raise GatewayError(503)
            capabilities = [TEXT_INPUT] if self.mode == 'ai_worker' else []
            if mcp and await mcp.is_ready():
                capabilities.extend(name for name in ALLOWED_TOOLS if name in mcp.name_mapping.values())
            self.http = aiohttp.ClientSession()
            self.client = GatewayClient(self.config.get('base_url', ''), self.config.get('gateway_key', ''), self.http)
            self.fence = await self.client.register(headers['device-id'], headers['client-id'], authorization[7:], self.config['gateway_id'], capabilities)
            session = await self.client.session('connect', self.fence, executionMode=self.mode)
            self.session_id = session['id']
            self.active = True
            self.spawn(self._heartbeat())
            self.spawn(self._poll())
        except asyncio.CancelledError:
            raise
        except Exception:
            await self.fail()

    def _clear_playback(self):
        self.playback_authority = None
        self.playback_started = False
        self.pending_playback_subtitle = None
        self.current_turn = None
        self.conn.client_abort = True
        clear = getattr(self.conn, "clear_queues", None)
        if clear:
            clear()

    async def fail(self):
        self._clear_playback()
        self.active = False
        if not self.closed:
            self.closed = True
            await self.conn.websocket.close(code=1011, reason='ESP32 gateway unavailable')

    async def _heartbeat(self):
        try:
            while self.active:
                await asyncio.sleep(8)
                await self.client.fence('heartbeat', self.fence)
        except asyncio.CancelledError:
            raise
        except Exception:
            await self.fail()

    async def _poll(self):
        try:
            while self.active:
                commands = await self.client.fence('poll', self.fence)
                if not isinstance(commands, list) or len(commands) > 8:
                    raise GatewayError()
                for command in commands:
                    if not self.active:
                        return
                    await self._command(command)
                await asyncio.sleep(1)
        except asyncio.CancelledError:
            raise
        except Exception:
            await self.fail()

    async def _command(self, command):
        from core.providers.tools.device_mcp.mcp_handler import call_mcp_tool
        from core.utils.util import sanitize_tool_name
        command_id, tool = command['id'], command['tool']
        # Recheck the lease/fence immediately before issuing a physical action.
        await self.client.fence('heartbeat', self.fence)
        if not self.active:
            return
        try:
            args = json.loads(command['arguments']) if isinstance(command['arguments'], str) else command['arguments']
            deadline = deadline_millis(command.get('deadline'))
            if not isinstance(args, dict) or deadline <= int(time.time() * 1000):
                raise ValueError('invalid command')
            if tool == TEXT_INPUT:
                status, body = await self._text_input(args)
            else:
                if tool not in ALLOWED_TOOLS:
                    raise ValueError('invalid command')
                client = self.conn.mcp_client
                result = await call_mcp_tool(self.conn, client, sanitize_tool_name(tool), args,
                    timeout=max(1, min(10, int((deadline - time.time()*1000)/1000))))
                status, body = normalize(tool, args, result)
        except asyncio.CancelledError:
            raise
        except (asyncio.TimeoutError, TimeoutError):
            # The device may have executed the setter before losing its reply.
            status, body = 'unknown', {"error_code": "MCP_ACK_TIMEOUT"}
        except RuntimeError:
            # The legacy MCP helper does not expose a typed device rejection.
            # Do not turn an undifferentiated transport/runtime error into a
            # definitive statement that a physical action did not execute.
            status, body = 'unknown', {"error_code": "MCP_ACK_UNCONFIRMED"}
        except Exception:
            status, body = 'unknown', {"error_code": "MCP_RESULT_UNAVAILABLE"}
        if not self.active:
            return
        await self.client.post('result', {"fence": self.fence, "commandId": command_id,
            "status": status, "success": status == 'succeeded', "result": body})

    async def _text_input(self, args):
        text = args.get('text')
        if (set(args) != {'text', 'session_id'} or args.get('session_id') != self.session_id
                or self.mode != 'ai_worker' or not self.active or self.closed
                or not isinstance(text, str) or not text.strip() or len(text) > 2000
                or any(ord(c) < 32 and c not in '\n\r\t' for c in text)):
            return 'failed', {'error_code': 'INVALID_TEXT_INPUT'}
        # Reuse exactly the ASR handoff: STT display, limits, interruption, intents,
        # trusted turn creation, model execution and device TTS retain one owner.
        from core.handle.receiveAudioHandle import startToChat
        generation = self.input_generation
        await startToChat(self.conn, text)
        if self.input_generation <= generation:
            return 'failed', {'error_code': 'TEXT_INPUT_REJECTED'}
        # This receipt confirms input acceptance, not completion of the AI reply.
        return 'succeeded', {'accepted': True}

    def submit(self, text):
        self.input_generation += 1
        wake_words=getattr(self.conn,'config',{}).get('wakeup_words',[])
        if isinstance(wake_words,list) and normalized(text) and normalized(text) in {normalized(w) for w in wake_words}:
            self.abort()
            return
        action = classify_voice_control(text)
        if action is not None:
            self.abort()
            self.turn_task = self.spawn(self._voice_control(action, self.input_generation))
            return
        self._clear_playback()
        if self.turn_task and not self.turn_task.done():
            if self.mode == "legacy_record_only":
                # The native worker thread cannot be cancelled; do not overlap turns.
                self.spawn(self.fail())
                return
            self.turn_task.cancel()
        self.turn_task = self.spawn(self._turn(text))

    async def _voice_control(self, action, generation):
        try:
            if self.abort_task:
                await self.abort_task
            if generation != self.input_generation or not self.active:
                return
            clear_speaking = getattr(self.conn, 'clearSpeakStatus', None)
            if clear_speaking:
                clear_speaking()
            await self.conn.websocket.send(json.dumps({'type':'voice_control','action':action,'session_id':self.conn.session_id}))
            if action == 'exit' and generation == self.input_generation:
                await self.conn.close()
        except asyncio.CancelledError:
            raise
        except Exception:
            await self.fail()

    async def _turn(self, text):
        generation = self.input_generation
        try:
            if self.abort_task:
                await self.abort_task
            for _ in range(100):
                if self.active or self.closed:
                    break
                await asyncio.sleep(.1)
            if not self.active:
                raise GatewayError()
            role = None
            headers = self.conn.headers if self.role_source else {}
            if self.role_source and headers.get('device-id') == self.role_source.device_mac:
                role = await self.role_source.resolve(self.http, self.fence['deviceId'], headers['client-id'])
            if generation != self.input_generation or not self.active:
                return
            role_input = {'rolePrompt': role.prompt} if role and role.prompt else {}
            turn = await self.client.session('input', self.fence, sessionId=self.session_id,
                inputKey=uuid.uuid4().hex, text=text, **role_input)
            if generation != self.input_generation or not self.active:
                return
            action = turn.get('intent_action')
            if action in ('stop', 'exit'):
                await self._voice_control(action, generation)
                return
            turn_id = turn['id']
            self.bind_turn(turn)
            if self.mode == 'legacy_record_only':
                try:
                    await self._legacy_turn(text, turn_id)
                except asyncio.CancelledError:
                    raise
                except Exception:
                    # Only report failures we actually observed; do not invent tool events.
                    try:
                        await self.client.session('event', self.fence, sessionId=self.session_id,
                            turnId=turn_id, eventKey=uuid.uuid4().hex,
                            eventType='failure', data='legacy_execution_failed')
                    except Exception:
                        pass
                    raise
            else:
                await self._canonical_turn(turn_id, role.voice if role else None)
        except asyncio.CancelledError:
            raise
        except Exception:
            await self.fail()

    async def _legacy_turn(self, text, turn_id):
        # Native chat streams TTS before we can validate its completion against
        # the current Java fence. Its asynchronous tools cannot be tied to this
        # turn either. Fail the turn before invoking any legacy action or model.
        await self.client.session('event', self.fence, sessionId=self.session_id,
            turnId=turn_id, eventKey=uuid.uuid4().hex, eventType='failure',
            data='legacy_executor_unavailable_for_ai_only')

    def bind_turn(self, turn):
        import re
        identity, version = turn.get('id'), turn.get('control_version')
        if isinstance(version, str) and version.isascii() and version.isdecimal():
            version = int(version)
        if not isinstance(identity, str) or not re.fullmatch(r'[a-f0-9]{32}', identity) or type(version) is not int or not 0 <= version < 2**63:
            raise GatewayError()
        self.current_turn, self.current_control_version = identity, version

    def playback_tags(self, sentence_id):
        authority = self.playback_authority
        if not authority or len(authority) != 4 or authority[0] != sentence_id or authority[1] != self.current_turn or type(authority[3]) is not int:
            return None
        return {'turn_id': authority[1], 'control_version': authority[3]}

    async def _canonical_turn(self, turn_id, voice_profile=None):
        from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO
        for _ in range(120):
            reply = await self.client.session('reply', self.fence, sessionId=self.session_id, turnId=turn_id)
            reply_id = reply.get('replyId') or reply.get('runId')
            if (reply.get('status') == 'completed' and isinstance(reply.get('text'), str)
                    and reply['text'] and isinstance(reply_id, str) and reply_id):
                if not self.active or self.current_turn != turn_id or turn_id in self.played_turns:
                    return
                self.played_turns.append(turn_id)
                self.conn.client_abort = False
                self.conn.sentence_id = uuid.uuid4().hex
                sid = self.conn.sentence_id
                self.playback_authority = (sid, turn_id, reply_id, self.current_control_version)
                for kind, content_type, text in ((SentenceType.FIRST, ContentType.ACTION, None), (SentenceType.MIDDLE, ContentType.TEXT, reply['text']), (SentenceType.LAST, ContentType.ACTION, None)):
                    self.conn.tts.tts_text_queue.put(TTSMessageDTO(sentence_id=sid, sentence_type=kind,
                        content_type=content_type, content_detail=text,
                        voice_profile=voice_profile if kind == SentenceType.FIRST else None))
                return
            if reply.get('status') != 'pending':
                raise GatewayError()
            await asyncio.sleep(.5)
        raise GatewayError()

    async def authorize_playback(self, sentence_id):
        authority = self.playback_authority
        if (not self.active or not sentence_id or not authority
                or authority[0] != sentence_id or authority[1] != self.current_turn
                or getattr(self.conn, 'sentence_id', None) != sentence_id):
            return False
        try:
            reply = await self.client.session('reply', self.fence,
                sessionId=self.session_id, turnId=authority[1])
        except asyncio.CancelledError:
            raise
        except Exception:
            reply = None
        still_current = (self.active and self.playback_authority == authority
                and self.current_turn == authority[1]
                and getattr(self.conn, 'sentence_id', None) == sentence_id)
        if not still_current:
            return False
        if (not isinstance(reply, dict) or reply.get('status') != 'completed'
                or (reply.get('replyId') or reply.get('runId')) != authority[2]):
            self.spawn(self.fail())
            return False
        return True

    def abort(self):
        interrupted_turn, interrupted_version = self.current_turn, self.current_control_version
        self._clear_playback()
        if self.turn_task and not self.turn_task.done():
            self.turn_task.cancel()
        if self.active:
            if self.mode == 'ai_worker':
                if interrupted_turn is not None and type(interrupted_version) is int:
                    self.abort_task = self.spawn(self._abort(interrupted_turn,interrupted_version))
            else:
                self.abort_task = self.spawn(self._abort())
                self.spawn(self.fail())

    async def _abort(self, turn_id=None, control_version=None):
        try:
            if self.mode=='ai_worker':
                await self.client.session('abort-turn',self.fence,sessionId=self.session_id,
                    turnId=turn_id,controlVersion=control_version)
            else:
                await self.client.session('abort',self.fence,sessionId=self.session_id)
        except GatewayError as error:
            if self.mode=='ai_worker' and error.status==409 and error.code=='STALE_ABORT':
                return
            await self.fail()
        except Exception:
            await self.fail()

    async def close(self):
        self._clear_playback()
        self.closed = True
        self.active = False
        # User-exit can close the connection from one of this runtime's tasks.
        # Never cancel or gather the currently executing close task itself.
        tasks = [task for task in self.tasks if task is not asyncio.current_task()]
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        if self.client and self.fence:
            try:
                await self.client.fence('disconnect', self.fence)
            except Exception:
                pass
        if self.http:
            await self.http.close()
