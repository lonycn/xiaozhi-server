import json
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
TAG = __name__


async def handleAbortMessage(conn: "ConnectionHandler", message=None):
    gateway = getattr(conn, "esp32_gateway", None)
    if gateway and isinstance(message, dict) and any(field in message for field in ("turn_id", "control_version")):
        version = message.get("control_version")
        if (type(version) is not int or message.get("turn_id") != gateway.current_turn
                or version != gateway.current_control_version
                or ("session_id" in message and message["session_id"] != conn.session_id)):
            return False
    conn.logger.bind(tag=TAG).info("Abort message received")
    gateway = getattr(conn, "esp32_gateway", None)
    canonical = gateway is not None and gateway.mode == "ai_worker"
    # Capture the interrupted sentence before abort clears its authority.
    tags = gateway.playback_tags(getattr(conn, "sentence_id", None)) if canonical else None
    if gateway:
        gateway.abort()
    # 设置成打断状态，会自动打断llm、tts任务
    conn.close_after_chat = False
    conn.client_abort = True
    conn.clear_queues()
    # 打断客户端说话状态
    if not canonical or tags is not None:
        message = {"type": "tts", "state": "stop", "session_id": conn.session_id}
        if tags is not None:
            message.update(tags)
        await conn.websocket.send(json.dumps(message))
    conn.clearSpeakStatus()
    conn.logger.bind(tag=TAG).info("Abort message received-end")
