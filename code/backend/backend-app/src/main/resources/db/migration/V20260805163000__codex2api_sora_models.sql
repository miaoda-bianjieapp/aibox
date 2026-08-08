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
    '40000000-0000-0000-0000-000000000031',
    'codex2api-sora-relay',
    'Codex2API Sora Relay',
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
) values (
    '41000000-0000-0000-0000-000000000007',
    'codex2api-sora-2-video',
    'codex2api-sora-relay',
    'Sora 2',
    'Sora 2 video generation through the Codex2API relay.',
    'VIDEO_GENERATION',
    'sora-2',
    true,
    false,
    '{
      "source":"relay",
      "discovery":"v1/models",
      "officialVendor":"OpenAI",
      "officialModel":"sora-2",
      "videoProtocol":"openai-videos",
      "videoPath":"/videos",
      "maxReferenceImages":1,
      "videoSizeMap":{
        "720p|16:9":"1280x720",
        "720p|9:16":"720x1280",
        "1080p|16:9":"1792x1024",
        "1080p|9:16":"1024x1792",
        "16:9":"1280x720",
        "9:16":"720x1280",
        "landscape":"1280x720",
        "portrait":"720x1280",
        "720p":"1280x720",
        "1080p":"1792x1024"
      },
      "parameterOptions":{
        "durationSeconds":["4","8","12"],
        "aspectRatio":["16:9","9:16"],
        "resolution":["720p","1080p"]
      },
      "videoPollIntervalMs":1000,
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
) values (
    '42000000-0000-0000-0000-000000000007',
    'video.default',
    'VIDEO_GENERATION',
    'codex2api-sora-2-video',
    30,
    true,
    now()
)
on conflict (model_alias, capability, deployment_code) do update
set priority = excluded.priority,
    enabled = true;
