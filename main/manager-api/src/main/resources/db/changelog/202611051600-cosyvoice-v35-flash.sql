-- Requested default voice for explicitly configured CosyVoice 3.5 Flash models.
-- Existing model credentials, selected voices and the v2 catalog are retained.
-- Validate provider access before changing an active model's config_json.
INSERT INTO ai_tts_voice
  (id, tts_model_id, name, tts_voice, languages, voice_demo, remark, sort, create_date)
SELECT MD5(CONCAT('cosyvoice-v3.5-flash:', model.id, ':longantai_v3')),
       model.id, '龙安台 · 嗲甜台湾女', 'longantai_v3', '普通话、英文',
       'https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20260128/jlfjdr/%E9%BE%99%E5%AE%89%E5%8F%B0.mp3',
       '社交陪伴；20~25岁；SSML：支持；Instruct：不支持；时间戳：支持；华北2（北京）',
       0, NOW()
FROM ai_model_config model
WHERE model.model_type = 'TTS'
  AND JSON_UNQUOTE(JSON_EXTRACT(model.config_json, '$.type')) = 'alibl_stream'
  AND JSON_UNQUOTE(JSON_EXTRACT(model.config_json, '$.model')) = 'cosyvoice-v3.5-flash'
  AND NOT EXISTS (
    SELECT 1 FROM ai_tts_voice existing
    WHERE existing.tts_model_id = model.id AND existing.tts_voice = 'longantai_v3'
  );
