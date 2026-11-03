#!/usr/bin/env bash
# AUD-007, with two real connections: a note insert that is still in flight when a cloud wipe starts
# must not outlive the wipe. pgTAP runs in one session and cannot hold a second transaction open, so
# this drives the local Supabase database directly.
#
# Run against a local stack only (`npx supabase start`, then `npx supabase db reset`):
#   bash scripts/ops/wipe-lock-race.test.sh
# It creates one throwaway auth user, and removes it and its rows again at the end.
set -uo pipefail

container="${NOTELIKEUS_DB_CONTAINER:-supabase_db_notelikeus}"
psql_db() { docker exec -i "$container" psql -U postgres -d postgres -v ON_ERROR_STOP=1 -qtA "$@"; }

uid="$(psql_db -c "select gen_random_uuid()")"
email="wipe-race-${uid}@notelikeus.test"
# A fresh sign-in, as GoTrue issues it: the wipe needs one within the last ten minutes (AUD-005).
claims="{\"sub\":\"${uid}\",\"role\":\"authenticated\",\"amr\":[{\"method\":\"otp\",\"timestamp\":$(date +%s)}]}"
as_user="set local role authenticated; select set_config('request.jwt.claims', '${claims}', true);"

cleanup() {
  psql_db >/dev/null <<SQL
begin;
select notelikeus_internal.begin_sync_mutation();
select public.present_wipe_generation(
  coalesce((select wipe_generation from public.account_sync_state where owner_id = '${uid}'), 0));
delete from public.note_attachments where owner_id = '${uid}';
delete from public.notes where owner_id = '${uid}';
delete from public.note_tombstones where owner_id = '${uid}';
delete from public.sync_meta where owner_id = '${uid}';
delete from public.account_sync_state where owner_id = '${uid}';
delete from auth.users where id = '${uid}';
commit;
SQL
}
trap cleanup EXIT

psql_db >/dev/null -c "insert into auth.users (id, email, aud, role) values ('${uid}', '${email}', 'authenticated', 'authenticated')"

# Connection 1: inserts a brand-new note, then keeps its transaction open for three seconds.
psql_db >/dev/null <<SQL &
begin;
${as_user}
select public.apply_note_change(
  '77', 77::bigint, null::bigint, 'In flight', '', 100::bigint, 0, false, false, false, 0,
  null::bigint, '[]'::jsonb, '[]'::jsonb);
select pg_sleep(3);
commit;
SQL
writer=$!

sleep 1
# Connection 2: the wipe, started while connection 1 is still open.
started=$(date +%s)
psql_db >/dev/null <<SQL
begin;
${as_user}
select public.delete_all_user_cloud_data();
commit;
SQL
waited=$(( $(date +%s) - started ))
wait "$writer"

survivors="$(psql_db -c "select count(*) from public.notes where owner_id = '${uid}'")"
failures=0
if [[ "$survivors" == "0" ]]; then
  echo "ok: the in-flight note did not outlive the wipe"
else
  echo "FAIL: ${survivors} note(s) outlived the wipe (AUD-007)"
  failures=$((failures + 1))
fi
if (( waited >= 1 )); then
  echo "ok: the wipe waited for the writer (${waited}s)"
else
  echo "FAIL: the wipe did not wait for the writer's lock"
  failures=$((failures + 1))
fi
exit "$failures"
