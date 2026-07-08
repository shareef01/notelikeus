package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(
    onDismiss: () -> Unit,
    noteCount: Int,
    isTrueDarkMode: Boolean,
    onTrueDarkModeChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header: Visual Identity
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 16.dp)
            ) {
                Surface(
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "A",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Premium User",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Elite Mastermind Edition",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Section: Appearance
            SettingSectionHeader("Appearance")
            SettingsToggleItem(
                icon = Icons.Default.DarkMode,
                title = "True Dark Mode",
                subtitle = "Optimized for OLED displays",
                checked = isTrueDarkMode,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onTrueDarkModeChange(it)
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Section: Insights
            SettingSectionHeader("Insights")
            Row(modifier = Modifier.fillMaxWidth()) {
                InsightCard(
                    modifier = Modifier.weight(1f),
                    title = "Total Notes",
                    value = noteCount.toString(),
                    icon = Icons.Default.Description
                )
                Spacer(modifier = Modifier.width(16.dp))
                InsightCard(
                    modifier = Modifier.weight(1f),
                    title = "Cloud Sync",
                    value = "Active",
                    icon = Icons.Default.CloudDone
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section: Account
            SettingSectionHeader("Account")
            ListItem(
                headlineContent = { Text("Backup & Export") },
                leadingContent = { Icon(Icons.Default.Backup, contentDescription = null) },
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            )
            ListItem(
                headlineContent = { Text("Premium Subscription") },
                leadingContent = { Icon(Icons.Default.Stars, contentDescription = null) },
                supportingContent = { Text("Managed via Google Play") },
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            )
        }
    }
}

@Composable
fun SettingSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge.copy(
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        ),
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun InsightCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: ImageVector
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = value, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            Text(text = title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
