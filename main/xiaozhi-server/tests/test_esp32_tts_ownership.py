import asyncio
import json
import queue
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock
from core.providers.tts.alibl_stream import TTSProvider
from core.providers.tts.base import TTSProviderBase
from core.handle.abortHandle import handleAbortMessage

class OwnershipTests(unittest.IsolatedAsyncioTestCase):
 async def test_old_monitor_cannot_retag_binary_with_new_sentence(self):
  tts=object.__new__(TTSProvider);tts.conn=SimpleNamespace(sentence_id='new',stop_event=threading.Event());tts.ws=object();tts.opus_encoder=MagicMock();old=SimpleNamespace(recv=AsyncMock(side_effect=[b'old-pcm',asyncio.CancelledError()]))
  tts.current_sentence_id='new'
  with self.assertRaises(asyncio.CancelledError):await tts._start_monitor_tts_response('old',old)
  tts.opus_encoder.encode_pcm_to_opus_stream.assert_not_called()
 async def test_explicit_callback_keeps_old_identity(self):
  tts=object.__new__(TTSProvider);tts.tts_audio_queue=queue.Queue();tts.current_sentence_id='new'
  TTSProviderBase.handle_opus(tts,b'old-opus','old')
  self.assertEqual(tts.tts_audio_queue.get_nowait()[3],'old')
 async def test_abort_tags_are_captured_before_clear(self):
  tags={'turn_id':'a'*32,'control_version':7};gateway=SimpleNamespace(mode='ai_worker',playback_tags=MagicMock(return_value=tags))
  gateway.abort=MagicMock(side_effect=lambda:setattr(gateway.playback_tags,'return_value',None))
  conn=SimpleNamespace(logger=MagicMock(),esp32_gateway=gateway,sentence_id='old',session_id='socket',websocket=SimpleNamespace(send=AsyncMock()),clear_queues=MagicMock(),clearSpeakStatus=MagicMock())
  await handleAbortMessage(conn);body=json.loads(conn.websocket.send.call_args.args[0]);self.assertEqual(body['turn_id'],'a'*32);self.assertIs(type(body['control_version']),int)

 async def test_stale_tagged_abort_never_clears_new_turn(self):
  gateway=SimpleNamespace(mode='ai_worker',current_turn='b'*32,current_control_version=8,abort=MagicMock())
  conn=SimpleNamespace(esp32_gateway=gateway,session_id='ws',clear_queues=MagicMock())
  self.assertFalse(await handleAbortMessage(conn,{'turn_id':'a'*32,'control_version':7,'session_id':'ws'}));gateway.abort.assert_not_called();conn.clear_queues.assert_not_called()

 async def test_completed_transport_is_not_reused_for_a_new_sentence(self):
  tts=object.__new__(TTSProvider);tts.ws=object();tts._monitor_task=None;tts.activate_session=False;tts.opus_encoder=MagicMock();tts.conn=SimpleNamespace(sample_rate=16000)
  async def close():tts.ws=None
  tts.close=AsyncMock(side_effect=close)
  new_ws=SimpleNamespace(send=AsyncMock())
  async def connect():tts.ws=new_ws
  tts._ensure_connection=AsyncMock(side_effect=connect);tts._start_monitor_tts_response=AsyncMock();tts.last_active_time=None
  tts.model='fixture';tts.voice='fixture';tts.format='pcm';tts.volume=50;tts.rate=1.;tts.pitch=1.
  await tts.start_session('new-id', {'model':'cosyvoice-v2','voice':'longfeifei_v2','ttsRate':50})
  tts.close.assert_awaited_once();tts.opus_encoder.reset_state.assert_called_once();self.assertEqual(tts.current_sentence_id,'new-id')
  payload=json.loads(new_ws.send.call_args.args[0])['payload']
  self.assertEqual(payload['model'],'cosyvoice-v2')
  self.assertEqual(payload['parameters']['voice'],'longfeifei_v2')
  self.assertEqual(payload['parameters']['rate'],1.5)
  self.assertEqual((tts.voice,tts.rate),('fixture',1.0))
  await asyncio.sleep(0)
