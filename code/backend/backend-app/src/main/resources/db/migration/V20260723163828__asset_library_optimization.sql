alter table asset
    drop constraint if exists ck_asset_status;

alter table asset
    add constraint ck_asset_status
    check (status in ('READY', 'TEMPORARY', 'DELETED'));

alter table asset
    add column origin varchar(30) not null default 'USER_UPLOAD',
    add column media_category varchar(30) not null default 'OTHER',
    add column blob_id uuid;

alter table asset
    add constraint ck_asset_origin
    check (origin in ('USER_UPLOAD', 'MODEL_OUTPUT', 'APP_DERIVED'));

alter table asset
    add constraint ck_asset_media_category
    check (media_category in ('IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT', 'OTHER'));

create table asset_blob (
    id uuid primary key,
    tenant_id uuid not null,
    user_id uuid not null,
    sha256 varchar(64) not null,
    size_bytes bigint not null,
    storage_key varchar(500) not null unique,
    storage_backend varchar(30) not null default 'LOCAL_FS',
    status varchar(30) not null default 'READY',
    created_at timestamptz not null,
    deleted_at timestamptz,
    constraint uk_asset_blob_owner_hash unique (tenant_id, user_id, sha256, size_bytes),
    constraint ck_asset_blob_backend check (storage_backend in ('LOCAL_FS')),
    constraint ck_asset_blob_status check (status in ('READY', 'DELETED'))
);

insert into asset_blob (
    id, tenant_id, user_id, sha256, size_bytes, storage_key,
    storage_backend, status, created_at, deleted_at
)
select distinct on (tenant_id, user_id, sha256, size_bytes)
    gen_random_uuid(),
    tenant_id,
    user_id,
    sha256,
    size_bytes,
    storage_key,
    'LOCAL_FS',
    case when deleted_at is null then 'READY' else 'DELETED' end,
    created_at,
    deleted_at
from asset
order by tenant_id, user_id, sha256, size_bytes, deleted_at nulls first, created_at;

update asset a
set blob_id = blob.id
from asset_blob blob
where blob.tenant_id = a.tenant_id
  and blob.user_id = a.user_id
  and blob.sha256 = a.sha256
  and blob.size_bytes = a.size_bytes;

alter table asset
    alter column blob_id set not null,
    add constraint fk_asset_blob foreign key (blob_id) references asset_blob(id);

alter table asset
    drop constraint if exists asset_storage_key_key;

create index idx_asset_blob_owner_status
    on asset_blob(tenant_id, user_id, status, created_at desc);

create index idx_asset_blob_reference
    on asset(blob_id)
    where deleted_at is null;

update asset
set media_category = case
    when lower(media_type) like 'image/%' then 'IMAGE'
    when lower(media_type) like 'video/%' then 'VIDEO'
    when lower(media_type) like 'audio/%' then 'AUDIO'
    when lower(media_type) like 'text/%'
      or lower(media_type) in (
        'application/pdf',
        'application/json',
        'application/msword',
        'application/vnd.ms-excel',
        'application/vnd.ms-powerpoint',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'application/vnd.openxmlformats-officedocument.presentationml.presentation'
      )
      or lower(original_name) ~ '\.(txt|md|markdown|json|csv|pdf|doc|docx|xls|xlsx|ppt|pptx)$'
      then 'DOCUMENT'
    else 'OTHER'
end;

update asset a
set origin = 'MODEL_OUTPUT'
where exists (
    select 1
    from artifact_asset relation
    where relation.asset_id = a.id
);

update asset a
set origin = 'APP_DERIVED',
    status = case when a.deleted_at is null then 'TEMPORARY' else a.status end
where exists (
    select 1
    from task_run run
    where run.feature_code = 'image.local_edit'
      and run.parameters_json ->> 'maskImage' = a.id::text
);

create table task_run_asset (
    run_id uuid not null references task_run(id) on delete cascade,
    asset_id uuid not null references asset(id),
    direction varchar(20) not null,
    field_key varchar(120) not null,
    ordinal integer not null,
    snapshot_name varchar(500) not null,
    snapshot_media_type varchar(200) not null,
    snapshot_size_bytes bigint not null,
    created_at timestamptz not null,
    primary key (run_id, direction, field_key, ordinal),
    constraint ck_task_run_asset_direction check (direction in ('INPUT'))
);

insert into task_run_asset (
    run_id,
    asset_id,
    direction,
    field_key,
    ordinal,
    snapshot_name,
    snapshot_media_type,
    snapshot_size_bytes,
    created_at
)
select
    run.id,
    asset.id,
    'INPUT',
    coalesce((
        select parameter.key
        from jsonb_each(run.parameters_json) parameter
        where parameter.value = to_jsonb(asset.id::text)
           or (
                jsonb_typeof(parameter.value) = 'array'
                and parameter.value @> jsonb_build_array(asset.id::text)
           )
        order by parameter.key
        limit 1
    ), 'attachment'),
    input.ordinality::integer - 1,
    asset.original_name,
    asset.media_type,
    asset.size_bytes,
    run.created_at
from task_run run
cross join lateral jsonb_array_elements_text(run.input_asset_ids_json)
    with ordinality as input(asset_id_text, ordinality)
join asset on asset.id::text = input.asset_id_text;

create index idx_task_run_asset_asset
    on task_run_asset(asset_id, run_id);

create index idx_task_run_asset_run
    on task_run_asset(run_id, ordinal);

create index idx_asset_library_owner
    on asset(tenant_id, user_id, origin, media_category, created_at desc)
    where deleted_at is null and origin <> 'APP_DERIVED';

create index idx_asset_original_name_lower
    on asset(tenant_id, user_id, lower(original_name));
