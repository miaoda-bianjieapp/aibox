alter table task_run
    add column base_artifact_id uuid references artifact(id);

create index idx_run_base_artifact on task_run(base_artifact_id)
    where base_artifact_id is not null;

create table artifact_asset (
    artifact_id uuid not null references artifact(id) on delete cascade,
    asset_id uuid not null references asset(id),
    role varchar(80) not null,
    created_at timestamptz not null,
    primary key (artifact_id, asset_id, role)
);

create index idx_artifact_asset_asset on artifact_asset(asset_id);

