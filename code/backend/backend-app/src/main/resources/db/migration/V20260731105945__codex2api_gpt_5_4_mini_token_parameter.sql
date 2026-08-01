update model_deployment
set config_json = config_json || '{"maxTokensParameter":"max_output_tokens"}'::jsonb,
    updated_at = now()
where code in (
    'codex2api-gpt-5-4-mini-text',
    'codex2api-gpt-5-4-mini-vision'
);
