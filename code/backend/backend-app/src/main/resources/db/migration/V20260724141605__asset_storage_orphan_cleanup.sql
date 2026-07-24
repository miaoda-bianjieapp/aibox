create index if not exists idx_asset_active_storage_key
    on asset(storage_key)
    where deleted_at is null;
