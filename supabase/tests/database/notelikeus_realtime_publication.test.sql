begin;
select plan(2);

select ok(
  exists (
    select 1
    from pg_publication_tables
    where pubname = 'supabase_realtime'
      and schemaname = 'public'
      and tablename = 'notes'
  ),
  'notes is in supabase_realtime publication'
);

select ok(
  exists (
    select 1
    from pg_publication_tables
    where pubname = 'supabase_realtime'
      and schemaname = 'public'
      and tablename = 'note_tombstones'
  ),
  'note_tombstones is in supabase_realtime publication'
);

select * from finish();
rollback;
