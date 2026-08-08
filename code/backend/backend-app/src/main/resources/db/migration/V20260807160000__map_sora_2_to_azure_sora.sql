update model_deployment
set provider_model = 'azure-sora',
    config_json = config_json || '{
      "publicModel":"sora-2",
      "upstreamModel":"azure-sora"
    }'::jsonb,
    updated_at = now()
where code = 'codex2api-sora-2-video';

update feature_model_option
set description = 'Sora 2 public model routed to the upstream azure-sora video model.'
where deployment_code = 'codex2api-sora-2-video';
