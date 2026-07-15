insert into model_provider (
    id, code, display_name, protocol, enabled, created_at, updated_at
) values (
    '40000000-0000-0000-0000-000000000002',
    'aliyun-maas',
    'Alibaba Cloud Model Studio',
    'openai-compatible',
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
) values (
    '41000000-0000-0000-0000-000000000003',
    'aliyun-qwen-image-2-0',
    'aliyun-maas',
    'Qwen Image 2.0',
    'General-purpose image generation model',
    'IMAGE_GENERATION',
    'qwen-image-2.0',
    true,
    true,
    '{}',
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
) values (
    '42000000-0000-0000-0000-000000000003',
    'image.generation.default',
    'IMAGE_GENERATION',
    'aliyun-qwen-image-2-0',
    10,
    true,
    now()
);
