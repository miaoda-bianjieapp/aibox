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
    '40000000-0000-0000-0000-000000000003',
    'codex2api-relay',
    'Codex2API Relay',
    'openai-compatible',
    'RELAY',
    true,
    now(),
    now()
);

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
        '41000000-0000-0000-0000-000000000004',
        'codex2api-gpt-5-4-mini-text',
        'codex2api-relay',
        'GPT-5.4 Mini',
        'General-purpose text model through Codex2API relay',
        'TEXT_GENERATION',
        'gpt-5.4-mini',
        true,
        true,
        '{"source":"relay","discovery":"v1/models"}',
        now(),
        now()
    ),
    (
        '41000000-0000-0000-0000-000000000005',
        'codex2api-gpt-5-6-text',
        'codex2api-relay',
        'GPT-5.6',
        'High-quality text model through Codex2API relay',
        'TEXT_GENERATION',
        'gpt-5.6',
        true,
        true,
        '{"source":"relay","discovery":"v1/models"}',
        now(),
        now()
    ),
    (
        '41000000-0000-0000-0000-000000000006',
        'codex2api-gpt-image-2-image',
        'codex2api-relay',
        'GPT Image 2',
        'Image generation model through Codex2API relay',
        'IMAGE_GENERATION',
        'gpt-image-2',
        true,
        true,
        '{"source":"relay","discovery":"v1/models"}',
        now(),
        now()
    );

insert into model_route (
    id,
    model_alias,
    capability,
    deployment_code,
    priority,
    enabled,
    created_at
) values
    (
        '42000000-0000-0000-0000-000000000004',
        'text.default',
        'TEXT_GENERATION',
        'codex2api-gpt-5-4-mini-text',
        20,
        true,
        now()
    ),
    (
        '42000000-0000-0000-0000-000000000005',
        'text.default',
        'TEXT_GENERATION',
        'codex2api-gpt-5-6-text',
        30,
        true,
        now()
    ),
    (
        '42000000-0000-0000-0000-000000000006',
        'image.generation.default',
        'IMAGE_GENERATION',
        'codex2api-gpt-image-2-image',
        20,
        true,
        now()
    );

insert into feature_model_option (
    policy_id,
    deployment_code,
    display_name,
    description,
    sort_order,
    enabled
) values
    (
        '43000000-0000-0000-0000-000000000001',
        'codex2api-gpt-5-4-mini-text',
        'GPT-5.4 Mini',
        'Balanced text generation through a third-party relay',
        20,
        true
    ),
    (
        '43000000-0000-0000-0000-000000000001',
        'codex2api-gpt-5-6-text',
        'GPT-5.6',
        'High-quality text generation through a third-party relay',
        30,
        true
    );
