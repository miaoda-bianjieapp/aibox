create table model_provider (
    id uuid primary key,
    code varchar(80) not null unique,
    display_name varchar(120) not null,
    protocol varchar(80) not null,
    enabled boolean not null default true,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table model_deployment (
    id uuid primary key,
    code varchar(120) not null unique,
    provider_code varchar(80) not null references model_provider(code),
    display_name varchar(120) not null,
    description varchar(500) not null default '',
    capability varchar(80) not null,
    provider_model varchar(160) not null,
    enabled boolean not null default true,
    selectable boolean not null default false,
    config_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index idx_model_deployment_capability
    on model_deployment(capability, enabled, selectable);

create table model_route (
    id uuid primary key,
    model_alias varchar(120) not null,
    capability varchar(80) not null,
    deployment_code varchar(120) not null references model_deployment(code),
    priority integer not null,
    enabled boolean not null default true,
    created_at timestamptz not null,
    unique(model_alias, capability, deployment_code)
);

create index idx_model_route_lookup
    on model_route(model_alias, capability, enabled, priority);

create table feature_model_policy (
    id uuid primary key,
    feature_code varchar(120) not null references feature_definition(code),
    capability varchar(80) not null,
    default_deployment_code varchar(120) not null references model_deployment(code),
    allow_user_selection boolean not null default false,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique(feature_code, capability)
);

create table feature_model_option (
    policy_id uuid not null references feature_model_policy(id) on delete cascade,
    deployment_code varchar(120) not null references model_deployment(code),
    display_name varchar(120) not null,
    description varchar(500) not null default '',
    sort_order integer not null,
    enabled boolean not null default true,
    primary key(policy_id, deployment_code)
);

alter table task_run
    add column selected_model_code varchar(120) references model_deployment(code);

alter table provider_invocation
    add column deployment_code varchar(120) references model_deployment(code);

create index idx_invocation_deployment on provider_invocation(deployment_code, started_at);

insert into model_provider (
    id, code, display_name, protocol, enabled, created_at, updated_at
) values (
    '40000000-0000-0000-0000-000000000001',
    'zhipu-bigmodel',
    'Zhipu BigModel',
    'openai-compatible',
    true,
    now(),
    now()
);

insert into model_deployment (
    id, code, provider_code, display_name, description, capability,
    provider_model, enabled, selectable, config_json, created_at, updated_at
) values
    (
        '41000000-0000-0000-0000-000000000001',
        'zhipu-glm-5v-turbo-text',
        'zhipu-bigmodel',
        'GLM-5V Turbo',
        'General-purpose drafting and rewriting model',
        'TEXT_GENERATION',
        'glm-5v-turbo',
        true,
        true,
        '{}',
        now(),
        now()
    ),
    (
        '41000000-0000-0000-0000-000000000002',
        'zhipu-glm-5v-turbo-vision',
        'zhipu-bigmodel',
        'GLM-5V Turbo Vision',
        'Image understanding and multimodal analysis model',
        'VISION',
        'glm-5v-turbo',
        true,
        false,
        '{}',
        now(),
        now()
    );

insert into model_route (
    id, model_alias, capability, deployment_code, priority, enabled, created_at
) values
    (
        '42000000-0000-0000-0000-000000000001',
        'text.default',
        'TEXT_GENERATION',
        'zhipu-glm-5v-turbo-text',
        10,
        true,
        now()
    ),
    (
        '42000000-0000-0000-0000-000000000002',
        'vision.default',
        'VISION',
        'zhipu-glm-5v-turbo-vision',
        10,
        true,
        now()
    );

insert into feature_model_policy (
    id, feature_code, capability, default_deployment_code,
    allow_user_selection, created_at, updated_at
) values (
    '43000000-0000-0000-0000-000000000001',
    'writing.draft',
    'TEXT_GENERATION',
    'zhipu-glm-5v-turbo-text',
    true,
    now(),
    now()
);

insert into feature_model_option (
    policy_id, deployment_code, display_name, description, sort_order, enabled
) values (
    '43000000-0000-0000-0000-000000000001',
    'zhipu-glm-5v-turbo-text',
    'GLM-5V Turbo',
    'Default model for complete first drafts',
    10,
    true
);
