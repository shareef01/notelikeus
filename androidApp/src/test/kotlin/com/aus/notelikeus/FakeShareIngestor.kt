package com.aus.notelikeus

import android.net.Uri
import com.aus.notelikeus.ui.navigation.ExternalImageIngestor
import com.aus.notelikeus.ui.navigation.IngestionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Deterministic stand-in for [com.aus.notelikeus.ui.navigation.DefaultExternalImageIngestor] in the
 * Robolectric lifecycle suite.
 *
 * Robolectric's ContentResolver refuses to serve `content://` authorities that are not declared in a
 * manifest, so an Activity-level test cannot copy through a registered provider. What this suite
 * needs to own is the *Activity/ViewModel* contract around an import — when a payload is accepted,
 * when a duplicate is refused, what survives recreation — not the byte-level ingestion rules, which
 * `ExternalImageIngestorTest` already pins with a mocked resolver and the instrumentation suite
 * exercises against a real provider.
 *
 * The outcomes it returns deliberately mirror the production policy (image types only, non-content
 * schemes refused, failures surfaced as `Failure`, never an exception), so the Activity is driven by
 * the same shapes it would see in production.
 */
class FakeShareIngestor(
    private val dispatcher: CoroutineDispatcher,
) : ExternalImageIngestor {

    /** Number of times [ingest] was called; lets a failing assertion name the stage it stalled at. */
    var callCount: Int = 0
        private set

    /** The most recent URI handed to [ingest], or null when it was never called. */
    var lastUri: Uri? = null
        private set

    override suspend fun ingest(uri: Uri, declaredMimeType: String?): IngestionResult {
        callCount++
        lastUri = uri
        return withContext(dispatcher) {
            if (uri.scheme != "content") {
                return@withContext IngestionResult.Failure("Unsupported scheme: ${uri.scheme}")
            }
            when (uri.lastPathSegment) {
                SEGMENT_NON_IMAGE -> IngestionResult.Failure("Provider type is not an image")
                SEGMENT_SECURITY -> IngestionResult.Failure("SecurityException")
                SEGMENT_EMPTY -> IngestionResult.Failure("Stream empty or exceeded limit")
                // The real ingestor resolves through ContentResolver.openInputStream, which fails for
                // an authority nothing serves; the fake must not be more forgiving than that.
                SEGMENT_UNKNOWN_AUTHORITY -> IngestionResult.Failure("FileNotFoundException")
                else -> IngestionResult.Success(bytes = VALID_BYTES, mimeType = IMAGE_MIME)
            }
        }
    }

    companion object {
        const val AUTHORITY = "com.aus.notelikeus.test.share"
        const val SEGMENT_VALID = "valid_image.png"
        const val SEGMENT_NON_IMAGE = "non_image"
        const val SEGMENT_SECURITY = "security_exception"
        const val SEGMENT_EMPTY = "empty"
        const val SEGMENT_UNKNOWN_AUTHORITY = "unknown_authority"

        const val IMAGE_MIME = "image/png"

        val VALID_BYTES = ByteArray(16) { index -> (index + 1).toByte() }
    }
}
