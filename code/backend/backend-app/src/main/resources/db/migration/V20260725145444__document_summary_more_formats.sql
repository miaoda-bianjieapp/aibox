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
    '5ae3d62d-9ed9-4db5-ae93-b63c256a81f1',
    feature.id,
    2,
    jsonb_set(
        previous.input_schema_json,
        '{properties,document,description}',
        to_jsonb(
            '每次处理 1 个 PDF、Word、Excel、PowerPoint、Markdown、TXT、JSON 或 CSV 文档。'::text
        ),
        true
    ),
    jsonb_set(
        jsonb_set(
            jsonb_set(
                previous.ui_schema_json,
                '{fieldOptions,document,acceptedMimeTypes}',
                '[
                  "application/pdf",
                  "application/msword",
                  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                  "application/vnd.ms-excel",
                  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                  "application/vnd.ms-powerpoint",
                  "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                  "text/csv",
                  "text/plain",
                  "text/markdown",
                  "text/x-markdown",
                  "application/json",
                  "text/json",
                  "application/csv",
                  "text/comma-separated-values",
                  "application/zip",
                  "application/x-zip-compressed",
                  "application/octet-stream"
                ]'::jsonb,
                true
            ),
            '{fieldOptions,document,allowedExtensions}',
            '[
              ".pdf",
              ".doc",
              ".docx",
              ".xls",
              ".xlsx",
              ".csv",
              ".md",
              ".markdown",
              ".txt",
              ".json",
              ".ppt",
              ".pptx"
            ]'::jsonb,
            true
        ),
        '{fieldHelp,document,text}',
        to_jsonb(
            '正文抽取后最多 15 万字符；超出时请拆分文档。扫描 PDF 会使用所选模型识别页面文字。CSV、Markdown、TXT 和 JSON 仅支持 UTF-8 或 UTF-8 BOM。'::text
        ),
        true
    ),
    previous.output_schema_json,
    jsonb_set(
        previous.config_json,
        '{textEncoding}',
        '"UTF-8"'::jsonb,
        true
    ),
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 1
where feature.code = 'document.summary';

update feature_definition
set current_version = 2,
    description = '总结单个 PDF、Office、Markdown、TXT、JSON 或 CSV 文档，输出摘要、章节要点、结论和行动项。',
    updated_at = now()
where code = 'document.summary';
