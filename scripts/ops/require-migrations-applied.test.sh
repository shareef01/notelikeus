#!/usr/bin/env bash
# Tests for require-migrations-applied.sh. Run: bash scripts/ops/require-migrations-applied.test.sh
set -uo pipefail
script="$(dirname "$0")/require-migrations-applied.sh"
failures=0

expect() {
  local name="$1" want="$2" input="$3"
  printf '%s' "$input" | bash "$script" >/dev/null 2>&1
  local got=$?
  if [[ "$want" == "pass" && "$got" -ne 0 ]] || [[ "$want" == "fail" && "$got" -eq 0 ]]; then
    echo "FAIL: $name (exit $got)"
    failures=$((failures + 1))
  else
    echo "ok: $name"
  fi
}

header=$'   Local          | Remote         | Time (UTC)\n  ----------------|----------------|---------------------\n'
expect "every migration applied" pass "${header}   20250902000000 | 20250902000000 | 2025-09-02 00:00:00
   20261015000000 | 20261015000000 | 2026-10-15 00:00:00
"
expect "a local migration not applied" fail "${header}   20250902000000 | 20250902000000 | 2025-09-02 00:00:00
   20261015000000 |                | 2026-10-15 00:00:00
"
expect "a remote-only migration is not this check's concern" pass "${header}                  | 20240101000000 | 2024-01-01 00:00:00
   20250902000000 | 20250902000000 | 2025-09-02 00:00:00
"
expect "an unreadable listing" fail "Connecting to remote database...
"
expect "an empty listing" fail ""

[[ "$failures" -eq 0 ]] || exit 1
