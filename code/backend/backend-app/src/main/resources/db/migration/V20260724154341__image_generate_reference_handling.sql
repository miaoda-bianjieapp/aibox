insert into feature_version (
    id,
    feature_id,
    version,
    input_schema_json,
    ui_schema_json,
    output_schema_json,
    config_json,
    created_at
)
select
    '5f9e46d1-2203-4a88-9600-000000000004',
    feature.id,
    4,
    jsonb_set(
        jsonb_set(
            jsonb_set(
                previous.input_schema_json,
                '{properties,generatedReferenceMode}',
                '{
                  "type":"string",
                  "enum":["NONE","USE_BASE"],
                  "default":"NONE",
                  "title":"上一版成果",
                  "description":"继续修改时决定是否将上一版生成成果作为额外参考图。"
                }'::jsonb,
                true
            ),
            '{properties,referenceImages,title}',
            '"自行上传参考图"'::jsonb,
            true
        ),
        '{properties,referenceImages,description}',
        '"可选上传最多 3 张主体、构图或风格参考图；与上一版生成成果分开管理。"'::jsonb,
        true
    ),
    jsonb_set(
        jsonb_set(
            jsonb_set(
                jsonb_set(
                    previous.ui_schema_json,
                    '{order}',
                    '["prompt","referenceImages","generatedReferenceMode","aspectRatio"]'::jsonb,
                    true
                ),
                '{widgets,generatedReferenceMode}',
                '"hidden"'::jsonb,
                true
            ),
            '{fieldOptions,referenceImages,requiresReferenceImageSupport}',
            'true'::jsonb,
            true
        ),
        '{revisionArtifactReference}',
        '{
          "modeField":"generatedReferenceMode",
          "enabledValue":"USE_BASE",
          "disabledValue":"NONE",
          "defaultEnabled":true,
          "title":"上一版成果",
          "description":"默认作为额外参考图，可点击叉号仅在本次生成中移除。"
        }'::jsonb,
        true
    ),
    previous.output_schema_json,
    previous.config_json || '{
      "maxTotalReferenceImages":4,
      "defaultRevisionReferenceMode":"USE_BASE"
    }'::jsonb,
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 3
where feature.code = 'image.generate'
on conflict do nothing;

update feature_definition
set current_version = 4,
    updated_at = now()
where code = 'image.generate'
  and current_version = 3;

update model_deployment
set config_json = config_json || '{"maxReferenceImages":4}'::jsonb,
    updated_at = now()
where code = 'codex2api-gpt-image-2-image';

update model_deployment
set config_json = config_json || '{"maxReferenceImages":0}'::jsonb,
    updated_at = now()
where code = 'aliyun-qwen-image-2-0';

update feature_model_option
set description = '适合中文文字和通用图片生成，仅支持文生图，不接收参考图片。'
where policy_id = '43000000-0000-0000-0000-000000000100'
  and deployment_code = 'aliyun-qwen-image-2-0';
