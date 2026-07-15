update feature_version
set ui_schema_json = '{
  "order":["topic","audience","tone","length"],
  "widgets":{"topic":"textarea","audience":"text","tone":"segmented","length":"segmented"},
  "enumLabels":{
    "tone":{"professional":"专业","concise":"简洁","friendly":"亲切","creative":"创意"},
    "length":{"short":"短","medium":"中等","long":"长"}
  }
}'::jsonb
where feature_id = '20000000-0000-0000-0000-000000000001'
  and version = 1;
