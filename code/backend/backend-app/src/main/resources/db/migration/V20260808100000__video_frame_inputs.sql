update feature_version version
set input_schema_json = jsonb_set(
        jsonb_set(
            version.input_schema_json,
            '{properties,firstFrameAssetId}',
            '{
              "type":"string",
              "format":"uuid",
              "description":"Optional first-frame image; it must be the first image in inputAssetIds."
            }'::jsonb,
            true
        ),
        '{properties,lastFrameAssetId}',
        '{
          "type":"string",
          "format":"uuid",
          "description":"Optional last-frame image; availability follows the selected video deployment."
        }'::jsonb,
        true
    ),
    config_json = version.config_json || '{
      "videoFrameInputs":{
        "order":["FIRST_FRAME","LAST_FRAME"],
        "firstFrameParameter":"firstFrameAssetId",
        "lastFrameParameter":"lastFrameAssetId"
      }
    }'::jsonb
from feature_definition feature
where version.feature_id = feature.id
  and feature.code = 'video.generate'
  and version.version = feature.current_version;
