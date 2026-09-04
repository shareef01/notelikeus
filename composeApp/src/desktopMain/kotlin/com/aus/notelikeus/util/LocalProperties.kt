package com.aus.notelikeus.util

import java.io.File
import java.util.Properties

private const val MAX_PARENT_LOOKUPS = 6

/**
 * Reads a single key from the nearest gitignored `local.properties`, walking up from [startDir].
 *
 * Desktop `./gradlew run` starts in `composeApp/`; a packaged app starts wherever the launcher
 * puts it. A fixed relative path would only work in one of those.
 */
internal fun readLocalProperty(
    key: String,
    startDir: File = File(".").absoluteFile,
): String? {
    var dir: File? = startDir
    repeat(MAX_PARENT_LOOKUPS) {
        val candidate = File(dir, "local.properties")
        if (candidate.isFile) {
            return runCatching {
                Properties()
                    .apply { candidate.inputStream().use { load(it) } }
                    .getProperty(key)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
        dir = dir?.parentFile ?: return null
    }
    return null
}
