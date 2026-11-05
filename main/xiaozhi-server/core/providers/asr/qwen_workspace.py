"""Qwen ASR using explicit workspace endpoints and nonblocking HTTP calls."""
import asyncio
import base64
import os
from pathlib import Path
from urllib.parse import urlsplit

from openai import AsyncOpenAI
from config.logger import setup_logging
from core.providers.asr.base import ASRProviderBase
from core.providers.asr.dto.dto import InterfaceType

logger=setup_logging()
TAG=__name__


class ASRProvider(ASRProviderBase):
    def __init__(self, config, delete_audio_file):
        super().__init__()
        self.interface_type=InterfaceType.NON_STREAM
        key=config.get('api_key')
        if not key:raise ValueError('Qwen3-ASR-Flash 需要配置 api_key')
        self.model_name=config.get('model_name','qwen3-asr-flash')
        self.output_dir=config.get('output_dir','tmp/')
        self.delete_audio_file=delete_audio_file
        self.language=config.get('language')
        self.enable_itn=config.get('enable_itn',True)
        self.context=config.get('context','')
        endpoint=config.get('base_url','https://dashscope.aliyuncs.com/compatible-mode/v1')
        parsed=urlsplit(endpoint)
        if parsed.scheme!='https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError('ASR requires a trusted HTTPS endpoint')
        self.client=AsyncOpenAI(api_key=key,base_url=endpoint,timeout=30,max_retries=0)
        os.makedirs(self.output_dir,exist_ok=True)

    def prefers_temp_file(self):
        return True

    def requires_file(self):
        return True

    async def speech_to_text(self, opus_data, session_id, artifacts=None):
        if artifacts is None or not artifacts.temp_path:
            return '',None
        try:
            path=Path(artifacts.temp_path)
            if path.stat().st_size>8*1024*1024:raise ValueError('ASR audio exceeds bounded upload size')
            audio=await asyncio.to_thread(path.read_bytes)
            uri='data:audio/wav;base64,'+base64.b64encode(audio).decode()
            messages=[{'role':'user','content':[{'type':'input_audio','input_audio':{'data':uri}}]}]
            if self.context:
                messages.insert(0,{'role':'system','content':self.context})
            options={'enable_itn':self.enable_itn}
            if self.language:options['language']=self.language
            response=await self.client.chat.completions.create(model=self.model_name,messages=messages,extra_body={'asr_options':options})
            text=(response.choices[0].message.content or '').strip()
            if len(text)>16000:raise ValueError('ASR transcript exceeds bounded text size')
            return text,artifacts.file_path
        except Exception as error:
            logger.bind(tag=TAG).error('语音识别失败: {}',type(error).__name__)
            raise
