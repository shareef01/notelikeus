package com.aus.notelikeus.data.attachments

/**
 * Path-containment helpers for `file:` attachment locations.
 *
 * Callers must pass *already canonical* paths (JVM: `File.canonicalFile.path`). This module only
 * decides whether one canonical path is a strict child of another — it does not touch the
 * filesystem — so Android and Desktop can share the rule while each platform owns
 * canonicalize / symlink resolution.
 *
 * A similarly-prefixed sibling such as `…/attachments-evil` must not match `…/attachments`.
 */
fun isStrictChildPath(
    rootCanonical: String,
    candidateCanonical: String,
    ignoreCase: Boolean = false,
): Boolean {
    if (rootCanonical.isEmpty() || candidateCanonical.isEmpty()) return false
    if (rootCanonical.indexOf('\u0000') >= 0 || candidateCanonical.indexOf('\u0000') >= 0) {
        return false
    }
    val root = normalizePathSeparators(rootCanonical).trimEnd('/')
    val candidate = normalizePathSeparators(candidateCanonical)
    if (root.isEmpty() || candidate.isEmpty()) return false
    // The attachment root itself is not a readable/deletable attachment file.
    if (root.equals(candidate, ignoreCase = ignoreCase)) return false
    val prefix = "$root/"
    return candidate.startsWith(prefix, ignoreCase = ignoreCase) && candidate.length > prefix.length
}

fun normalizePathSeparators(path: String): String = path.replace('\\', '/')
