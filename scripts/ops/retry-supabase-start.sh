#!/usr/bin/env bash
# Start the local Supabase stack in CI, retrying only when the runner, not the code, failed.
#
# On a shared runner the image registry sometimes answers `Rate exceeded` while the CLI
# pulls the stack's images. The CLI then gives up part-way, and the database container it
# had already started keeps port 54322 bound, so starting again in the same state fails
# with `address already in use`. Both happened on two consecutive attempts of one run.
# A retry therefore stops whatever was started first, then waits before trying again.
#
# Anything else (a migration that does not apply, a bad config.toml) is a real failure
# and surfaces on the first attempt.
#
# Usage: retry-supabase-start.sh npx supabase start
set -uo pipefail

MAX_ATTEMPTS="${RETRY_MAX_ATTEMPTS:-3}"
BASE_DELAY="${RETRY_BASE_DELAY:-30}"
STOP_COMMAND="${RETRY_STOP_COMMAND:-npx supabase stop --no-backup}"

attempt=1
while :; do
  # As in retry-on-connect.sh: read the status inside the else branch, where it is the
  # command's own and not the `if` compound's.
  if output="$("$@" 2>&1)"; then
    printf '%s\n' "$output"
    exit 0
  else
    status=$?
  fi
  printf '%s\n' "$output"

  case "$output" in
    *'Rate exceeded'*|*'toomanyrequests'*|*'address already in use'*|*'port is already allocated'*)
      ;;
    *)
      exit "$status"
      ;;
  esac

  if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
    echo "The Supabase stack still did not start after ${MAX_ATTEMPTS} attempts. Giving up."
    exit "$status"
  fi

  delay=$((BASE_DELAY * attempt))
  echo "The runner could not start the stack (registry rate limit or a held port)."
  echo "Stopping what was started, then retrying in ${delay}s (attempt $((attempt + 1))/${MAX_ATTEMPTS})."
  $STOP_COMMAND || true
  sleep "$delay"
  attempt=$((attempt + 1))
done
