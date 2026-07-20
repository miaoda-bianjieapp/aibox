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
    '73aa021b-59d7-4e0a-8f92-31658e22d158',
    feature_id,
    2,
    jsonb_set(
        jsonb_set(
            input_schema_json,
            '{properties,instruction,title}',
            '"选区修改内容"'::jsonb
        ),
        '{properties,instruction,description}',
        '"涂抹区域决定允许修改的范围；这里只描述选区内需要变成什么。"'::jsonb
    ),
    jsonb_set(
        ui_schema_json,
        '{fieldOptions,maskImage,editorLabel}',
        '"在原图上涂抹编辑区域"'::jsonb
    ),
    output_schema_json,
    config_json,
    now()
from feature_version
where feature_id = '3ecffe9d-176d-462a-9a62-f14969905676'
  and version = 1;

update feature_definition
set current_version = 2,
    updated_at = now()
where code = 'image.local_edit';

update feature_model_policy
set allow_user_selection = true,
    updated_at = now()
where feature_code = 'image.local_edit'
  and capability = 'IMAGE_GENERATION';
