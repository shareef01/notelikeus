package com.aus.notelikeus.util

actual object AppConfig {
    /**
     * A packaged build has no debug flag to read, so this keys off the run environment instead of
     * being hardcoded: `NOTELIKEUS_DEBUG=1`, or the presence of a JDWP agent (how the IDE launches
     * a debug run). An installed MSI has neither, so it reports false the way it should.
     */
    actual val isDebug: Boolean = System.getenv("NOTELIKEUS_DEBUG") == "1" ||
        java.lang.management.ManagementFactory.getRuntimeMXBean()
            .inputArguments
            .any { it.startsWith("-agentlib:jdwp") }

    actual val versionName: String = "1.0.0"

    // App lock needs Windows Hello via JNA — not implemented yet.
    actual val supportsAppLock: Boolean = false

    // Cloud sync is implemented via OAuth 2.0 loopback flow + Firestore REST.
    actual val supportsCloudSync: Boolean = true
}
