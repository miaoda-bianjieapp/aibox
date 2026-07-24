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
    'ad24b8c2-aa5d-48a6-82a8-264868996bd5',
    feature.id,
    3,
    previous.input_schema_json,
    jsonb_set(
        previous.ui_schema_json,
        '{fieldOptions,referenceImages,maxFileSizeBytes}',
        '20971520'::jsonb,
        true
    ),
    previous.output_schema_json,
    jsonb_set(
        previous.config_json,
        '{maxReferenceImageBytes}',
        '20971520'::jsonb,
        true
    ),
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 2
where feature.code = 'image.generate'
on conflict (feature_id, version) do nothing;

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
    '77201c4c-d0d0-452c-80d8-dde5f315581f',
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
on conflict (feature_id, version) do nothing;

do $$
begin
    if not exists (
        select 1
        from feature_definition feature
        join feature_version version
          on version.feature_id = feature.id
         and version.version = 3
        where feature.code = 'image.generate'
    ) or not exists (
        select 1
        from feature_definition feature
        join feature_version version
          on version.feature_id = feature.id
         and version.version = 4
        where feature.code = 'image.generate'
    ) then
        raise exception 'image.generate feature versions 3 and 4 must exist';
    end if;
end
$$;

update feature_definition
set current_version = 4,
    updated_at = now()
where code = 'image.generate';

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
