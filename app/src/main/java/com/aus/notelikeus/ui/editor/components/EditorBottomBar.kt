package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun EditorBottomBar(
    timestamp: Long,
    onAddAttachment: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    
    BottomAppBar(
        modifier = modifier.height(48.dp),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        IconButton(onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onAddAttachment()
        }) {
            Icon(Icons.Default.AddBox, contentDescription = "Add attachment")
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Text(
            text = "Edited " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.weight(1f))

        IconButton(onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onMoreClick()
        }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options")
        }
    }
}
