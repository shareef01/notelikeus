# Engineering Audit Archive & History

This directory archives the engineering audits, defect analyses, and verification logs conducted during the development and hardening of Notelikeus.

---

## Audit Provenance & Progression

| Document | Scope & Purpose | Status |
|---|---|:---:|
| **[AUDIT_REPORT.md](AUDIT_REPORT.md)** | Comprehensive production audit (commit `0551a5b6`, Oct 12, 2026). Identified critical findings $F\text{-}01$ through $F\text{-}14$, offline durability merge gaps, and worker isolation boundaries. | **RESOLVED** (SHIPPED v2.4.1) |
| **[REMAINING_WORK.md](REMAINING_WORK.md)** | Post-audit milestone tracking leading up to v2.4.1 release. Tracked PRs #316–#388. | **RESOLVED** (SHIPPED v2.4.1) |
| **[archive/](archive/)** | Detailed historical logs, DeepSeek / Claude review snapshots, iterative project audits (Oct 15–21), and UI/UX benchmark verification logs. | **ARCHIVED** |

---

## Key Security & Architectural Invariants Established

Across the audit iterations documented here, the following core engineering guarantees were verified and permanently incorporated into the codebase and test suites:

1. **Owner-Derived R2 Keys:** Cloudflare Workers authenticate Supabase JWTs and derive object paths from `(owner_id, note_id, attachment_id)`, preventing horizontal privilege escalation.
2. **Deterministic Sequence-Based Concurrency:** Cloud synchronization utilizes PostgreSQL sequence-based optimistic concurrency control rather than wall-clock timestamps, preventing lost edits across desynchronized device clocks.
3. **Local Encryption-at-Rest:** SQLCipher local databases on Android (Keystore-backed) and Windows (DPAPI-backed), paired with WebCrypto AES-GCM on web local storage, protecting confidentiality on device compromise.
4. **Wipe Generation Fencing:** Server-side account wipe generation counters ensure that previously disconnected devices cannot push stale copies over an intentional account wipe.
5. **Cross-Client Contract Fixtures:** Shared JSON contracts in `contracts/` asserted identically by Kotlin Multiplatform and TypeScript test suites.
