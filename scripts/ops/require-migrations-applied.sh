#!/usr/bin/env bash
# Fails when a migration committed in supabase/migrations has not been applied to the linked
# (hosted) project. Run before deploying a client that may call an RPC only those migrations add:
# the web deploy is automatic and the database deploy is not, so this is what keeps them in order.
#
# Reads `supabase migration list --linked` on stdin (its table is "Local | Remote | Time"): a row
# whose Local column holds a version and whose Remote column is empty is a pending migration. A
# listing with no migration rows at all fails too -- that means the format changed, not that the
# database is up to date.
#
# Usage: npx supabase migration list --linked | require-migrations-applied.sh
set -euo pipefail

listing="$(cat)"
rows=0
pending=()
while IFS='|' read -r local remote _rest; do
  local_version="$(printf '%s' "$local" | tr -d '[:space:]')"
  remote_version="$(printf '%s' "$remote" | tr -d '[:space:]')"
  [[ "$local_version$remote_version" =~ ^[0-9]{14}([0-9]{14})?$ ]] || continue
  rows=$((rows + 1))
  if [[ -n "$local_version" && -z "$remote_version" ]]; then
    pending+=("$local_version")
  fi
done <<< "$listing"

if [[ "$rows" -eq 0 ]]; then
  printf '%s\n' "$listing"
  echo "::error::Could not read any migration rows from the listing above."
  exit 1
fi
if [[ "${#pending[@]}" -gt 0 ]]; then
  echo "::error::Not applied to the hosted database yet: ${pending[*]}. Run the Supabase CI workflow (deploy) first, then re-run this deploy."
  exit 1
fi
echo "All ${rows} migrations are applied to the hosted database."
