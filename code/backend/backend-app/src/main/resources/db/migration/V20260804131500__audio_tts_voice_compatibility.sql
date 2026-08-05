update model_deployment
set config_json = jsonb_set(
        jsonb_set(
            coalesce(config_json, '{}'::jsonb),
            '{voiceMap}',
            '{"gentle_female":"voice_4cb4da6d4aaa4e48aab7_v4"}'::jsonb,
            true
        ),
        '{parameterOptions}',
        '{"voice":["gentle_female"]}'::jsonb,
        true
    ),
    updated_at = now()
where code = 'openai2api-gpt-sovits-v2-tts';

update model_deployment
set config_json = jsonb_set(
        jsonb_set(
            coalesce(config_json, '{}'::jsonb),
            '{voiceMap}',
            '{
              "science_female":"voice_069da16e0fe399b207c3_v2",
              "gentle_female":"voice_4cb4da6d4aaa4e48aab7_v4"
            }'::jsonb,
            true
        ),
        '{parameterOptions}',
        '{"voice":["science_female","gentle_female"]}'::jsonb,
        true
    ),
    updated_at = now()
where code in (
    'openai2api-index-tts2-tts',
    'openai2api-omnivoice-tts'
);

update feature_model_option
set description = case deployment_code
        when 'openai2api-gpt-sovits-v2-tts'
            then '中文参考音色模型，输出 WAV；当前仅开放温柔女声。'
        when 'openai2api-index-tts2-tts'
            then '中文零样本语音模型，支持科普视频女声和温柔女声，输出 WAV。'
        when 'openai2api-omnivoice-tts'
            then '多语言语音模型，首版按中文输入使用，支持两种业务声音，输出 WAV。'
        else description
    end
where policy_id = 'fdea7533-b994-4dca-b025-687dae27293c'
  and deployment_code in (
      'openai2api-gpt-sovits-v2-tts',
      'openai2api-index-tts2-tts',
      'openai2api-omnivoice-tts'
  );