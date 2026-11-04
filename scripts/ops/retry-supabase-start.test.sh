#!/usr/bin/env bash
# Regression cover for retry-supabase-start.sh: it retries the runner's transient failures,
# stops the half-started stack before each retry, and lets every other failure through.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RETRY="$HERE/retry-supabase-start.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

failures=0
check() {
  local name="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    echo "ok   - $name"
  else
    echo "FAIL - $name (expected $expected, got $actual)"
    failures=$((failures + 1))
  fi
}

export STOPS="$WORK/stops"
stops() { if [ -f "$STOPS" ]; then wc -l < "$STOPS" | tr -d ' '; else echo 0; fi; }
cat > "$WORK/stop.sh" <<'X'
echo stopped >> "$STOPS"
X
export RETRY_STOP_COMMAND="bash $WORK/stop.sh"
export RETRY_BASE_DELAY=0

cat > "$WORK/migration-error.sh" <<'X'
echo 'ERROR: relation "notes" already exists (SQLSTATE 42P07)' >&2
exit 1
X

cat > "$WORK/port-held.sh" <<'X'
echo 'failed to bind host port 0.0.0.0:54322/tcp: address already in use' >&2
exit 1
X

cat > "$WORK/flaky.sh" <<'X'
n=$(cat "$COUNTER" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" > "$COUNTER"
if [ "$n" -lt 2 ]; then
  echo 'error from registry: Rate exceeded' >&2
  exit 1
fi
echo "Started supabase local development setup."
X

bash "$RETRY" echo ok >/dev/null 2>&1
check "a start that succeeds exits 0" 0 $?

rm -f "$STOPS"
bash "$RETRY" bash "$WORK/migration-error.sh" >/dev/null 2>&1
check "a migration error fails on the first attempt" 1 $?
check "nothing is stopped for a real failure" 0 "$(stops)"

rm -f "$STOPS"
RETRY_MAX_ATTEMPTS=3 bash "$RETRY" bash "$WORK/port-held.sh" >/dev/null 2>&1
check "a port that stays held fails" 1 $?
check "the stack is stopped before each retry" 2 "$(stops)"

rm -f "$STOPS"
export COUNTER="$WORK/counter"
bash "$RETRY" bash "$WORK/flaky.sh" >/dev/null 2>&1
check "a registry rate limit recovers" 0 $?
check "it recovered on the second attempt" 2 "$(cat "$COUNTER")"

if [ "$failures" -ne 0 ]; then
  echo "$failures check(s) failed."
  exit 1
fi
echo "All checks passed."
