insert into model_deployment (
    id,
    code,
    provider_code,
    display_name,
    description,
    capability,
    provider_model,
    enabled,
    selectable,
    config_json,
    created_at,
    updated_at
) values
(
    'd23ae8eb-f61a-4463-a05c-d3fb08cbf4d7',
    'openai2api-index-tts2-tts',
    'openai2api-tts-relay',
    'IndexTTS2 中文语音',
    '通过 OpenAI2API Unified TTS 服务调用 IndexTTS2，输出中文 WAV 语音。',
    'TEXT_TO_SPEECH',
    'index-tts2',
    true,
    true,
    '{
      "source":"openai2api-unified-tts",
      "speechProtocol":"unified-tts",
      "speechFormat":"wav",
      "defaultLanguage":"zh",
      "defaultEndUserId":"yuanzuo",
      "delivery":"realtime",
      "voiceMap":{
        "science_female":"voice_069da16e0fe399b207c3_v2",
        "gentle_female":"voice_4cb4da6d4aaa4e48aab7_v4"
      },
      "maxInputCharacters":500
    }'::jsonb,
    now(),
    now()
)
on conflict (code) do update
set provider_code = excluded.provider_code,
    display_name = excluded.display_name,
    description = excluded.description,
    capability = excluded.capability,
    provider_model = excluded.provider_model,
    enabled = excluded.enabled,
    selectable = excluded.selectable,
    config_json = excluded.config_json,
    updated_at = now();

insert into model_deployment (
    id,
    code,
    provider_code,
    display_name,
    description,
    capability,
    provider_model,
    enabled,
    selectable,
    config_json,
    created_at,
    updated_at
) values (
    'e5b09597-4250-45f4-b2c0-78e35224b91d',
    'openai2api-omnivoice-tts',
    'openai2api-tts-relay',
    'OmniVoice 多语言语音',
    '通过 OpenAI2API Unified TTS 服务调用 OmniVoice，输出 WAV 语音；首版按中文输入使用。',
    'TEXT_TO_SPEECH',
    'omnivoice',
    true,
    true,
    '{
      "source":"openai2api-unified-tts",
      "speechProtocol":"unified-tts",
      "speechFormat":"wav",
      "defaultLanguage":"zh",
      "defaultEndUserId":"yuanzuo",
      "delivery":"realtime",
      "voiceMap":{
        "science_female":"voice_069da16e0fe399b207c3_v2",
        "gentle_female":"voice_4cb4da6d4aaa4e48aab7_v4"
      },
      "maxInputCharacters":500
    }'::jsonb,
    now(),
    now()
)
on conflict (code) do update
set provider_code = excluded.provider_code,
    display_name = excluded.display_name,
    description = excluded.description,
    capability = excluded.capability,
    provider_model = excluded.provider_model,
    enabled = excluded.enabled,
    selectable = excluded.selectable,
    config_json = excluded.config_json,
    updated_at = now();

update feature_model_policy
set allow_user_selection = true,
    updated_at = now()
where feature_code = 'audio.text_to_speech'
  and capability = 'TEXT_TO_SPEECH';

insert into feature_model_option (
    policy_id,
    deployment_code,
    display_name,
    description,
    sort_order,
    enabled
) values
(
    'fdea7533-b994-4dca-b025-687dae27293c',
    'openai2api-index-tts2-tts',
    'IndexTTS2 中文语音',
    '中文语音模型，输出 WAV。',
    20,
    true
),
(
    'fdea7533-b994-4dca-b025-687dae27293c',
    'openai2api-omnivoice-tts',
    'OmniVoice 多语言语音',
    '多语言语音模型，首版按中文输入使用，输出 WAV。',
    30,
    true
)
on conflict (policy_id, deployment_code) do update
set display_name = excluded.display_name,
    description = excluded.description,
    sort_order = excluded.sort_order,
    enabled = excluded.enabled;