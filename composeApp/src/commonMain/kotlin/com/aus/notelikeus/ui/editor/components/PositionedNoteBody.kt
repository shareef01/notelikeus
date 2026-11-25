package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.aus.notelikeus.domain.model.Attachment
import com.aus.notelikeus.ui.editor.RichTextParser
import com.aus.notelikeus.ui.theme.AppType
import com.aus.notelikeus.ui.theme.Spacing
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.attachment_return_to_strip
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PositionedNoteBody(
    body: String,
    positioned: List<Pair<Int, Attachment>>,
    contentColor: Color,
    loadPreviewBytes: suspend (Attachment) -> ByteArray?,
    onRemove: (Attachment) -> Unit,
    onUnplace: ((Attachment) -> Unit)?,
    revealImageId: String?,
) {
    val rendered = remember(body, contentColor) { RichTextParser.toTransformedText(body, contentColor) }
    val pieces = remember(body, positioned) {
        var start = 0
        positioned.groupBy({ it.first }, { it.second }).map { (end, attachments) ->
            BodyPiece(start, end, attachments).also { start = end }
        } + BodyPiece(start, body.length, emptyList())
    }
    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            pieces.forEach { piece ->
                val from = piece.start + if (piece.start > 0 && body.getOrNull(piece.start) == '\n') 1 else 0
                val end = if (piece.attachments.isEmpty()) piece.end else {
                    from + body.substring(from.coerceAtMost(piece.end), piece.end).trimEnd('\r', '\n').length
                }
                if (from < end) {
                    val mapping = rendered.offsetMapping
                    val transformedStart = mapping.originalToTransformed(from)
                    val transformedEnd = mapping.originalToTransformed(end)
                    Text(
                        rendered.text.subSequence(transformedStart, transformedEnd),
                        style = AppType.editorBody.copy(color = contentColor),
                    )
                }
                if (piece.attachments.isNotEmpty()) {
                    key(piece.attachments.first().id) {
                        val reveal = remember { BringIntoViewRequester() }
                        LaunchedEffect(revealImageId) {
                            if (piece.attachments.any { it.id == revealImageId }) reveal.bringIntoView()
                        }
                        DisableSelection {
                            AttachmentImageStrip(
                                attachments = piece.attachments,
                                contentColor = contentColor,
                                loadPreviewBytes = loadPreviewBytes,
                                onRemove = onRemove,
                                modifier = Modifier.bringIntoViewRequester(reveal),
                                actionText = onUnplace?.let { stringResource(Res.string.attachment_return_to_strip) },
                                onAction = onUnplace,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class BodyPiece(val start: Int, val end: Int, val attachments: List<Attachment>)
