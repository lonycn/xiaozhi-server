"""Typed stock-firmware MCP acknowledgements. Unknown data never means success."""
import json


def normalize(tool, arguments, raw):
    if isinstance(raw, str):
        if len(raw.encode('utf-8')) > 4096:
            return 'unknown', {'error_code': 'MCP_RESULT_TOO_LARGE'}
        try:
            raw = json.loads(raw)
        except (ValueError, TypeError):
            return 'unknown', {'error_code': 'MCP_RESULT_INVALID'}
    if tool == 'self.audio_speaker.set_volume':
        # Stock mcp_server.cc returns true after SetOutputVolume returns. This
        # confirms only the device's software setter, never physical loudness.
        if raw is True:
            return 'succeeded', {'volume': arguments['volume'], 'confirmation': 'software_setter_returned'}
        return 'unknown', {'error_code': 'MCP_VOLUME_ACK_UNRECOGNIZED'}
    if tool == 'self.get_device_status' and isinstance(raw, dict):
        speaker = raw.get('audio_speaker')
        volume = speaker.get('volume') if isinstance(speaker, dict) else None
        if type(volume) is int and 0 <= volume <= 100:
            # Publish only the allowlisted schema, not arbitrary device text.
            return 'succeeded', {'audio_speaker': {'volume': volume}, 'confirmation': 'device_software_status'}
    return 'unknown', {'error_code': 'MCP_STATUS_UNRECOGNIZED'}
