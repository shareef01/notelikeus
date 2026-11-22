# Notes for Claude

## Commit attribution

Commits in this repository are authored by shareef01 only. Do not add `Co-Authored-By`,
`Claude-Session` or other AI attribution trailers to commit messages, and do not author commits
as Claude: GitHub counts commit authors and co-authors in the repository's Contributors list.

## Rust build practice (`desktop-rust`)

- For routine edits, prefer `cargo fmt --check` and a targeted `cargo check -p <crate>` from the `desktop-rust` workspace root; `cargo check` does not link or launch the app. Run focused tests when behavior changes.
- Use full workspace builds or tests for cross-crate, feature, build-script, platform integration, or release validation. Reserve `--all-features` for changes that need feature-interaction coverage, and do not repeat an unchanged full build/test.
- Before expensive compilation, check C: free space with `Get-Volume -DriveLetter C`. Reuse the workspace `target` cache between sessions.
- Prefer the active stable MSVC toolchain for Windows development. GNU and MSVC builds share `target`; if GNU is needed, set a separate user-level `CARGO_TARGET_DIR` outside version-controlled configuration.
- Do not run `cargo clean` or remove release executables, dependency caches, or configuration without explicit user authorization.
