-- Add a separate model-list entry without replacing an active TTS configuration.
-- Credentials are provisioned through model settings, never embedded in this seed.
INSERT INTO ai_model_config
  (id, model_type, model_code, model_name, is_default, is_enabled,
   config_json, doc_link, remark, sort, create_date)
SELECT 'TTS_BailianCosyVoice35Flash', 'TTS', 'TTS_BailianCosyVoice',
       '百炼 CosyVoice 3.5 Flash', 0, 1,
       JSON_OBJECT('type', 'alibl_stream', 'api_key', '',
                   'ws_url', 'wss://dashscope.aliyuncs.com/api-ws/v1/inference/',
                   'model', 'cosyvoice-v3.5-flash', 'voice', 'longantai_v3',
                   'format', 'pcm', 'sample_rate', 24000, 'output_dir', 'tmp/',
                   'volume', 50, 'rate', 1, 'pitch', 1),
       'https://help.aliyun.com/zh/model-studio/cosyvoice-v3-5-flash',
       '默认音色：龙安台（longantai_v3），嗲甜台湾女，20~25岁，普通话、英文。支持 SSML、时间戳，不支持 Instruct。请使用已开通模型的北京业务空间配置；切换为默认模型前先验证实际合成。',
       0, NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM ai_model_config existing
    WHERE existing.id = 'TTS_BailianCosyVoice35Flash'
);
