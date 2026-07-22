create table run_output_stream (
    run_id uuid not null references task_run(id) on delete cascade,
    channel varchar(80) not null,
    format varchar(40) not null,
    content_text text not null default '',
    status varchar(30) not null,
    last_sequence bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (run_id, channel),
    constraint ck_run_output_stream_status check (
        status in ('STREAMING', 'COMPLETED', 'FAILED', 'PARTIAL')
    )
);

create table run_output_event (
    id bigserial primary key,
    run_id uuid not null references task_run(id) on delete cascade,
    channel varchar(80) not null,
    sequence bigint not null,
    event_type varchar(40) not null,
    payload_json jsonb not null,
    created_at timestamptz not null,
    unique (run_id, channel, sequence)
);

create index idx_run_output_event_replay
    on run_output_event(run_id, id);

create index idx_run_output_event_created
    on run_output_event(created_at);
