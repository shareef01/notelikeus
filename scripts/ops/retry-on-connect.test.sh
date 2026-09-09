#!/usr/bin/env bash
# Regression cover for retry-on-connect.sh.
#
# The first version of that script read $? after `fi`, where it is the status of the
# `if` compound -- 0 when no branch ran -- so every failure exited 0 and a failed
# migration would have been reported as a successful deploy. Case 2 and case 3 are
# there to keep that from coming back.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RETRY="$HERE/retry-on-connect.sh"
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

cat > "$WORK/sql-error.sh" <<'X'
echo 'ERROR: relation "notes" already exists (SQLSTATE 42P07)' >&2
exit 1
X

cat > "$WORK/connect-error.sh" <<'X'
echo 'failed to connect to postgres: server error (FATAL: password authentication failed for user "postgres" (SQLSTATE 28P01))' >&2
exit 1
X

cat > "$WORK/flaky.sh" <<'X'
n=$(cat "$COUNTER" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" > "$COUNTER"
if [ "$n" -lt 2 ]; then
  echo 'failed to connect to postgres: SQLSTATE 28P01' >&2
  exit 1
fi
echo "migrations applied"
X

cat > "$WORK/exit-three.sh" <<'X'
echo 'ERROR: something else entirely' >&2
exit 3
X

RETRY_BASE_DELAY=1 bash "$RETRY" echo ok >/dev/null 2>&1
check "a command that succeeds exits 0" 0 $?

RETRY_BASE_DELAY=1 bash "$RETRY" bash "$WORK/sql-error.sh" >/dev/null 2>&1
check "a SQL error fails instead of being swallowed" 1 $?

start=$SECONDS
RETRY_MAX_ATTEMPTS=3 RETRY_BASE_DELAY=1 bash "$RETRY" bash "$WORK/connect-error.sh" >/dev/null 2>&1
code=$?
check "a persistent connection failure fails" 1 "$code"
[ $((SECONDS - start)) -ge 2 ] && echo "ok   - it retried before giving up" || {
  echo "FAIL - it gave up without retrying"; failures=$((failures + 1)); }

export COUNTER="$WORK/counter"
RETRY_MAX_ATTEMPTS=4 RETRY_BASE_DELAY=1 bash "$RETRY" bash "$WORK/flaky.sh" >/dev/null 2>&1
check "a transient connection failure recovers" 0 $?
check "it recovered on the second attempt" 2 "$(cat "$COUNTER")"

RETRY_BASE_DELAY=1 bash "$RETRY" bash "$WORK/exit-three.sh" >/dev/null 2>&1
check "the command's own exit code survives" 3 $?

if [ "$failures" -ne 0 ]; then
  echo "$failures check(s) failed."
  exit 1
fi
echo "All checks passed."
