update model_deployment
set description = case code
        when 'codex2api-gpt-5-4-mini-text'
            then '轻量均衡的文本生成模型，通过 Codex2API 中转服务调用。'
        when 'codex2api-gpt-5-6-text'
            then '高质量文本生成模型，通过 Codex2API 中转服务调用。'
    end,
    updated_at = now()
where code in (
    'codex2api-gpt-5-4-mini-text',
    'codex2api-gpt-5-6-text'
);

update feature_model_option
set description = case deployment_code
        when 'codex2api-gpt-5-4-mini-text'
            then '轻量均衡的文本生成模型，通过 Codex2API 中转服务调用。'
        when 'codex2api-gpt-5-6-text'
            then '高质量文本生成模型，通过 Codex2API 中转服务调用。'
    end
where deployment_code in (
    'codex2api-gpt-5-4-mini-text',
    'codex2api-gpt-5-6-text'
);
