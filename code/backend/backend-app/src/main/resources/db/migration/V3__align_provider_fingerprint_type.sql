alter table provider_invocation
    alter column request_fingerprint type varchar(64)
    using trim(request_fingerprint);

