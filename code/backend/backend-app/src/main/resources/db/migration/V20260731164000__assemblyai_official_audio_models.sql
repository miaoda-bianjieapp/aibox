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
    '40000000-0000-0000-0000-000000000005',
    'assemblyai-official',
    'AssemblyAI',
    'assemblyai',
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
) values
    (
        '41000000-0000-0000-0000-000000000024',
        'assemblyai-universal-3-5-pro-audio',
        'assemblyai-official',
        'AssemblyAI Universal-3.5 Pro',
        'AssemblyAI official high-accuracy transcription model with automatic language detection and keyterm prompting',
        'AUDIO_TRANSCRIPTION',
        'universal-3-5-pro',
        true,
        true,
        '{
          "source":"official",
          "discovery":"AssemblyAI OpenAPI 1.3.4 and model selection documentation",
          "protocol":"assemblyai-prerecorded-v2",
          "speechModels":["universal-3-5-pro","universal-2"],
          "promptMode":"keyterms",
          "maxKeyterms":1000,
          "pollIntervalMillis":3000,
          "pollTimeoutSeconds":240
        }'::jsonb,
        now(),
        now()
    ),
    (
        '41000000-0000-0000-0000-000000000025',
        'assemblyai-universal-2-audio',
        'assemblyai-official',
        'AssemblyAI Universal-2',
        'AssemblyAI official cost-efficient transcription model with broad language coverage and keyterm prompting',
        'AUDIO_TRANSCRIPTION',
        'universal-2',
        true,
        true,
        '{
          "source":"official",
          "discovery":"AssemblyAI OpenAPI 1.3.4 and model selection documentation",
          "protocol":"assemblyai-prerecorded-v2",
          "promptMode":"keyterms",
          "maxKeyterms":200,
          "pollIntervalMillis":3000,
          "pollTimeoutSeconds":240
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
) values
    (
        '42000000-0000-0000-0000-000000000024',
        'audio.transcription.default',
        'AUDIO_TRANSCRIPTION',
        'assemblyai-universal-3-5-pro-audio',
        10,
        true,
        now()
    ),
    (
        '42000000-0000-0000-0000-000000000025',
        'audio.transcription.default',
        'AUDIO_TRANSCRIPTION',
        'assemblyai-universal-2-audio',
        20,
        true,
        now()
    )
on conflict (model_alias, capability, deployment_code) do update
set priority = excluded.priority,
    enabled = excluded.enabled;
