# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Reporting a Vulnerability

If you discover a potential security issue in Notelikeus, please report it responsibly:

- **Maintainer:** [@shareef01](https://github.com/shareef01)
- Please open a private security advisory on GitHub or contact the maintainer directly.
- Include detailed steps to reproduce the issue and any relevant platform/version information.

Please do not disclose security vulnerabilities publicly until they have been investigated and addressed.

## Backend security model

Cloud data is protected by Supabase Auth, PostgreSQL row-level security, RPC authorization (callers cannot pass arbitrary owner IDs), and mutation guards that block direct writes of revision/owner fields. Attachment blobs are authorized by a Cloudflare Worker that verifies the Supabase JWT and derives object keys from the authenticated user. Client apps only ship a public anon/publishable key; they must never contain a `service_role` key.

