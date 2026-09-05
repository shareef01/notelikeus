# Production cutover — rollback plan

**Status: SUPERSEDED — 2026-09-05**

Firebase is no longer a runtime fallback. Rollback to Firebase is not supported in source.
If a new Supabase project is misconfigured, fix or replace that project; do not re-enable Firebase.

See `docs/BACKEND_ARCHITECTURE.md`.

---

The remainder of this file is historical.



## What a cutover actually changes

| Client | Before | After | How it flips |
| --- | --- | --- | --- |
| Web (`notelike.web.app`) | Firebase | Supabase | a build with `VITE_ALLOW_SUPABASE_PRODUCTION=true`, deployed to Firebase Hosting |
| Android release | Firebase | Supabase | `assembleRelease` with `NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION=true` in the build env |
| Android debug | already Supabase | unchanged | `local.properties` |
| Desktop | Firebase | Supabase | `NOTELIKEUS_ALLOW_SUPABASE_PRODUCTION=true` at runtime |

Nothing in the Firebase project is deleted by a cutover. Auth, Firestore, rules and Hosting all
keep working; the clients simply stop talking to them.

## Rollback, per client

**Web — minutes.** Rebuild without `VITE_ALLOW_SUPABASE_PRODUCTION` and redeploy:

```bash
cd web && npm run build && cd .. && npx.cmd firebase deploy --only hosting
```

Or use the Firebase console's Hosting → Release history → Rollback, which restores the previous
bundle without a rebuild. (`firebase hosting:rollback` is not a CLI command; do not plan around it.)

**Desktop — immediate.** Stop setting the environment variable. Nothing is baked in.

**Android — this is the hard one.** A released APK carries its cutover configuration in
`BuildConfig`, so rolling back means shipping *another* release without the env vars and waiting
for users to update. Play rollout can be halted, but installs already out there keep pointing at
Supabase until each user updates. **Budget days, not minutes.**

## Two constraints you cannot fix afterwards

**1. The PWA serves a stale bundle until the user accepts a reload.**
The service worker uses `injectManifest` with `registerType: 'autoUpdate'`, and `skipWaiting()`
fires only when the client posts `SKIP_WAITING` — which happens when the user acts on the "Reload"
toast (`onNeedRefresh` in `main.tsx`). A tab left open keeps running the old bundle indefinitely.

So during both the cutover *and* the rollback there is a window where some clients run the old code
against the new backend. Web-only and self-correcting on reload, but it means "deployed" is not the
same as "in effect".

**2. Data written after the cutover lives only in Supabase.**
There is no dual-write and no reverse sync. Every note created or edited on Supabase after the
switch is invisible to Firestore. Rolling back returns clients to whatever Firestore last held —
those notes are not lost, but they are not *there* either.

Recovering them means exporting from Supabase and importing through the backup path
(`scripts/ops/export-firestore-user.mjs` runs the other direction; the Supabase→backup direction is
not written). **The longer the window, the more there is to reconcile** — which is the real argument
for keeping a cutover window short rather than open-ended.

## Do not delete during the rollback window

- Firebase Auth users, Firestore data, `firestore.rules`, `firestore.indexes.json`
- Firebase Hosting site `notelike` and its release history
- The Firebase web config in `web/.env` — `bootstrap.ts` still initialises Firebase when it is
  present, which is what keeps the Firebase→Supabase migration bridge able to read a legacy session
- `google-services.json`
- Any Firestore-reading code (`web/src/lib/firestore/`, `FirestoreNoteTransport`,
  `DesktopFirestoreTransport`)

Retire these only once the window has closed and no client can still be on Firebase — which, given
Android, means once install telemetry says the old release is gone.

## Suggested sequence

1. Cut over **web only**. It is the fastest to roll back and the easiest to observe.
2. Watch for a few days. Confirm sign-in, sync, attachments and account switching.
3. Only then build and ship the Android release cutover, since that is the leg with the slow rollback.
4. Keep Firebase entirely intact throughout.

## Owner sign-off

- [ ] Rollback plan read
- [ ] Accepts that post-cutover Supabase writes are not reflected in Firestore
- [ ] Accepts that an Android release rollback takes days
