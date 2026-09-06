#!/usr/bin/env python3
"""Rewrite commit authors/messages so only shareef01 remains as a human contributor.

Strips Cursor/Claude co-author trailers and remaps their author/committer identities.
Does not touch dependabot commits (GitHub bots are separate from the contributor graph
for most human-facing counts, but leave them as bots).

Requires: pip install git-filter-repo
Run from repo root:
  python scripts/ops/rewrite-sole-contributor.py
Then force-push main (destructive; coordinate first).
"""

from __future__ import annotations

import re
import sys

try:
    import git_filter_repo as fr
except ImportError:
    sys.stderr.write("Install git-filter-repo first: pip install git-filter-repo\n")
    sys.exit(1)

CANON_NAME = b"shareef01"
CANON_EMAIL = b"shareef.q13@gmail.com"

REMAP_EMAILS = {
    b"cursoragent@cursor.com",
    b"noreply@anthropic.com",
}

COAUTHOR_RE = re.compile(
    br"^[ \t]*Co-[Aa]uthored-[Bb]y:[ \t]*(Cursor|Claude|cursoragent).*$",
    re.MULTILINE,
)
CLAUDE_SESSION_RE = re.compile(br"^[ \t]*Claude-Session:.*$", re.MULTILINE)


def fix_identity(name: bytes, email: bytes) -> tuple[bytes, bytes]:
    if email.lower() in REMAP_EMAILS or email.lower().endswith(b"@cursor.com"):
        return CANON_NAME, CANON_EMAIL
    if name in (b"Cursor Agent", b"Cursor", b"Claude", b"Claude Opus 5"):
        return CANON_NAME, CANON_EMAIL
    return name, email


def callback(commit: fr.Commit, _metadata) -> None:
    commit.author_name, commit.author_email = fix_identity(
        commit.author_name, commit.author_email
    )
    commit.committer_name, commit.committer_email = fix_identity(
        commit.committer_name, commit.committer_email
    )
    msg = commit.message
    msg = COAUTHOR_RE.sub(b"", msg)
    msg = CLAUDE_SESSION_RE.sub(b"", msg)
    # Collapse accidental blank runs left by stripped trailers.
    msg = re.sub(br"\n{3,}", b"\n\n", msg)
    commit.message = msg.rstrip() + b"\n"


args = fr.FilteringOptions.parse_args(["--force", "--refs", "refs/heads/main"])
args.repack = False
filter_obj = fr.RepoFilter(args, commit_callback=callback)
filter_obj.run()
