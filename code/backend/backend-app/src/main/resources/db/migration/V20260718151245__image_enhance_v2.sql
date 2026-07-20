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
    'fbfa220a-3e66-45c3-9c38-9bcf7d8a23f3',
    feature.id,
    2,
    previous.input_schema_json,
    jsonb_set(
        jsonb_set(
            previous.ui_schema_json,
            '{fieldOptions,mode}',
            '{
              "showSelectedIcon": false,
              "labelMaxLines": 1,
              "compact": true
            }'::jsonb,
            true
        ),
        '{feeNotice}',
        to_jsonb(
            '老照片修复开启上色时会连续调用所选模型 2 次，其余处理方式调用 1 次；费用按实际模型计费，点击“开始修复”即表示确认本次调用。'::text
        ),
        true
    ),
    previous.output_schema_json,
    previous.config_json || '{
      "oldPhotoRestoreModelInvocationCount": 1,
      "oldPhotoColorizeModelInvocationCount": 2,
      "colorizationStrategy": "restoration_then_full_colorization"
    }'::jsonb,
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 1
where feature.code = 'image.enhance';

update feature_definition
set current_version = 2,
    updated_at = now()
where code = 'image.enhance';
