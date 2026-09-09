#!/usr/bin/env bash
# Run a Supabase CLI command, retrying only when it fails to reach the database.
#
# Supavisor caches credentials per pooler node, so for a window after a database
# password reset one node can still reject the new password while another accepts it.
# That surfaces as `FATAL: password authentication failed` on a credential that is
# demonstrably correct -- we have watched the same command succeed and then fail three
# minutes later inside a single workflow run.
#
# Only connection and authentication failures are retried. A SQL error from a migration
# is a real failure and must surface on the first attempt, because retrying it would
# just fail again more slowly and bury the error.
#
# Usage: retry-on-connect.sh npx supabase db push
set -uo pipefail

MAX_ATTEMPTS="${RETRY_MAX_ATTEMPTS:-4}"
BASE_DELAY="${RETRY_BASE_DELAY:-15}"

attempt=1
while :; do
  # `status` must be read inside the else branch: after `fi`, $? is the status of the
  # `if` compound itself, which is 0 when no branch ran -- that would report a failed
  # migration as a successful deploy.
  if output="$("$@" 2>&1)"; then
    printf '%s\n' "$output"
    exit 0
  else
    status=$?
  fi
  printf '%s\n' "$output"

  case "$output" in
    *'failed to connect to postgres'*|*'SQLSTATE 28P01'*|*'connection refused'*|*'i/o timeout'*)
      ;;
    *)
      # Not a connectivity problem. Surface it now.
      exit "$status"
      ;;
  esac

  if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
    echo "Still cannot reach the database after ${MAX_ATTEMPTS} attempts. Giving up."
    exit "$status"
  fi

  delay=$((BASE_DELAY * attempt))
  echo "Could not reach the database. This is the shape of a pooler serving a stale"
  echo "credential, so retrying in ${delay}s (attempt $((attempt + 1))/${MAX_ATTEMPTS})."
  sleep "$delay"
  attempt=$((attempt + 1))
done
