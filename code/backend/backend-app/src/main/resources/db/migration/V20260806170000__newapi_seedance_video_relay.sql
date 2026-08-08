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
    '40000000-0000-0000-0000-000000000032',
    'newapi-seedance-relay',
    'New API Seedance Relay',
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
        '41000000-0000-0000-0000-000000000033',
        'newapi-seedance-2-0-video',
        'newapi-seedance-relay',
        'Seedance 2.0',
        'Seedance 2.0 video generation claimed by the New API relay; paid generation is not yet verified.',
        'VIDEO_GENERATION',
        'doubao-seedance-2-0-260128',
        true,
        false,
        '{
          "source":"relay",
          "discovery":"/v1/models",
          "relayPlatform":"New API",
          "claimedOwner":"doubaovideo",
          "upstreamVendor":"ByteDance Volcano Engine",
          "videoProtocol":"openai-videos",
          "videoPath":"/videos",
          "maxReferenceImages":1,
          "videoSizeMap":{
            "720p|16:9":"1280x720",
            "720p|9:16":"720x1280",
            "16:9":"1280x720",
            "9:16":"720x1280",
            "720p":"1280x720"
          },
          "parameterOptions":{
            "durationSeconds":["4","8","12","15"],
            "aspectRatio":["16:9","9:16"],
            "resolution":["720p"]
          },
          "videoPollIntervalMs":2000,
          "videoPollTimeoutMs":1200000
        }'::jsonb,
        now(),
        now()
    ),
    (
        '41000000-0000-0000-0000-000000000034',
        'newapi-seedance-2-0-fast-video',
        'newapi-seedance-relay',
        'Seedance 2.0 Fast',
        'Faster Seedance 2.0 video generation claimed by the New API relay; paid generation is not yet verified.',
        'VIDEO_GENERATION',
        'doubao-seedance-2-0-fast-260128',
        true,
        false,
        '{
          "source":"relay",
          "discovery":"/v1/models",
          "relayPlatform":"New API",
          "claimedOwner":"doubaovideo",
          "upstreamVendor":"ByteDance Volcano Engine",
          "videoProtocol":"openai-videos",
          "videoPath":"/videos",
          "maxReferenceImages":1,
          "videoSizeMap":{
            "720p|16:9":"1280x720",
            "720p|9:16":"720x1280",
            "16:9":"1280x720",
            "9:16":"720x1280",
            "720p":"1280x720"
          },
          "parameterOptions":{
            "durationSeconds":["4","8","12","15"],
            "aspectRatio":["16:9","9:16"],
            "resolution":["720p"]
          },
          "videoPollIntervalMs":2000,
          "videoPollTimeoutMs":1200000
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
        '42000000-0000-0000-0000-000000000033',
        'video.seedance',
        'VIDEO_GENERATION',
        'newapi-seedance-2-0-video',
        10,
        true,
        now()
    ),
    (
        '42000000-0000-0000-0000-000000000034',
        'video.seedance',
        'VIDEO_GENERATION',
        'newapi-seedance-2-0-fast-video',
        20,
        true,
        now()
    )
on conflict (model_alias, capability, deployment_code) do update
set priority = excluded.priority,
    enabled = true;
