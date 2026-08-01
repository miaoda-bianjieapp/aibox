update model_deployment
set config_json = coalesce(config_json, '{}'::jsonb)
        || '{"speechModels":["universal-3-5-pro","universal-2"]}'::jsonb,
    updated_at = now()
where code = 'assemblyai-universal-3-5-pro-audio'
  and coalesce(config_json -> 'speechModels', 'null'::jsonb)
        is distinct from '["universal-3-5-pro","universal-2"]'::jsonb;
