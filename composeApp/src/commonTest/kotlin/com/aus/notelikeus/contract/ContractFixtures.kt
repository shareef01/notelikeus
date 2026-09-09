package com.aus.notelikeus.contract

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Reads a fixture from the repository's `contracts/` directory.
 *
 * The directory is found by walking up from the test's working directory, which differs between
 * the Android unit-test run and the desktop JVM run, so neither has to hard-code a
 * module-relative path.
 */
object ContractFixtures {

    private val root: Path by lazy { locateContractsRoot() }

    private fun locateContractsRoot(): Path {
        var dir: Path? = FileSystem.SYSTEM.canonicalize(".".toPath())
        repeat(12) {
            val current = dir ?: return@repeat
            val candidate = current / "contracts"
            if (FileSystem.SYSTEM.exists(candidate / "README.md")) return candidate
            dir = current.parent
        }
        throw IllegalStateException(
            "No contracts/ directory within 12 levels of " +
                FileSystem.SYSTEM.canonicalize(".".toPath())
        )
    }

    fun read(relativePath: String): String {
        val path = relativePath.split('/').fold(root) { acc, segment -> acc / segment }
        return FileSystem.SYSTEM.read(path) { readUtf8() }
    }
}
