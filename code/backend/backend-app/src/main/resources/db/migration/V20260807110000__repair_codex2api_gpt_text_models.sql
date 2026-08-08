update model_deployment
set config_json = config_json || '{"maxTokensParameter":"max_output_tokens"}'::jsonb,
    updated_at = now()
where code in (
    'codex2api-gpt-5-4-mini-text',
    'codex2api-gpt-5-4-mini-vision'
);

update model_deployment
set display_name = 'GPT-5.6 Sol',
    description = 'High-quality text model through Codex2API relay, using the currently advertised Sol variant',
    provider_model = 'gpt-5.6-sol',
    config_json = config_json || '{
      "source":"relay",
      "discovery":"v1/models",
      "modelVariant":"sol"
    }'::jsonb,
    updated_at = now()
where code = 'codex2api-gpt-5-6-text';

update feature_model_option
set display_name = 'GPT-5.6 Sol',
    description = '质量优先的文本生成模型，通过 Codex2API 中转服务调用当前可用的 Sol 版本。'
where deployment_code = 'codex2api-gpt-5-6-text';
