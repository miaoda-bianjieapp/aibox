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
    '40000000-0000-0000-0000-000000000030',
    'codex2api-grok-relay',
    'Codex2API Grok Relay',
    'openai-compatible',
    'RELAY',
    true,
    now(),
    now()
)
on conflict (code) do update
set display_name = excluded.display_name,
    protocol = excluded.protocol,
    provider_kind = 'RELAY',
    enabled = true,
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
) values
    (
        '41000000-0000-0000-0000-000000000030',
        'codex2api-grok-4-5-text',
        'codex2api-grok-relay',
        'Grok 4.5',
        'Grok 4.5 text generation through the Codex2API relay.',
        'TEXT_GENERATION',
        'grok-4.5',
        true,
        false,
        '{
          "source":"relay",
          "discovery":"v1/models",
          "officialVendor":"xAI",
          "officialModel":"grok-4.5",
          "officialInputModalities":["text","image"],
          "officialOutputModalities":["text"]
        }'::jsonb,
        now(),
        now()
    ),
    (
        '41000000-0000-0000-0000-000000000031',
        'codex2api-grok-4-5-vision',
        'codex2api-grok-relay',
        'Grok 4.5 Vision',
        'Grok 4.5 image understanding through the Codex2API relay.',
        'VISION',
        'grok-4.5',
        true,
        false,
        '{
          "source":"relay",
          "discovery":"v1/models",
          "officialVendor":"xAI",
          "officialModel":"grok-4.5",
          "officialInputModalities":["text","image"],
          "officialOutputModalities":["text"]
        }'::jsonb,
        now(),
        now()
    ),
    (
        '41000000-0000-0000-0000-000000000032',
        'codex2api-grok-imagine-video',
        'codex2api-grok-relay',
        'Grok Imagine Video',
        'Grok Imagine text and reference-image video generation through the Codex2API relay.',
        'VIDEO_GENERATION',
        'grok-imagine-video',
        true,
        false,
        '{
          "source":"relay",
          "discovery":"v1/models",
          "officialVendor":"xAI",
          "officialModel":"grok-imagine-video",
          "videoProtocol":"xai-videos",
          "videoPath":"/videos/generations",
          "videoReferenceField":"reference_images",
          "maxReferenceImages":7,
          "parameterOptions":{
            "durationSeconds":["4","8","12"],
            "aspectRatio":["16:9","9:16"],
            "resolution":["720p"]
          },
          "videoPollIntervalMs":5000,
          "videoPollTimeoutMs":900000
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
    enabled = true,
    selectable = false,
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
) values
    (
        '42000000-0000-0000-0000-000000000030',
        'text.default',
        'TEXT_GENERATION',
        'codex2api-grok-4-5-text',
        80,
        true,
        now()
    ),
    (
        '42000000-0000-0000-0000-000000000031',
        'vision.default',
        'VISION',
        'codex2api-grok-4-5-vision',
        80,
        true,
        now()
    ),
    (
        '42000000-0000-0000-0000-000000000032',
        'video.default',
        'VIDEO_GENERATION',
        'codex2api-grok-imagine-video',
        20,
        true,
        now()
    )
on conflict (model_alias, capability, deployment_code) do update
set priority = excluded.priority,
    enabled = true;
