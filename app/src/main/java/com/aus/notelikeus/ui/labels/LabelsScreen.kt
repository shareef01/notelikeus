package com.aus.notelikeus.ui.labels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.R
import com.aus.notelikeus.domain.model.Label

/**
 * Elite Label Management Screen
 * Inline create + tap-to-rename parity with the web PWA.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelsScreen(
    viewModel: LabelsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var newLabelName by remember { mutableStateOf("") }
    var editingLabelKey by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var labelToDelete by remember { mutableStateOf<Label?>(null) }

    fun labelKey(label: Label): String = label.id?.toString() ?: label.name

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.edit_labels),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                windowInsets = WindowInsets.statusBars
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                top = 8.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = 24.dp
            )
        ) {
            item {
                LabelCreateRow(
                    value = newLabelName,
                    onValueChange = { newLabelName = it },
                    onCreate = {
                        val trimmed = newLabelName.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.createLabel(trimmed)
                            newLabelName = ""
                        }
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )
            }

            if (state.labels.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Label,
                            contentDescription = null,
                            modifier = Modifier
                                .size(72.dp)
                                .alpha(0.35f),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.empty_labels_hint),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.empty_labels_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                items(
                    items = state.labels,
                    key = { labelKey(it) }
                ) { label ->
                    val isEditing = editingLabelKey == labelKey(label)
                    if (isEditing) {
                        LabelInlineEditRow(
                            value = editName,
                            onValueChange = { editName = it },
                            onCommit = {
                                viewModel.updateLabel(label, editName)
                                editingLabelKey = null
                            },
                            onCancel = { editingLabelKey = null }
                        )
                    } else {
                        LabelListItem(
                            label = label,
                            onEditClick = {
                                editingLabelKey = labelKey(label)
                                editName = label.name
                            },
                            onDeleteClick = { labelToDelete = label }
                        )
                    }
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

    labelToDelete?.let { label ->
        AlertDialog(
            onDismissRequest = { labelToDelete = null },
            shape = MaterialTheme.shapes.large,
            title = {
                Text(
                    stringResource(R.string.delete_label_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = { Text(stringResource(R.string.delete_label_message, label.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteLabel(label)
                        labelToDelete = null
                    }
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { labelToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun LabelCreateRow(
    value: String,
    onValueChange: (String) -> Unit,
    onCreate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(stringResource(R.string.create_new_label))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCreate() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f)
        )
        if (value.trim().isNotEmpty()) {
            TextButton(onClick = onCreate) {
                Text(
                    stringResource(R.string.action_create),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun LabelInlineEditRow(
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Label,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            ),
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun LabelListItem(
    label: Label,
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
        leadingContent = {
            Icon(
                Icons.Default.Label,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
        },
        trailingContent = {
            IconButton(onClick = onDeleteClick) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cd_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEditClick)
    )
}
