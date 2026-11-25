package com.aus.notelikeus.data.attachments

import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.domain.model.AttachmentPlacement
import okio.ByteString.Companion.encodeUtf8

internal const val MAX_PLACEMENT_INTEGER = 9_007_199_254_740_991L

/** Maps the backup's Unicode scalar offsets to the editor's UTF-16 offsets. */
internal class PlacementBody(body: String) {
    private val digest = body.encodeUtf8().sha256().hex()
    private val boundaries = buildMap<Long, Int> {
        val lines = body.splitToSequence('\n').iterator()
        var line = lines.next()
        var index = 0
        var offset = 0L
        while (true) {
            val next = if (lines.hasNext()) lines.next() else null
            val separator = if (next == null) 0 else 1
            index += line.length + separator
            offset += line.scalarCount() + separator
            if (offset > 0 && !(line.startsWith("• ") && next?.startsWith("• ") == true)) put(offset, index)
            line = next ?: break
        }
    }

    fun matches(placement: AttachmentPlacement): Boolean = indexOf(placement) != null

    fun indexOf(placement: AttachmentPlacement): Int? =
        boundaries[placement.offset]?.takeIf {
            placement.bodySha256 == digest && placement.ordinal in 0..MAX_PLACEMENT_INTEGER
        }

    fun placementAfter(cursor: Int, ordinal: Long = 0): AttachmentPlacement? {
        val entry = boundaries.entries.firstOrNull { it.value > cursor } ?: boundaries.entries.lastOrNull()
        return entry?.let { AttachmentPlacement(digest, it.key, ordinal) }
    }

    fun placementAt(index: Int, ordinal: Long): AttachmentPlacement? =
        boundaries.entries.firstOrNull { it.value == index }?.let { AttachmentPlacement(digest, it.key, ordinal) }
}

private fun String.scalarCount(): Int {
    var count = 0
    var index = 0
    while (index < length) {
        if (this[index].isHighSurrogate() && getOrNull(index + 1)?.isLowSurrogate() == true) index++
        count++
        index++
    }
    return count
}

internal fun rebaseAttachmentPlacements(attachments: List<Attachment>, old: String, new: String): List<Attachment> {
    if (old == new || attachments.none { it.placement != null }) return attachments
    val before = PlacementBody(old)
    val after = PlacementBody(new)
    val prefix = old.commonPrefixWith(new).length
    val suffix = old.commonSuffixWith(new).length.coerceAtMost(minOf(old.length, new.length) - prefix)
    return attachments.map { attachment ->
        val placement = attachment.placement ?: return@map attachment
        val index = before.indexOf(placement) ?: return@map attachment
        val next = when {
            index == old.length && prefix == old.length && !old.endsWith('\n') ->
                new.indexOf('\n', startIndex = index).let { if (it == -1) new.length else it + 1 }
            index <= prefix -> index
            index > old.length - suffix -> new.length - (old.length - index)
            else -> return@map attachment
        }
        // A removed boundary falls back to the strip. Keep its old digest so undo can restore it.
        val moved = after.placementAt(next, placement.ordinal) ?: return@map attachment
        attachment.copy(placement = moved)
    }
}

internal fun positionedAttachments(body: String, attachments: List<Attachment>): List<Pair<Int, Attachment>> {
    if (attachments.none { it.placement != null }) return emptyList()
    val positions = PlacementBody(body)
    return attachments.mapNotNull { attachment ->
        val placement = attachment.placement ?: return@mapNotNull null
        positions.indexOf(placement)?.let { it to attachment }
    }.sortedWith(compareBy({ it.first }, { it.second.placement?.ordinal }, { it.second.id }))
}
