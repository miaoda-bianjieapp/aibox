insert into model_provider (
    id,
    code,
    display_name,
    protocol,
    provider_kind,
    enabled,
    created_at,
    updated_at
) values (
    '3877c4f6-3fa2-4fc7-b8bc-548d9bb91762',
    'openai2api-tts-relay',
    'OpenAI2API Unified TTS',
    'openai-compatible',
    'RELAY',
    true,
    now(),
    now()
)
on conflict (code) do update
set display_name = excluded.display_name,
    protocol = excluded.protocol,
    provider_kind = excluded.provider_kind,
    enabled = excluded.enabled,
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
    '1ad4aec5-dedc-491c-ba1c-f43b7556a97b',
    'openai2api-gpt-sovits-v2-tts',
    'openai2api-tts-relay',
    'GPT-SoVITS v2 中文语音',
    '通过 OpenAI2API Unified TTS 服务生成中文语音，已验证温柔女声与 WAV 输出。',
    'TEXT_TO_SPEECH',
    'gpt-sovits-v2',
    true,
    true,
    '{
      "source":"openai2api-unified-tts",
      "discovery":"openai2api.com:8765 /openapi.json and /api/v1/models",
      "speechProtocol":"unified-tts",
      "speechFormat":"wav",
      "defaultLanguage":"zh",
      "defaultVoiceId":"voice_4cb4da6d4aaa4e48aab7_v4",
      "defaultVoiceName":"温柔女声",
      "defaultEndUserId":"yuanzuo",
      "delivery":"realtime",
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

insert into model_route (
    id,
    model_alias,
    capability,
    deployment_code,
    priority,
    enabled,
    created_at
) values (
    'bbe107d8-37ec-4a2c-8d97-9bda1c6581ac',
    'speech.default',
    'TEXT_TO_SPEECH',
    'openai2api-gpt-sovits-v2-tts',
    10,
    true,
    now()
)
on conflict (model_alias, capability, deployment_code) do update
set priority = excluded.priority,
    enabled = excluded.enabled;
