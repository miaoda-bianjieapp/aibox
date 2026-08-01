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
    '6166a162-e7ef-4ea9-a546-ce79e44f6304',
    'cleanvoice-official',
    'Cleanvoice',
    'cleanvoice',
    'OFFICIAL',
    true,
    now(),
    now()
)
on conflict (code) do update
set display_name = excluded.display_name,
    protocol = excluded.protocol,
    provider_kind = excluded.provider_kind,
    enabled = excluded.enabled,
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
    '9793daf9-dd7b-4005-ac15-89d086db2548',
    'cleanvoice-studio-sound-audio',
    'cleanvoice-official',
    'Cleanvoice Studio Sound',
    'Stable speech enhancement with noise removal, dereverberation, loudness normalization and voice clarity processing',
    'AUDIO_ENHANCEMENT',
    'studio-sound',
    true,
    true,
    '{
      "source":"official",
      "discovery":"Cleanvoice API v2 OpenAPI and official REST documentation",
      "protocol":"cleanvoice-v2",
      "removeNoise":true,
      "studioSound":true,
      "normalize":true,
      "targetLufs":-16,
      "exportFormat":"auto",
      "nightly":false,
      "maxInputBytes":209715200,
      "initialPollDelayMillis":30000,
      "pollIntervalMillis":10000,
      "pollTimeoutSeconds":1200
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
    enabled = excluded.enabled,
    selectable = excluded.selectable,
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
    '9170458c-ac50-4118-8556-a761185b31f6',
    'audio.enhancement.default',
    'AUDIO_ENHANCEMENT',
    'cleanvoice-studio-sound-audio',
    10,
    true,
    now()
)
on conflict (model_alias, capability, deployment_code) do update
set priority = excluded.priority,
    enabled = excluded.enabled;
