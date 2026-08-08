insert into model_provider (
    id, code, display_name, protocol, provider_kind, enabled, created_at, updated_at
) values (
    '4ad5d37c-d0d4-4aa4-95ce-071200000001',
    'agnes-ai-official',
    'Agnes AI Official',
    'openai-compatible',
    'OFFICIAL',
    true,
    now(),
    now()
)
on conflict (code) do update
set display_name = excluded.display_name,
    protocol = excluded.protocol,
    provider_kind = 'OFFICIAL',
    enabled = true,
    updated_at = now();

insert into model_deployment (
    id, code, provider_code, display_name, description, capability,
    provider_model, enabled, selectable, config_json, created_at, updated_at
) values
    (
        '4ad5d37c-d0d4-4aa4-95ce-071200000011',
        'agnes-2-5-flash-text',
        'agnes-ai-official',
        'Agnes 2.5 Flash',
        'Stable speed-oriented text model for storyboard generation.',
        'TEXT_GENERATION',
        'agnes-2.5-flash',
        true,
        true,
        '{
          "source":"official",
          "discovery":"GET /models",
          "lifecycle":"stable",
          "contextWindow":1048576,
          "maxOutputTokens":65536
        }'::jsonb,
        now(),
        now()
    ),
    (
        '4ad5d37c-d0d4-4aa4-95ce-071200000013',
        'agnes-2-5-pro-text',
        'agnes-ai-official',
        'Agnes 2.5 Pro',
        'Stable quality-oriented text model for complex storyboard generation.',
        'TEXT_GENERATION',
        'agnes-2.5-pro',
        true,
        true,
        '{
          "source":"official",
          "discovery":"GET /models",
          "lifecycle":"stable",
          "contextWindow":262144,
          "maxOutputTokens":65536
        }'::jsonb,
        now(),
        now()
    ),
    (
        '4ad5d37c-d0d4-4aa4-95ce-071200000015',
        'agnes-image-2-1-flash-image',
        'agnes-ai-official',
        'Agnes Image 2.1 Flash',
        'Stable image generation and reference-image editing model for video assets.',
        'IMAGE_GENERATION',
        'agnes-image-2.1-flash',
        true,
        true,
        '{
          "source":"official",
          "discovery":"GET /models",
          "lifecycle":"stable",
          "imageProtocol":"agnes-json",
          "imageResolution":"2K",
          "supportsReferenceImages":true,
          "maxReferenceImages":9,
          "supportsImageMask":false
        }'::jsonb,
        now(),
        now()
    ),
    (
        '4ad5d37c-d0d4-4aa4-95ce-071200000016',
        'agnes-video-v2-0-video',
        'agnes-ai-official',
        'Agnes Video V2.0',
        'Stable asynchronous text-to-video model.',
        'VIDEO_GENERATION',
        'agnes-video-v2.0',
        true,
        true,
        '{
          "source":"official",
          "discovery":"GET /models",
          "lifecycle":"stable",
          "videoProtocol":"agnes-videos",
          "videoPath":"/videos",
          "videoPollIntervalMs":5000,
          "videoPollTimeoutMs":900000,
          "maxReferenceImages":0,
          "parameterOptions":{
            "durationSeconds":["3","5","10","18"],
            "aspectRatio":["16:9","9:16"],
            "resolution":["480p","720p","1080p"]
          }
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
    selectable = true,
    config_json = excluded.config_json,
    updated_at = now();

insert into model_route (
    id, model_alias, capability, deployment_code, priority, enabled, created_at
) values
    (
        '4ad5d37c-d0d4-4aa4-95ce-081100000021',
        'text.video-storyboard',
        'TEXT_GENERATION',
        'agnes-2-5-flash-text',
        60,
        true,
        now()
    ),
    (
        '4ad5d37c-d0d4-4aa4-95ce-081100000022',
        'text.video-storyboard',
        'TEXT_GENERATION',
        'agnes-2-5-pro-text',
        70,
        true,
        now()
    ),
    (
        '4ad5d37c-d0d4-4aa4-95ce-081100000023',
        'image.video-asset',
        'IMAGE_GENERATION',
        'agnes-image-2-1-flash-image',
        30,
        true,
        now()
    ),
    (
        '4ad5d37c-d0d4-4aa4-95ce-081100000024',
        'video.default',
        'VIDEO_GENERATION',
        'agnes-video-v2-0-video',
        50,
        true,
        now()
    )
on conflict (model_alias, capability, deployment_code) do update
set priority = excluded.priority,
    enabled = true;

insert into feature_model_option (
    policy_id, deployment_code, display_name, description, sort_order, enabled
)
select
    policy.id,
    option.deployment_code,
    option.display_name,
    option.description,
    option.sort_order,
    true
from feature_model_policy policy
cross join (
    values
        (
            'TEXT_GENERATION',
            'agnes-2-5-flash-text',
            'Agnes 2.5 Flash',
            'Official speed-oriented model for storyboard generation.',
            60
        ),
        (
            'TEXT_GENERATION',
            'agnes-2-5-pro-text',
            'Agnes 2.5 Pro',
            'Official quality-oriented model for complex storyboards.',
            70
        ),
        (
            'IMAGE_GENERATION',
            'agnes-image-2-1-flash-image',
            'Agnes Image 2.1 Flash',
            'Official image model supporting reference-image video assets.',
            30
        ),
        (
            'VIDEO_GENERATION',
            'agnes-video-v2-0-video',
            'Agnes Video V2.0',
            'Official asynchronous text-to-video model.',
            50
        )
) as option(capability, deployment_code, display_name, description, sort_order)
where policy.feature_code = 'video.generate'
  and policy.capability = option.capability
on conflict (policy_id, deployment_code) do update
set display_name = excluded.display_name,
    description = excluded.description,
    sort_order = excluded.sort_order,
    enabled = true;

update feature_version version
set input_schema_json = jsonb_set(
        jsonb_set(
            version.input_schema_json,
            '{properties,durationSeconds,enum}',
            '[3,4,5,8,10,12,15,16,18,20]'::jsonb
        ),
        '{properties,resolution,enum}',
        '["480p","720p","1080p"]'::jsonb
    )
from feature_definition feature
where version.feature_id = feature.id
  and feature.code = 'video.generate'
  and version.version = feature.current_version;
