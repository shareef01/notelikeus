#!/usr/bin/env bash
# Bootstrap a Notelikeus *staging* stack (Supabase + R2 attachments worker).
# Does NOT enable production cutover flags. Firebase remains the live backend.
#
# Required environment variables:
#   SUPABASE_ACCESS_TOKEN   — personal access token (Account → Access Tokens)
#   SUPABASE_PROJECT_REF    — staging project ref (subdomain before .supabase.co)
#   SUPABASE_ANON_KEY       — Project Settings → API → anon public key
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

print_manual_steps() {
  cat <<EOF

Manual dashboard steps (agent cannot complete these via CLI):

Supabase Auth → Providers → Google:
  1. Enable Google provider.
  2. Client ID: same Web OAuth client as Firebase (VITE_FIREBASE_GOOGLE_CLIENT_ID).
  3. Client secret: from Google Cloud Console for that OAuth client.
  4. Auth → URL configuration → Redirect URLs, add:
       ${STAGING_WEB_ORIGIN}/**
       ${STAGING_WEB_ORIGIN}

Supabase Auth → URL configuration:
  - Site URL: ${STAGING_WEB_ORIGIN}

Google Cloud Console → OAuth client → Authorized redirect URIs, add:
  - https://${SUPABASE_PROJECT_REF}.supabase.co/auth/v1/callback

Smoke test (after copying web/.env.staging → web/.env):
  cd web && npm run dev
  - Sign in with Google
  - Create a note, add an image attachment, confirm sync

Migration rehearsal (test Firebase account only):
  node scripts/ops/export-firestore-user.mjs --input dump.json --out backup.json
  - Sign in on staging, use in-app backup import

Do NOT set VITE_ALLOW_SUPABASE_PRODUCTION until owner authorizes production cutover.
EOF
}

if [[ "${SKIP_SUPABASE:-}" != "1" ]]; then
  require_env SUPABASE_ACCESS_TOKEN
  require_env SUPABASE_PROJECT_REF
  require_env SUPABASE_ANON_KEY
fi

if [[ "${SKIP_CLOUDFLARE:-}" != "1" ]]; then
  require_env CLOUDFLARE_ACCOUNT_ID
  if [[ -z "${CLOUDFLARE_API_TOKEN:-}" ]] && ! npx wrangler whoami >/dev/null 2>&1; then
    red "Set CLOUDFLARE_API_TOKEN or run: npx wrangler login"
    exit 1
  fi
fi

export SUPABASE_ACCESS_TOKEN

if [[ "${SKIP_SUPABASE:-}" != "1" ]]; then
  info "Supabase: authenticate and link staging project ${SUPABASE_PROJECT_REF}"
  run npx supabase login --token "$SUPABASE_ACCESS_TOKEN"

  link_args=(link --project-ref "$SUPABASE_PROJECT_REF")
  if [[ -n "${SUPABASE_DB_PASSWORD:-}" ]]; then
    link_args+=(--password "$SUPABASE_DB_PASSWORD")
  fi
  run npx supabase "${link_args[@]}"

  info "Supabase: push migrations from supabase/migrations/"
  run npx supabase db push

  green "Supabase staging schema applied at ${SUPABASE_URL}"
fi

WORKER_URL=""

if [[ "${SKIP_CLOUDFLARE:-}" != "1" ]]; then
  info "Cloudflare: ensure R2 bucket ${R2_BUCKET}"
  run npx wrangler r2 bucket create "$R2_BUCKET" 2>/dev/null || info "R2 bucket may already exist (continuing)"

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
EOF
  fi

  info "Cloudflare: deploy attachments worker"
  if [[ "${DRY_RUN:-}" == "1" ]]; then
    info "[dry-run] wrangler secret put SUPABASE_ANON_KEY (in ${WORKER_DIR})"
    info "[dry-run] npx wrangler deploy (in ${WORKER_DIR})"
    WORKER_URL="https://notelikeus-attachments.<account>.workers.dev"
  else
    (
      cd "$WORKER_DIR"
      printf '%s' "$SUPABASE_ANON_KEY" | npx wrangler secret put SUPABASE_ANON_KEY
      deploy_out="$(npx wrangler deploy 2>&1)"
      echo "$deploy_out"
      WORKER_URL="$(echo "$deploy_out" | sed -n 's/.*https:\/\/[^ ]*workers\.dev.*/\0/p' | head -1 | tr -d '[:space:]')"
      if [[ -z "$WORKER_URL" ]]; then
        WORKER_URL="$(npx wrangler deployments list 2>/dev/null | head -5 || true)"
        info "Could not parse worker URL from deploy output; check Cloudflare dashboard."
      fi
    )
  fi

  green "Attachments worker deployed${WORKER_URL:+ → ${WORKER_URL}}"
fi

if [[ "${SKIP_SUPABASE:-}" != "1" && "${DRY_RUN:-}" != "1" ]]; then
  cat > "$ROOT/web/.env.staging" <<EOF
# Staging — copy to web/.env for local smoke tests. NOT for production deploy.
VITE_REMOTE_BACKEND=supabase
VITE_SUPABASE_URL=${SUPABASE_URL}
VITE_SUPABASE_ANON_KEY=${SUPABASE_ANON_KEY}
VITE_ATTACHMENTS_WORKER_URL=${WORKER_URL:-http://127.0.0.1:8787}

# Keep Firebase vars if you need side-by-side comparison (from web/.env.example):
# VITE_FIREBASE_API_KEY=...
# VITE_FIREBASE_GOOGLE_CLIENT_ID=...  (required for Supabase Google OAuth locally)
EOF
  green "Wrote web/.env.staging (copy to web/.env)"
fi

print_manual_steps

green "Staging bootstrap complete (Firebase remains production default)."
