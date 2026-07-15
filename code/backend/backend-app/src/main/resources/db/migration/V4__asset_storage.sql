create table asset (
    id uuid primary key,
    tenant_id uuid not null,
    user_id uuid not null,
    original_name varchar(500) not null,
    media_type varchar(200) not null,
    size_bytes bigint not null,
    storage_key varchar(500) not null unique,
    sha256 varchar(64) not null,
    status varchar(30) not null,
    created_at timestamptz not null,
    deleted_at timestamptz,
    constraint ck_asset_status check (status in ('READY', 'DELETED'))
);

create index idx_asset_owner_created
    on asset(tenant_id, user_id, created_at desc)
    where deleted_at is null;

