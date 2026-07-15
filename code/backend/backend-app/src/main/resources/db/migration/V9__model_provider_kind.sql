alter table model_provider
    add column provider_kind varchar(20) not null default 'OFFICIAL';

alter table model_provider
    add constraint ck_model_provider_kind
    check (provider_kind in ('OFFICIAL', 'RELAY'));

update model_provider
set provider_kind = 'OFFICIAL'
where provider_kind is null;
