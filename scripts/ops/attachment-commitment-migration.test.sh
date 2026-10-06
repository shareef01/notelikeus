#!/usr/bin/env bash
# R19.2: the attachment-commitment migration against real PostgreSQL.
#
# Three lanes that a single-database test file cannot express:
#
#   1. every migration applies to a database from scratch (the same property the Supabase CI job
#      checks with `supabase db reset`, here without the Supabase stack);
#   2. a row created *before* the migration -- one an older client's upload committed at upload time --
#      is still visible after it (the legacy policy: nothing that is visible today may disappear);
#   3. applying the migration twice leaves provisional rows provisional (the backfill is one-shot).
#
# Usage: bash scripts/ops/attachment-commitment-migration.test.sh [container-name]
# Requires: docker, network access for the postgres image, and a checked-out repository.
set -euo pipefail

CONTAINER="${1:-r19mig}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATIONS="$ROOT/supabase/migrations"
readonly MIGRATION="$MIGRATIONS/20260910000000_attachment_commitment.sql"
readonly DB=r19mig_lane

cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "# starting postgres:17 in $CONTAINER"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CONTAINER" -e POSTGRES_PASSWORD=pg postgres:17 >/dev/null
for _ in $(seq 1 30); do
    docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1 && break
    sleep 1
done

psql_file() { docker cp "$1" "$CONTAINER:/tmp/x.sql" >/dev/null; docker exec "$CONTAINER" psql -U postgres -d "$DB" -q -v ON_ERROR_STOP=1 -f /tmp/x.sql; }
psql_cmd() { docker exec "$CONTAINER" psql -U postgres -d "$DB" -qtA -c "$1"; }

# Supabase provides auth/roles; the harness stubs exactly what these migrations touch.
cat > /tmp/r19mig_stub.sql <<'SQL'
create schema if not exists auth;
create table if not exists auth.users (
  id uuid primary key default gen_random_uuid(),
  email text, phone text,
  raw_user_meta_data jsonb default '{}'::jsonb,
  raw_app_meta_data jsonb default '{}'::jsonb,
  created_at timestamptz default now(), updated_at timestamptz default now()
);
create or replace function auth.uid() returns uuid language sql stable as $$
  select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;
create or replace function auth.role() returns text language sql stable as $$
  select coalesce(nullif(current_setting('request.jwt.claim.role', true), ''), 'authenticated')
$$;
do $$ begin
  if not exists (select 1 from pg_roles where rolname='authenticated') then create role authenticated; end if;
  if not exists (select 1 from pg_roles where rolname='anon') then create role anon; end if;
  if not exists (select 1 from pg_roles where rolname='service_role') then create role service_role; end if;
end $$;
-- Supabase ships this publication; one migration adds its tables to it.
do $$ begin
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    create publication supabase_realtime;
  end if;
end $$;
SQL

setup_database() {
    docker exec "$CONTAINER" psql -U postgres -q -c "drop database if exists $DB;" >/dev/null
    docker exec "$CONTAINER" psql -U postgres -q -c "create database $DB;" >/dev/null
    psql_file /tmp/r19mig_stub.sql
    for migration in $(ls "$MIGRATIONS"/*.sql | sort); do
        [ "$migration" = "$MIGRATION" ] && continue
        psql_file "$migration" >/dev/null
    done
    # Supabase's default grants, which the migrations rely on rather than create.
    psql_cmd "grant usage on schema public, auth to authenticated, anon, service_role;
              grant all on all tables in schema public to authenticated, service_role;
              grant all on all sequences in schema public to authenticated, service_role;
              grant all on all functions in schema public to authenticated, service_role;" >/dev/null
}

failures=0
check() { # check <label> <expected> <actual>
    if [ "$2" = "$3" ]; then echo " ok - $1"; else echo " not ok - $1 (want '$2', have '$3')"; failures=$((failures + 1)); fi
}

echo "# lane 1: every migration applies to a fresh database"
setup_database
check "the schema is present after a from-scratch build" \
    "1" "$(psql_cmd "select count(*) from information_schema.tables where table_name='note_attachments'")"

echo "# lane 2: a row created before the migration stays visible"
setup_database
OWNER=11111111-1111-1111-1111-111111111111
psql_cmd "insert into auth.users (id, email) values ('$OWNER', 'legacy@test');" >/dev/null
# The row the pre-R19.1 client would have created: its own note, then a legacy (committed-at-upload)
# upload against it.
psql_cmd "set request.jwt.claim.sub = '$OWNER';
          select public.apply_note_change('42', 42, null, 'legacy note', 'body', 9000, 0, false, false,
                                          false, 0, null, '[]'::jsonb, '[]'::jsonb);" >/dev/null
psql_cmd "set request.jwt.claim.sub = '$OWNER';
          select public.finalize_note_attachment_put('42', 'att-legacy',
                 'owners/$OWNER/notes/42/att-legacy', 'image/png', 3, 'image');" >/dev/null
psql_file "$MIGRATION" >/dev/null
check "the pre-migration row was backfilled as committed" \
    "1" "$(psql_cmd "set request.jwt.claim.sub = '$OWNER'; select count(*) from note_attachments where attachment_id='att-legacy' and committed_at is not null;")"
check "the pre-migration row is still visible to hydration" \
    "1" "$(psql_cmd "set request.jwt.claim.sub = '$OWNER'; select jsonb_array_length(public.list_user_attachments());")"

echo "# lane 3: applying the migration twice leaves provisional rows provisional"
psql_cmd "set request.jwt.claim.sub = '$OWNER';
          select public.finalize_note_attachment_put('42', 'att-new', 'owners/$OWNER/notes/42/att-new', 'image/png', 3, true, 'image');" >/dev/null
check "the deferred upload is provisional" \
    "1" "$(psql_cmd "select count(*) from note_attachments where attachment_id='att-new' and committed_at is null;")"
psql_file "$MIGRATION" >/dev/null
check "the re-applied migration left it provisional" \
    "1" "$(psql_cmd "select count(*) from note_attachments where attachment_id='att-new' and committed_at is null;")"
check "the re-applied migration did not re-hide the committed row" \
    "1" "$(psql_cmd "set request.jwt.claim.sub = '$OWNER'; select count(*) from note_attachments where attachment_id='att-legacy' and committed_at is not null;")"

echo "1..3"
if [ "$failures" -ne 0 ]; then
    echo "# $failures lane(s) failed"
    exit 1
fi
echo "# all migration lanes passed"
