alter table provider_invocation
    add column if not exists invocation_scope varchar(30) not null default 'TASK_RUN';

alter table provider_invocation
    alter column run_id drop not null;

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'ck_provider_invocation_scope'
          and conrelid = 'provider_invocation'::regclass
    ) then
        alter table provider_invocation
            add constraint ck_provider_invocation_scope
            check (
                (invocation_scope = 'TASK_RUN' and run_id is not null)
                or (invocation_scope = 'PROMPT_ASSIST' and run_id is null)
            );
    end if;
end
$$;

create index if not exists idx_provider_invocation_scope_started
    on provider_invocation(tenant_id, invocation_scope, started_at desc);

insert into model_route (
    id,
    model_alias,
    capability,
    deployment_code,
    priority,
    enabled,
    created_at
) values (
    '5f9e46d1-2203-4a88-9600-000000000001',
    'prompt.optimize.default',
    'TEXT_GENERATION',
    'codex2api-gpt-5-4-mini-text',
    10,
    true,
    now()
)
on conflict (model_alias, capability, deployment_code) do update
set priority = excluded.priority,
    enabled = excluded.enabled;

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
    '5f9e46d1-2203-4a88-9600-000000000002',
    feature.id,
    2,
    previous.input_schema_json,
    previous.ui_schema_json || '{
      "promptAssist":{
        "fields":{
          "prompt":{"contextFields":["referenceImages","aspectRatio"]}
        }
      }
    }'::jsonb,
    previous.output_schema_json,
    previous.config_json,
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 1
where feature.code = 'image.generate'
on conflict do nothing;

insert into feature_version (
    id, feature_id, version, input_schema_json, ui_schema_json,
    output_schema_json, config_json, created_at
)
select
    '5f9e46d1-2203-4a88-9600-000000000003',
    feature.id,
    3,
    previous.input_schema_json,
    previous.ui_schema_json || '{
      "promptAssist":{
        "fields":{
          "instruction":{"contextFields":["sourceImage","maskImage"]}
        }
      }
    }'::jsonb,
    previous.output_schema_json,
    previous.config_json,
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 2
where feature.code = 'image.local_edit'
on conflict do nothing;

insert into feature_version (
    id, feature_id, version, input_schema_json, ui_schema_json,
    output_schema_json, config_json, created_at
)
select
    '5f9e46d1-2203-4a88-9600-000000000004',
    feature.id,
    4,
    previous.input_schema_json,
    previous.ui_schema_json || '{
      "promptAssist":{
        "fields":{
          "backgroundDescription":{"contextFields":["mode","backgroundImage"]}
        }
      }
    }'::jsonb,
    previous.output_schema_json,
    previous.config_json,
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 3
where feature.code = 'image.background_edit'
on conflict do nothing;

insert into feature_version (
    id, feature_id, version, input_schema_json, ui_schema_json,
    output_schema_json, config_json, created_at
)
select
    '5f9e46d1-2203-4a88-9600-000000000005',
    feature.id,
    2,
    previous.input_schema_json,
    previous.ui_schema_json || '{
      "promptAssist":{
        "fields":{
          "topic":{"contextFields":["audience","tone","length"]}
        }
      }
    }'::jsonb,
    previous.output_schema_json,
    previous.config_json,
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 1
where feature.code = 'writing.draft'
on conflict do nothing;

insert into feature_version (
    id, feature_id, version, input_schema_json, ui_schema_json,
    output_schema_json, config_json, created_at
)
select
    '5f9e46d1-2203-4a88-9600-000000000006',
    feature.id,
    2,
    previous.input_schema_json,
    previous.ui_schema_json || '{
      "promptAssist":{
        "fields":{
          "thesis":{"contextFields":["articleTitle","style"]}
        }
      }
    }'::jsonb,
    previous.output_schema_json,
    previous.config_json,
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 1
where feature.code = 'writing.outline_ideas'
on conflict do nothing;

insert into feature_version (
    id, feature_id, version, input_schema_json, ui_schema_json,
    output_schema_json, config_json, created_at
)
select
    '5f9e46d1-2203-4a88-9600-000000000007',
    feature.id,
    3,
    previous.input_schema_json,
    previous.ui_schema_json || '{
      "promptAssist":{
        "fields":{
          "rewriteRequirements":{"contextFields":["mode","sourceText"]},
          "polishRequirements":{"contextFields":["mode","sourceText"]}
        }
      }
    }'::jsonb,
    previous.output_schema_json,
    previous.config_json,
    now()
from feature_definition feature
join feature_version previous
  on previous.feature_id = feature.id
 and previous.version = 2
where feature.code = 'writing.rewrite_polish'
on conflict do nothing;

update feature_definition
set current_version = case code
        when 'image.generate' then 2
        when 'image.local_edit' then 3
        when 'image.background_edit' then 4
        when 'writing.draft' then 2
        when 'writing.outline_ideas' then 2
        when 'writing.rewrite_polish' then 3
        else current_version
    end,
    updated_at = now()
where code in (
    'image.generate',
    'image.local_edit',
    'image.background_edit',
    'writing.draft',
    'writing.outline_ideas',
    'writing.rewrite_polish'
);
