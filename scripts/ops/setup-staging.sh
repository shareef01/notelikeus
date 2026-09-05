#!/usr/bin/env bash
# Bootstrap a Notelikeus *staging* stack (Supabase + R2 attachments worker).
#
# Required environment variables:
#   SUPABASE_ACCESS_TOKEN   — personal access token (Account → Access Tokens)
#   SUPABASE_PROJECT_REF    — staging project ref (subdomain before .supabase.co)
#   SUPABASE_ANON_KEY       — optional if SUPABASE_ACCESS_TOKEN can list keys;
#                             must be the anon *public* JWT (eyJ…), never sb_secret_…
#   CLOUDFLARE_API_TOKEN    — Workers + R2 edit (or run `wrangler login` first)
#   CLOUDFLARE_ACCOUNT_ID   — Cloudflare dashboard → account id
#
# Optional:
#   SUPABASE_DB_PASSWORD    — database password (avoids interactive prompt on link)
#   STAGING_WEB_ORIGIN      — Pages preview origin for OAuth redirects (default: http://localhost:5173)
#   SKIP_CLOUDFLARE=1       — only push Supabase migrations
#   SKIP_SUPABASE=1         — only deploy Cloudflare worker (wrangler.toml must exist)
#   DRY_RUN=1               — print planned steps without mutating remote state
#
# Outputs (gitignored):
#   web/.env.staging        — copy to web/.env for local smoke tests
#   local.properties        — Kotlin staging keys merged (oauth secret preserved)
#   workers/attachments/wrangler.toml
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

STAGING_WEB_ORIGIN="${STAGING_WEB_ORIGIN:-http://localhost:5173}"
SUPABASE_URL="${SUPABASE_URL:-https://${SUPABASE_PROJECT_REF}.supabase.co}"
R2_BUCKET="${R2_BUCKET:-notelikeus-attachments-dev}"
WORKER_DIR="$ROOT/workers/attachments"

red() { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
info() { printf '→ %s\n' "$*"; }

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    red "Missing required env var: $name"
    exit 1
  fi
}

run() {
  if [[ "${DRY_RUN:-}" == "1" ]]; then
    info "[dry-run] $*"
  else
    info "$*"
    "$@"
  fi
}

# Like run(), but logs a label instead of argv so tokens/passwords stay out of output.
run_logged() {
  local label="$1"
  shift
  if [[ "${DRY_RUN:-}" == "1" ]]; then
    info "[dry-run] $label"
  else
    info "$label"
    "$@"
  fi
}

# Resolves a browser-safe anon JWT (role=anon, eyJ…). The Cursor/env secret named
# SUPABASE_ANON_KEY is often a new sb_secret_ key, which GoTrue rejects in the browser
# with "Forbidden use of secret API key in browser".
resolve_browser_anon_key() {
  require_env SUPABASE_ACCESS_TOKEN
  require_env SUPABASE_PROJECT_REF
  if [[ "${DRY_RUN:-}" == "1" ]]; then
    info "[dry-run] resolve browser-safe anon JWT from Management API"
    return
  fi
  local json resolved
  set +e
  json="$(curl -fsS \
    -H "Authorization: Bearer ${SUPABASE_ACCESS_TOKEN}" \
    -H "User-Agent: notelikeus-setup-staging/1.0" \
    -H "Accept: application/json" \
    "https://api.supabase.com/v1/projects/${SUPABASE_PROJECT_REF}/api-keys" 2>/dev/null)"
  local curl_status=$?
  set -e
  if [[ "$curl_status" -eq 0 ]]; then
    resolved="$(printf '%s' "$json" | python3 -c '
import json, sys
keys = json.load(sys.stdin)
if not isinstance(keys, list):
    raise SystemExit("unexpected api-keys payload")
anon = next((k.get("api_key") or k.get("key") or "" for k in keys if k.get("name") == "anon"), "")
if not anon.startswith("eyJ"):
    raise SystemExit("Management API did not return a JWT anon public key")
print(anon)
')"
  fi
  if [[ -z "${resolved:-}" && "${SUPABASE_ANON_KEY:-}" == eyJ* ]]; then
    info "Management API key lookup failed; using SUPABASE_ANON_KEY (JWT)"
    return
  fi
  if [[ -z "${resolved:-}" ]]; then
    red "Could not resolve a browser-safe anon JWT. Set SUPABASE_ANON_KEY to the Project Settings → API anon public key (eyJ…), not a secret key."
    exit 1
  fi
  if [[ -n "${SUPABASE_ANON_KEY:-}" && "${SUPABASE_ANON_KEY}" != "${resolved}" ]]; then
    info "Using Management API anon JWT (len=${#resolved}); ignoring SUPABASE_ANON_KEY (not browser-safe)"
  else
    info "Resolved browser-safe anon JWT (len=${#resolved})"
  fi
  SUPABASE_ANON_KEY="$resolved"
}

