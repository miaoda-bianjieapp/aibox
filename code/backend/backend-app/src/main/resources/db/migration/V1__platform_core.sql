create table workspace (
    id uuid primary key,
    code varchar(80) not null unique,
    display_name varchar(120) not null,
    description varchar(500) not null,
    icon_key varchar(80) not null,
    groups_json jsonb not null default '[]'::jsonb,
    search_terms_json jsonb not null default '[]'::jsonb,
    sort_order integer not null,
    enabled boolean not null default true,
    created_at timestamptz not null
);

create table feature_definition (
    id uuid primary key,
    workspace_id uuid not null references workspace(id),
    code varchar(120) not null unique,
    display_name varchar(120) not null,
    description varchar(500) not null,
    status varchar(30) not null,
    current_version integer not null,
    result_type varchar(80) not null,
    renderer_key varchar(80) not null,
    execution_mode varchar(30) not null,
    sort_order integer not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ck_feature_status check (status in ('DRAFT', 'INTERNAL', 'BETA', 'PUBLISHED', 'DISABLED'))
);

create index idx_feature_workspace_status on feature_definition(workspace_id, status, sort_order);

create table feature_version (
    id uuid primary key,
    feature_id uuid not null references feature_definition(id),
    version integer not null,
    input_schema_json jsonb not null,
    ui_schema_json jsonb not null,
    output_schema_json jsonb not null,
    config_json jsonb not null,
    created_at timestamptz not null,
    unique (feature_id, version)
);

create table project (
    id uuid primary key,
    tenant_id uuid not null,
    user_id uuid not null,
    name varchar(200) not null,
    description varchar(1000) not null default '',
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz
);

create index idx_project_owner_updated on project(tenant_id, user_id, updated_at desc) where deleted_at is null;

create table task (
    id uuid primary key,
    tenant_id uuid not null,
    user_id uuid not null,
    project_id uuid references project(id),
    feature_code varchar(120) not null,
    title varchar(240) not null,
    status varchar(30) not null,
    current_artifact_id uuid,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deleted_at timestamptz,
    constraint ck_task_status check (status in ('ACTIVE', 'COMPLETED', 'ARCHIVED'))
);

create index idx_task_owner_updated on task(tenant_id, user_id, updated_at desc) where deleted_at is null;
create index idx_task_project on task(project_id, updated_at desc) where deleted_at is null;

create table task_run (
    id uuid primary key,
    tenant_id uuid not null,
    user_id uuid not null,
    task_id uuid not null references task(id),
    run_number integer not null,
    feature_code varchar(120) not null,
    feature_version integer not null,
    status varchar(30) not null,
    parameters_json jsonb not null default '{}'::jsonb,
    input_asset_ids_json jsonb not null default '[]'::jsonb,
    cancel_requested boolean not null default false,
    error_code varchar(100),
    error_message varchar(2000),
    queued_at timestamptz,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz not null,
    version bigint not null default 0,
    unique(task_id, run_number),
    constraint ck_run_status check (status in (
        'CREATED', 'VALIDATING', 'QUEUED', 'RUNNING', 'WAITING_CALLBACK',
        'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'EXPIRED'
    ))
);

create index idx_run_owner_created on task_run(tenant_id, user_id, created_at desc);
create index idx_run_task_number on task_run(task_id, run_number desc);
create index idx_run_status_created on task_run(status, created_at);

create table artifact (
    id uuid primary key,
    tenant_id uuid not null,
    user_id uuid not null,
    task_id uuid not null references task(id),
    run_id uuid not null references task_run(id),
    parent_artifact_id uuid references artifact(id),
    version_number integer not null,
    kind varchar(80) not null,
    title varchar(240) not null,
    mime_type varchar(160) not null,
    content_json jsonb not null default '{}'::jsonb,
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null
);

alter table task
    add constraint fk_task_current_artifact foreign key (current_artifact_id) references artifact(id);

create index idx_artifact_task_created on artifact(task_id, created_at desc);
create index idx_artifact_run_created on artifact(run_id, created_at);
create index idx_artifact_parent_version on artifact(parent_artifact_id, version_number);

create table job (
    id uuid primary key,
    tenant_id uuid not null,
    run_id uuid not null references task_run(id),
    type varchar(80) not null,
    status varchar(30) not null,
    attempts integer not null default 0,
    max_attempts integer not null default 3,
    available_at timestamptz not null,
    locked_by varchar(160),
    locked_until timestamptz,
    last_error varchar(2000),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ck_job_status check (status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

create index idx_job_claim on job(status, type, available_at, created_at);
create index idx_job_run on job(run_id);

create table idempotency_record (
    id uuid primary key,
    tenant_id uuid not null,
    scope varchar(200) not null,
    idempotency_key varchar(200) not null,
    request_hash char(64) not null,
    resource_type varchar(80) not null,
    resource_id uuid not null,
    created_at timestamptz not null,
    unique(tenant_id, scope, idempotency_key)
);

create index idx_idempotency_created on idempotency_record(created_at);

create table outbox_event (
    id uuid primary key,
    aggregate_type varchar(80) not null,
    aggregate_id uuid not null,
    event_type varchar(120) not null,
    payload_json jsonb not null,
    status varchar(30) not null,
    created_at timestamptz not null,
    published_at timestamptz
);

create index idx_outbox_unpublished on outbox_event(status, created_at) where status = 'NEW';

create table provider_invocation (
    id uuid primary key,
    tenant_id uuid not null,
    run_id uuid not null references task_run(id),
    capability varchar(80) not null,
    provider_code varchar(80) not null,
    model_alias varchar(120) not null,
    provider_model varchar(160),
    provider_request_id varchar(240),
    status varchar(30) not null,
    request_fingerprint char(64) not null,
    input_units bigint,
    output_units bigint,
    error_code varchar(100),
    started_at timestamptz not null,
    finished_at timestamptz
);

create index idx_invocation_run on provider_invocation(run_id, started_at);
create unique index uk_invocation_provider_request
    on provider_invocation(provider_code, provider_request_id)
    where provider_request_id is not null;

