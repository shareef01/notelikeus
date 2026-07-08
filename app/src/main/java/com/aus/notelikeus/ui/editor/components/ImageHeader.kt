package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun ImageHeader(
    uri: String,
    modifier: Modifier = Modifier
) {
    AsyncImage(
        model = uri,
        contentDescription = null,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .padding(bottom = 16.dp)
            .clip(MaterialTheme.shapes.medium),
        contentScale = ContentScale.Crop
    )
}