print_manual_steps() {
  local pages_origin="https://notelikeus-dev.pages.dev"
  cat <<EOF

Manual dashboard steps (agent cannot complete these via CLI):

Supabase Auth → Providers → Google:
  1. Enable Google provider.
  2. Client ID: Google Cloud Web OAuth client used by the app.
  3. Client secret: from Google Cloud Console for that OAuth client.
  4. Auth → URL configuration → Redirect URLs, add:
       ${STAGING_WEB_ORIGIN}/**
       ${STAGING_WEB_ORIGIN}
       ${pages_origin}/**
       ${pages_origin}

Supabase Auth → URL configuration:
  - Site URL: ${STAGING_WEB_ORIGIN}  (keep localhost for Vite smoke)
  - Also allow ${pages_origin} as a Redirect URL

Google Cloud Console → Web OAuth client:
  Authorized JavaScript origins:
  - ${pages_origin}
  - ${STAGING_WEB_ORIGIN}
  Authorized redirect URIs:
  - https://${SUPABASE_PROJECT_REF}.supabase.co/auth/v1/callback

Smoke test (after copying web/.env.staging → web/.env):
  cd web && npm run dev
  - Sign in with Google
  - Create a note, add an image attachment, confirm sync

Pages staging deploy (does not cut over production):
  npm run deploy:staging-pages

Kotlin debug staging:
  npm run kotlin:staging-properties
  - Android: rebuild the debug APK so BuildConfig picks up local.properties
  - Desktop: restart ./gradlew run (reads local.properties at runtime)
EOF
}

if [[ "${SKIP_SUPABASE:-}" != "1" ]]; then
  require_env SUPABASE_ACCESS_TOKEN
  require_env SUPABASE_PROJECT_REF
fi

if [[ "${SKIP_CLOUDFLARE:-}" != "1" ]]; then
  require_env CLOUDFLARE_ACCOUNT_ID
  require_env SUPABASE_PROJECT_REF
  if [[ -z "${CLOUDFLARE_API_TOKEN:-}" ]] && ! npx wrangler whoami >/dev/null 2>&1; then
    red "Set CLOUDFLARE_API_TOKEN or run: npx wrangler login"
    exit 1
  fi
fi

export SUPABASE_ACCESS_TOKEN
resolve_browser_anon_key

if [[ "${SKIP_SUPABASE:-}" != "1" ]]; then
  info "Supabase: authenticate and link staging project ${SUPABASE_PROJECT_REF}"
  run_logged "npx supabase login --token [redacted]" npx supabase login --token "$SUPABASE_ACCESS_TOKEN"

  link_args=(link --project-ref "$SUPABASE_PROJECT_REF" --yes)
  if [[ -n "${SUPABASE_DB_PASSWORD:-}" ]]; then
    link_args+=(--password "$SUPABASE_DB_PASSWORD")
  fi
  run_logged "npx supabase link --project-ref ${SUPABASE_PROJECT_REF} --yes [--password redacted]" npx supabase "${link_args[@]}"

  info "Supabase: push migrations from supabase/migrations/"
  run npx supabase db push --yes

  green "Supabase staging schema applied at ${SUPABASE_URL}"
fi

# Preserve a caller-supplied WORKER_URL (e.g. SKIP_CLOUDFLARE=1 with the live worker).
WORKER_URL="${WORKER_URL:-}"

write_env_staging() {
  if [[ "${DRY_RUN:-}" == "1" ]]; then
    info "[dry-run] write web/.env.staging"
    return
  fi
  if [[ -z "${WORKER_URL:-}" && -f "$ROOT/web/.env.staging" ]]; then
    WORKER_URL="$(sed -n 's/^VITE_ATTACHMENTS_WORKER_URL=//p' "$ROOT/web/.env.staging" | head -1 || true)"
  fi
  cat > "$ROOT/web/.env.staging" <<EOF
# Staging — copy to web/.env for local smoke tests.
VITE_SUPABASE_URL=${SUPABASE_URL}
VITE_SUPABASE_ANON_KEY=${SUPABASE_ANON_KEY}
VITE_ATTACHMENTS_WORKER_URL=${WORKER_URL:-http://127.0.0.1:8787}
EOF
  green "Wrote web/.env.staging (copy to web/.env)"
}

if [[ "${SKIP_SUPABASE:-}" != "1" ]]; then
  write_env_staging
fi

if [[ "${SKIP_CLOUDFLARE:-}" != "1" ]]; then
  info "Cloudflare: ensure R2 bucket ${R2_BUCKET}"
  if [[ "${DRY_RUN:-}" == "1" ]]; then
    info "[dry-run] npx wrangler r2 bucket create ${R2_BUCKET}"
  else
    set +e
    bucket_out="$(npx wrangler r2 bucket create "$R2_BUCKET" 2>&1)"
    bucket_status=$?
    set -e
    printf '%s\n' "$bucket_out"
    if [[ "$bucket_status" -ne 0 ]]; then
      if echo "$bucket_out" | grep -q '10042'; then
        red "R2 is not enabled on this Cloudflare account (API 10042)."
        red "Enable it in the dashboard: Storage & databases → R2 → Overview → complete the checkout flow."
        red "Then re-run: SKIP_SUPABASE=1 npm run setup:staging"
        exit 1
      fi
      if echo "$bucket_out" | grep -qi 'already exists'; then
        info "R2 bucket already exists (continuing)"
      else
        red "Failed to create R2 bucket ${R2_BUCKET}"
        exit 1
      fi
    fi
  fi

  info "Cloudflare: write ${WORKER_DIR}/wrangler.toml"
  if [[ "${DRY_RUN:-}" != "1" ]]; then
    cat > "$WORKER_DIR/wrangler.toml" <<EOF
name = "notelikeus-attachments"
main = "src/index.ts"
compatibility_date = "2025-09-02"
account_id = "${CLOUDFLARE_ACCOUNT_ID}"

[[r2_buckets]]
binding = "ATTACHMENTS_BUCKET"
bucket_name = "${R2_BUCKET}"

[vars]
SUPABASE_URL = "${SUPABASE_URL}"
# localhost and *.pages.dev are allowed in worker CORS; add extras here if needed.
# ALLOWED_ORIGINS = "${STAGING_WEB_ORIGIN}"
EOF
  fi

  info "Cloudflare: deploy attachments worker"
  if [[ "${DRY_RUN:-}" == "1" ]]; then
    info "[dry-run] wrangler secret put SUPABASE_ANON_KEY (in ${WORKER_DIR})"
    info "[dry-run] npx wrangler deploy (in ${WORKER_DIR})"
    WORKER_URL="https://notelikeus-attachments.<account>.workers.dev"
  else
    deploy_log="$(mktemp)"
    (
      cd "$WORKER_DIR"
      printf '%s' "$SUPABASE_ANON_KEY" | npx wrangler secret put SUPABASE_ANON_KEY
      # Stream logs and keep a copy so we can parse the workers.dev URL.
      npx wrangler deploy | tee "$deploy_log"
    )
    WORKER_URL="$(sed -n 's/.*\(https:\/\/[^[:space:]]*workers\.dev\).*/\1/p' "$deploy_log" | head -1 || true)"
    rm -f "$deploy_log"
    if [[ -z "$WORKER_URL" ]]; then
      info "Could not parse worker URL from deploy output; check Cloudflare dashboard."
    fi
  fi

  write_env_staging

  green "Attachments worker deployed${WORKER_URL:+ → ${WORKER_URL}}"
fi

if [[ "${DRY_RUN:-}" != "1" && -f "$ROOT/web/.env.staging" ]]; then
  info "Kotlin: merge staging keys into gitignored local.properties"
  node "$ROOT/scripts/ops/write-kotlin-staging-properties.mjs"
fi

print_manual_steps

green "Staging bootstrap complete (Firebase remains production default)."
