update model_deployment
set config_json = config_json || '{
      "videoRequestFormat":"json"
    }'::jsonb,
    updated_at = now()
where code = 'codex2api-sora-2-video';
