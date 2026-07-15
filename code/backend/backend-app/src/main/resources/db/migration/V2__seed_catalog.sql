insert into workspace (
    id, code, display_name, description, icon_key, groups_json,
    search_terms_json, sort_order, enabled, created_at
) values
    ('10000000-0000-0000-0000-000000000001', 'writing', '文本与写作', '同一编辑器内完成写、改、审', 'edit', '["create"]', '["文本","文章","文案","报告","润色","翻译"]', 10, true, now()),
    ('10000000-0000-0000-0000-000000000002', 'presentation', 'PPT 与演示', '生成、转换和优化可交付的幻灯片', 'presentation', '["create"]', '["PPT","演示","幻灯片","汇报","大纲"]', 20, true, now()),
    ('10000000-0000-0000-0000-000000000003', 'image', '图片设计', '生成与编辑共享同一设计画布', 'image', '["create","media"]', '["图片","设计","海报","配图","抠图","扩图"]', 30, true, now()),
    ('10000000-0000-0000-0000-000000000004', 'audio', '音频', '识别、生成和处理声音文件', 'audio', '["process","media"]', '["音频","语音","识别","转写","ASR","配音","降噪"]', 40, true, now()),
    ('10000000-0000-0000-0000-000000000005', 'video', '视频', '围绕画面与时间线完成生成和加工', 'video', '["create","media"]', '["视频","短视频","剪辑","字幕","数字人"]', 50, true, now()),
    ('10000000-0000-0000-0000-000000000006', 'document', '文档与数据', '以文件为对象，阅读、提取和转换', 'document', '["process"]', '["PDF","Word","Excel","文档","数据","提取","表格"]', 60, true, now());

insert into feature_definition (
    id, workspace_id, code, display_name, description, status,
    current_version, result_type, renderer_key, execution_mode,
    sort_order, created_at, updated_at
) values (
    '20000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    'writing.draft',
    '从零起草',
    '根据主题、受众和语气生成结构化初稿。',
    'INTERNAL',
    1,
    'rich_text',
    'rich_text_editor',
    'ASYNC',
    10,
    now(),
    now()
);

insert into feature_version (
    id, feature_id, version, input_schema_json, ui_schema_json,
    output_schema_json, config_json, created_at
) values (
    '30000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001',
    1,
    '{
      "$schema":"https://json-schema.org/draft/2020-12/schema",
      "type":"object",
      "required":["topic"],
      "properties":{
        "topic":{"type":"string","minLength":1,"maxLength":500,"title":"写作主题"},
        "audience":{"type":"string","maxLength":200,"title":"目标读者"},
        "tone":{"type":"string","enum":["professional","concise","friendly","creative"],"default":"professional","title":"表达语气"},
        "length":{"type":"string","enum":["short","medium","long"],"default":"medium","title":"篇幅"}
      },
      "additionalProperties":false
    }',
    '{
      "order":["topic","audience","tone","length"],
      "widgets":{"topic":"textarea","audience":"text","tone":"segmented","length":"segmented"}
    }',
    '{
      "type":"object",
      "required":["format","text"],
      "properties":{"format":{"const":"markdown"},"text":{"type":"string"}}
    }',
    '{"modelAlias":"text.default","maxOutputTokens":2000,"capabilities":["TEXT_GENERATION"]}',
    now()
);

