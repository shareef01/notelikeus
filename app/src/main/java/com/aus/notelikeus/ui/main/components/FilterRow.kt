package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.ui.theme.*

@Composable
fun FilterRow(
    selectedColor: Int?,
    onColorSelect: (Int?) -> Unit,
    allLabels: List<Label>,
    selectedLabelId: Long?,
    onLabelSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Colors
            val colors = listOf(
                BackgroundLight, NoteRedLight, NoteOrangeLight, NoteYellowLight,
                NoteGreenLight, NoteTealLight, NoteBlueLight, NoteDarkBlueLight,
                NotePurpleLight, NotePinkLight, NoteBrownLight, NoteGrayLight
            )
            
            item {
                FilterChip(
                    selected = selectedColor == null,
                    onClick = { onColorSelect(null) },
                    label = { Text("All Colors") }
                )
            }

            items(colors) { color ->
                val colorArgb = color.toArgb()
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (selectedColor == colorArgb) 2.dp else 1.dp,
                            color = if (selectedColor == colorArgb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        )
                        .clickable { onColorSelect(if (selectedColor == colorArgb) null else colorArgb) },
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedColor == colorArgb) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (color == BackgroundLight) Color.Black else Color.White
                        )
                    }
                }
            }
            
            item { Spacer(modifier = Modifier.width(16.dp)) }

            // Labels
            item {
                FilterChip(
                    selected = selectedLabelId == null,
                    onClick = { onLabelSelect(null) },
                    label = { Text("All Labels") }
                )
            }

            items(allLabels) { label ->
                FilterChip(
                    selected = selectedLabelId == label.id,
                    onClick = { onLabelSelect(if (selectedLabelId == label.id) null else label.id) },
                    label = { Text(label.name) }
                )
            }
        }
    }
}
