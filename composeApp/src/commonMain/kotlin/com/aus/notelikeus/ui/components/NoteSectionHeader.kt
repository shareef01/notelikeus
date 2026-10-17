package com.aus.notelikeus.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.ui.theme.AppType
import com.aus.notelikeus.ui.theme.Spacing

@Composable
fun NoteSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title.uppercase(),
        style = AppType.chromeLabel,
        // Full strength: the muted role is tuned to clear 4.5:1 and no more, so fading it
        // (it was 75%) put these headers at about 3:1.
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.md, bottom = Spacing.sm)
    )
}
