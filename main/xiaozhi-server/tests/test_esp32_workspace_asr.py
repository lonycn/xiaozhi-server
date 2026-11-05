import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch
from core.providers.asr.base import ASRProviderBase
from core.providers.asr.dto.dto import InterfaceType
from core.providers.asr.qwen_workspace import ASRProvider
class WorkspaceAsrTests(unittest.IsolatedAsyncioTestCase):
 async def test_short_vad_segment_is_kept_for_esp32_controls_only(self):
  packet=bytes(1920)
  for gateway in (SimpleNamespace(mode="ai_worker"), None):
   with self.subTest(gateway=gateway):
    provider=SimpleNamespace(interface_type=InterfaceType.NON_STREAM,handle_voice_stop=AsyncMock())
    conn=SimpleNamespace(esp32_gateway=gateway,client_listen_mode="auto",client_have_voice=True,
                         client_voice_stop=True,asr_audio=[packet]*9,asr=provider,
                         session_id="test",reset_audio_states=MagicMock())
    await ASRProviderBase.receive_audio(provider,conn,packet,False)
    if gateway:
     provider.handle_voice_stop.assert_awaited_once_with(conn,[packet*10],control_only=True)
    else:
     provider.handle_voice_stop.assert_not_awaited()
    conn.reset_audio_states.assert_called_once()

 async def test_short_segment_dispatches_only_explicit_control(self):
  conn=SimpleNamespace(voiceprint_provider=None,session_id="test")
  provider=SimpleNamespace(speech_to_text_wrapper=AsyncMock(side_effect=[("嗯",None),("退下吧",None)]),
                           _build_enhanced_text=lambda text,_speaker:text,stop_ws_connection=MagicMock())
  with patch("core.providers.asr.base.startToChat",AsyncMock()) as chat, patch("core.providers.asr.base.enqueue_asr_report"):
   await ASRProviderBase.handle_voice_stop(provider,conn,[b"audio"],control_only=True)
   chat.assert_not_awaited()
   await ASRProviderBase.handle_voice_stop(provider,conn,[b"audio"],control_only=True)
   chat.assert_awaited_once_with(conn,"退下吧")

 async def test_explicit_workspace_and_nonblocking_request(self):
  with tempfile.TemporaryDirectory() as directory,patch('core.providers.asr.qwen_workspace.AsyncOpenAI') as factory:
   client=MagicMock();client.chat.completions.create=AsyncMock(return_value=SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content='  请读取设备状态  '))]));factory.return_value=client
   provider=ASRProvider({'api_key':'fixture','base_url':'https://workspace.example/compatible-mode/v1','model_name':'qwen3-asr-flash','output_dir':directory},True)
   audio=Path(directory)/'input.wav';audio.write_bytes(b'fixture-wave');artifacts=SimpleNamespace(temp_path=str(audio),file_path='evidence.wav')
   text,path=await provider.speech_to_text([], 'session', artifacts)
   self.assertEqual((text,path),('请读取设备状态','evidence.wav'));self.assertEqual(factory.call_args.kwargs['base_url'],'https://workspace.example/compatible-mode/v1');self.assertEqual(factory.call_args.kwargs['max_retries'],0)
   request=client.chat.completions.create.call_args.kwargs;self.assertEqual(request['model'],'qwen3-asr-flash');self.assertTrue(request['messages'][0]['content'][0]['input_audio']['data'].startswith('data:audio/wav;base64,'))
 async def test_credential_endpoint_and_upload_boundaries(self):
  with tempfile.TemporaryDirectory() as directory:
   for endpoint in ['http://example.com','https://user:secret@example.com','https://example.com/?token=x']:
    with self.assertRaises(ValueError):ASRProvider({'api_key':'fixture','base_url':endpoint,'output_dir':directory},True)
   with patch('core.providers.asr.qwen_workspace.AsyncOpenAI'):
    provider=ASRProvider({'api_key':'fixture','output_dir':directory},True)
    path=Path(directory)/'oversize';path.write_bytes(b'x'*(8*1024*1024+1))
    with self.assertRaises(ValueError):await provider.speech_to_text([], 'session', SimpleNamespace(temp_path=str(path),file_path=None))
