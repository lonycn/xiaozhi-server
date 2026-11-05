"""Session-local capture fences across the HTTP pause / WebSocket queue boundary."""
import re
import secrets
from .control import Fault

PROTOCOL='material_capture_v1'
TOKEN=re.compile(r'[A-Za-z0-9_-]{32}')


class Capture:
    def __init__(self):
        self.token=secrets.token_urlsafe(24)
        self.retired=set()

    def metadata(self):return dict(capture_protocol=PROTOCOL,capture_token=self.token)

    def rotate(self):
        if len(self.retired)>=24:raise Fault('MATERIAL_LIMIT')
        self.retired.add(self.token)
        for _ in range(4):
            candidate=secrets.token_urlsafe(24)
            if candidate not in self.retired:
                self.token=candidate
                return self.metadata()
        raise Fault('WORKER_ERROR')

    def stale(self,value):
        if not isinstance(value,str) or not TOKEN.fullmatch(value):raise Fault('INPUT_LIMIT')
        if value==self.token:return False
        if value in self.retired:return True
        raise Fault('INPUT_LIMIT')
