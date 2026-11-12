package com.aus.notelikeus.ui.labels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.hasLabelNamed
import com.aus.notelikeus.ui.components.NotesEmptyState
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.ui.theme.Elevation

/**
 * Elite Label Management Screen
 * Geometric Discipline: Spacing.lg corner radius and standard grid spacing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelsScreen(
    viewModel: LabelsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var labelToEdit by remember { mutableStateOf<Label?>(null) }
    var labelToDelete by remember { mutableStateOf<Label?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        stringResource(Res.string.edit_labels),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                windowInsets = WindowInsets.statusBars
            )
        },
        floatingActionButton = {
            if (state.labels.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = Elevation.card,
                        pressedElevation = Elevation.dragging
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.create_new_label))
                }
            }
        }
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (state.loadFailed) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(Res.string.labels_load_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Spacing.xxxl)
                )
                Spacer(modifier = Modifier.height(Spacing.md))
                OutlinedButton(onClick = { viewModel.loadLabels() }) {
                    Text(stringResource(Res.string.action_retry))
                }
            }
        } else if (state.labels.isEmpty()) {
            NotesEmptyState(
                message = stringResource(Res.string.empty_labels_hint),
                subtitle = stringResource(Res.string.empty_labels_subtitle),
                icon = Icons.AutoMirrored.Filled.Label,
                showCreateButton = true,
                createButtonLabel = stringResource(Res.string.create_new_label),
                onCreateClick = { showCreateDialog = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(
                    top = Spacing.sm,
                    start = Spacing.lg,
                    end = Spacing.lg,
                    bottom = Spacing.snackbarAboveFab
                )
            ) {
                items(
                    items = state.labels,
                    key = { it.id ?: it.name }
                ) { label ->
                    LabelListItem(
                        label = label,
                        noteCount = label.id?.let { state.noteCounts[it] } ?: 0,
                        onEditClick = { labelToEdit = label },
                        onDeleteClick = { labelToDelete = label }
                    )
                    if (label != state.labels.last()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        LabelEditDialog(
            title = stringResource(Res.string.create_new_label),
            isTaken = { state.labels.hasLabelNamed(it) },
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                viewModel.createLabel(name)
                showCreateDialog = false
            }
        )
    }

    labelToEdit?.let { label ->
        LabelEditDialog(
            title = stringResource(Res.string.rename_label),
            initialName = label.name,
            isTaken = { state.labels.hasLabelNamed(it, exceptId = label.id) },
            onDismiss = { labelToEdit = null },
            onConfirm = { name ->
                viewModel.updateLabel(label, name)
                labelToEdit = null
            }
        )
    }

    labelToDelete?.let { label ->
        DeleteLabelDialog(
            label = label,
            noteCount = label.id?.let { state.noteCounts[it] } ?: 0,
            onConfirm = {
                viewModel.deleteLabel(label)
                labelToDelete = null
            },
            onDismiss = { labelToDelete = null },
        )
    }
}

/** Says how many notes lose the label, so deleting one in use is not a surprise. */
@Composable
private fun DeleteLabelDialog(
    label: Label,
    noteCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(Res.string.delete_label_title)) },
        text = {
            Text(
                if (noteCount > 0) {
                    pluralStringResource(Res.plurals.delete_label_message_count, noteCount, label.name, noteCount)
                } else {
                    stringResource(Res.string.delete_label_message)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.action_delete), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

@Composable
private fun LabelListItem(
    label: Label,
    noteCount: Int,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    ListItem(
        headlineContent = { 
            Text(
                label.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
            ) 
        },
        supportingContent = {
            Text(
                if (noteCount > 0) {
                    pluralStringResource(Res.plurals.label_note_count, noteCount, noteCount)
                } else {
                    stringResource(Res.string.label_no_notes)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { 
            Icon(
                Icons.AutoMirrored.Filled.Label, 
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            ) 
        },
        trailingContent = {
            Row {
                IconButton(onClick = onEditClick) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(Res.string.rename_label), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDeleteClick) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.cd_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(Res.string.rename_label), onClick = onEditClick)
    )
}

@Composable
private fun LabelEditDialog(
    title: String,
    initialName: String = "",
    /** Whether a name belongs to another label already; the dialog says so and won't save it. */
    isTaken: (String) -> Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    val taken = name.isNotBlank() && name.trim() != initialName.trim() && isTaken(name)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(title)
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.label_name_hint)) },
                singleLine = true,
                isError = taken,
                supportingText = if (taken) {
                    { Text(stringResource(Res.string.label_name_taken)) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank() && name.trim() != initialName && !taken
            ) {
                Text(stringResource(Res.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        }
    )
}
