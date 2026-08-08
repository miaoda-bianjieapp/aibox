with ranked_assets as (
    select id,
           row_number() over (
               partition by tenant_id,
                            user_id,
                            scope,
                            coalesce(project_id, '00000000-0000-0000-0000-000000000000'::uuid),
                            asset_type,
                            name,
                            description,
                            personality
               order by (
                   (current_primary_asset_id is not null)::int
                   + (current_three_view_asset_id is not null)::int
                   + (approved_primary_asset_id is not null)::int
                   + (approved_three_view_asset_id is not null)::int
               ) desc,
               updated_at desc,
               created_at desc,
               id desc
           ) as duplicate_rank
    from creative_asset
    where deleted_at is null
)
update creative_asset
set deleted_at = now(),
    updated_at = now()
where id in (
    select id
    from ranked_assets
    where duplicate_rank > 1
);
