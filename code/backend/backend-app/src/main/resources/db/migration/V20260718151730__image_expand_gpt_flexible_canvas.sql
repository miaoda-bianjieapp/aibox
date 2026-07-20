update model_deployment
set config_json = config_json || '{
  "imageExpansionMinPixels":1,
  "imageExpansionScaleFromSourceDimensions":true
}'::jsonb,
    updated_at = now()
where code = 'codex2api-gpt-image-2-image';
