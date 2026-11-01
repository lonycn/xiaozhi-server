from contextlib import asynccontextmanager
import httpx
from openai import AsyncOpenAI, APIConnectionError, APIStatusError, APITimeoutError


class ProviderFailure(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def error_code(error):
    if isinstance(error, (APITimeoutError, httpx.TimeoutException, TimeoutError)):
        return 'PROVIDER_TIMEOUT'
    if isinstance(error, APIStatusError):
        if error.status_code in (401, 403):
            return 'PROVIDER_AUTH'
        if error.status_code == 429:
            return 'PROVIDER_RATE_LIMIT'
        if error.status_code >= 500:
            return 'PROVIDER_UNAVAILABLE'
        return 'PROVIDER_ERROR'
    if isinstance(error, (APIConnectionError, httpx.TransportError)):
        return 'PROVIDER_UNAVAILABLE'
    return 'WORKER_ERROR'


class Bailian:
    def __init__(self, config, client=None, vision_client=None, omni_client=None):
        self.config = config
        self.client = client or AsyncOpenAI(
            api_key=config.api_key, base_url=config.base_url, max_retries=0,
            timeout=httpx.Timeout(config.provider_timeout_seconds, connect=5),
            http_client=httpx.AsyncClient(trust_env=False),
            default_headers=({'X-DashScope-WorkSpace': config.workspace_id}
                             if config.workspace_id else {}))

        self.vision_client = vision_client
        if config.vision_model and config.vision_model != config.model and self.vision_client is None:
            self.vision_client = AsyncOpenAI(
                api_key=config.vision_api_key or config.api_key,
                base_url=config.vision_base_url or config.base_url, max_retries=0,
                timeout=httpx.Timeout(config.provider_timeout_seconds, connect=5),
                http_client=httpx.AsyncClient(trust_env=False),
                default_headers=({'X-DashScope-WorkSpace': config.vision_workspace_id}
                                 if config.vision_workspace_id else {}))

        self.omni_client = omni_client
        if config.omni_model and self.omni_client is None:
            self.omni_client = AsyncOpenAI(
                api_key=config.omni_api_key, base_url=config.omni_base_url, max_retries=0,
                timeout=httpx.Timeout(config.provider_timeout_seconds, connect=5),
                http_client=httpx.AsyncClient(trust_env=False),
                default_headers=({'X-DashScope-WorkSpace': config.omni_workspace_id}
                                 if config.omni_workspace_id else {}))

    @asynccontextmanager
    async def stream(self, task):
        stream = None
        try:
            tool_options = {}
            if task.get('tools'):
                from .tools import policy
                policy(task)  # fail closed even when this adapter is called directly
                tool_options = {'tools': task['tools'], 'tool_choice': 'auto'}
            if self.config.omni_model and task.get('model') == self.config.omni_model and self.omni_client is not None:
                selected = self.omni_client
            elif task.get('model') == self.config.model:
                selected = self.client
            elif task.get('model') == self.config.vision_model and self.vision_client is not None:
                selected = self.vision_client
            else:
                raise ProviderFailure('MODEL_CAPABILITY_UNAVAILABLE')
            stream = await selected.chat.completions.create(
                model=task['model'], messages=task['messages'], stream=True,
                stream_options={'include_usage': True}, max_tokens=self.config.max_tokens,
                **tool_options,
                **({'modalities': ['text'], 'reasoning_effort': 'none'}
                   if self.config.omni_model and task['model'] == self.config.omni_model
                   else {'extra_body': {'enable_thinking': False}}))
            yield stream
        except Exception as exc:
            # Protocol errors raised inside the context must keep their identity.
            from .transport import LeaseLost, ControlError
            if isinstance(exc, (LeaseLost, ControlError, ProviderFailure)):
                raise
            raise ProviderFailure(error_code(exc)) from None
        finally:
            if stream is not None:
                await stream.close()

    async def close(self):
        await self.client.close()
        if self.vision_client is not None and self.vision_client is not self.client:
            await self.vision_client.close()
        if self.omni_client is not None and self.omni_client not in (self.client, self.vision_client):
            await self.omni_client.close()
