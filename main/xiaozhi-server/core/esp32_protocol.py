"""Bounds and compatibility rules for the ESP32 WebSocket protocol."""

MAX_TEXT_BYTES = 65536
MAX_OPUS_BYTES = 1275
MAX_AUDIO_QUEUE = 100


class ProtocolError(ValueError):
    pass


def validate_hello(message, *, mqtt_gateway=False):
    if not isinstance(message, dict):
        raise ProtocolError("invalid hello")
    version = message.get("version", 1)
    # The MQTT gateway has its own 16-byte envelope and version negotiation.
    if not mqtt_gateway and (type(version) is not int or version != 1):
        raise ProtocolError("unsupported WebSocket protocol version")
    params = message.get("audio_params")
    if not isinstance(params, dict):
        raise ProtocolError("missing audio parameters")
    if params.get("format") != "opus":
        raise ProtocolError("unsupported audio format")
    if type(params.get("channels")) is not int or params["channels"] != 1:
        raise ProtocolError("unsupported channel count")
    # The existing decoder and VAD consume 16 kHz mono PCM.
    if type(params.get("sample_rate")) is not int or params["sample_rate"] != 16000:
        raise ProtocolError("unsupported input sample rate")
    if type(params.get("frame_duration")) is not int or params["frame_duration"] not in (20, 40, 60):
        raise ProtocolError("unsupported frame duration")
    features = message.get("features", {})
    if not isinstance(features, dict):
        raise ProtocolError("invalid features")


def valid_opus_frame(data):
    return isinstance(data, bytes) and 0 < len(data) <= MAX_OPUS_BYTES
