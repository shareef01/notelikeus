# Cloudflare zone controls (F50) — operator checklist

Abuse controls that live on the **Cloudflare zone** (or account) in front of the attachments
Worker cannot be expressed as source in this repository. The Worker already applies an optional
edge rate limiter when `ATTACHMENT_RATE_LIMITER` is bound — see
`workers/attachments/wrangler.toml.example` and `docs/BACKEND_ARCHITECTURE.md`.

This checklist is what to configure **outside** the repo so a deployment is not limited to that
binding alone. It stores no secrets; values are starting points, not product guarantees.

## Already handled in-repo / deploy workflow

| Control | Where |
|---|---|
| Per-IP then per-user request throttling | `ATTACHMENT_RATE_LIMITER` binding (default **120 / 60s** via deploy workflow inputs; `0` disables) |
| Upload size / MIME caps | Worker `src/limits.ts` (enforced after auth) |
| Auth + object-key ownership | Supabase JWT + RPC authorization path |

Confirm the production Worker binding is present after deploy (workflow log / Wrangler dashboard).
Without it, the Worker fails open on throttle and only the limits below still apply.

## Zone / account checklist

Do these in the Cloudflare dashboard (or API) for the zone that fronts the attachments Worker
hostname. Adjust numbers to traffic; the goal is to stop bulk scraping and credential stuffing,
not a note with twenty images syncing once.

### 1. WAF / custom rules (recommended)

- [ ] Block or challenge obviously automated clients (known bad ASNs, empty/`curl`-only UAs if that
      matches your threat model — prefer challenge over hard block while tuning).
- [ ] Rate-limit or challenge high-volume `POST`/`PUT`/`DELETE` to the Worker hostname by IP when
      the Worker binding is off or you want a second layer.
- [ ] Skip or loosen rules for your own Pages origin and known sync clients if false positives
      appear.

### 2. Bot Fight / Bot Management (optional)

- [ ] Enable Bot Fight Mode (free) or Super Bot Fight / Bot Management (paid) on the zone.
- [ ] Prefer “managed challenge” for likely bots rather than silent drop while monitoring.

### 3. R2 bucket access

- [ ] Confirm the R2 bucket used for attachments (`notelikeus-attachments`) has **public access
      disabled** in the Cloudflare Dashboard (Storage → R2 → bucket → Settings). Public R2 buckets
      bypass the Worker entirely: any URL of the form `pub-<hash>.r2.dev/<object-key>` would serve
      attachment bytes to anyone without authentication. This cannot be verified from source control
      — it must be checked in the dashboard after every environment provisioning step.
- [ ] Keep R2 CORS and `ALLOWED_ORIGINS` tight — only Pages / app origins that need attachment PUT.

### 4. Platform / Worker limits

- [ ] Confirm Worker **CPU time** and **subrequest** limits match expected upload concurrency
      (account plan defaults usually suffice; raise only if legitimate syncs hit 503s).

### 5. Observability

- [ ] Watch Worker analytics / logs for sustained 429 spikes (binding working) vs 401/403 floods
      (auth probing — WAF/bot rules help more than raising the rate limit).
- [ ] After changing zone rules, run one signed-in attachment upload and one rejected anonymous
      request to confirm sync still works and abuse paths still fail closed on auth.

## What this does *not* replace

- Row-level security and attachment RPCs in Supabase remain the authorization boundary.
- Rate limiting fails open by design; it is a mitigation, not proof of ownership.
- Turning these on in staging first avoids locking out real clients during tuning.

## Cross-links

- Finding: [`docs/FINDINGS.md`](FINDINGS.md) F50  
- Worker example binding: `workers/attachments/wrangler.toml.example`  
- Pipeline order: [`docs/BACKEND_ARCHITECTURE.md`](BACKEND_ARCHITECTURE.md)  
- Repo automation vs account settings: [`docs/SECURITY_AUTOMATION.md`](SECURITY_AUTOMATION.md)
