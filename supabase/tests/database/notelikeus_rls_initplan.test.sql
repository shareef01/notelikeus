begin;
select plan(1);

-- Every owner RLS policy must wrap auth.uid() in a subquery so Postgres builds an InitPlan
-- instead of re-evaluating current_setting() / JWT claims once per row.

create temporary table expected_policies (
    polname name primary key
);

insert into expected_policies (polname) values
    ('notes_select_own'),
    ('notes_insert_own'),
    ('notes_update_own'),
    ('notes_delete_own'),
    ('tombstones_select_own'),
    ('tombstones_insert_own'),
    ('tombstones_update_own'),
    ('tombstones_delete_own'),
    ('sync_meta_select_own'),
    ('sync_meta_insert_own'),
    ('sync_meta_update_own'),
    ('sync_meta_delete_own'),
    ('note_attachments_select_own'),
    ('note_attachments_insert_own'),
    ('note_attachments_update_own'),
    ('note_attachments_delete_own');

select is(
    ARRAY(
        select e.polname
        from expected_policies e
        left join pg_policy p on p.polname = e.polname
        where p.oid is null
           or not (
                (
                    p.polqual is null
                    or pg_get_expr(p.polqual, p.polrelid) ~* '\(\s*SELECT\s+auth\.uid\(\)'
                )
                and (
                    p.polwithcheck is null
                    or pg_get_expr(p.polwithcheck, p.polrelid) ~* '\(\s*SELECT\s+auth\.uid\(\)'
                )
                and (p.polqual is not null or p.polwithcheck is not null)
           )
        order by 1
    ),
    ARRAY[]::name[],
    'all owner RLS policies wrap auth.uid() in (SELECT …)'
);

select * from finish();
rollback;
