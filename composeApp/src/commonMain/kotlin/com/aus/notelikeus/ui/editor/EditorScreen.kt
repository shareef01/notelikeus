package com.aus.notelikeus.ui.editor

import androidx.compose.animation.*
import com.aus.notelikeus.util.PlatformBackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import org.jetbrains.compose.resources.stringResource
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.main.UndoAction
import com.aus.notelikeus.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.aus.notelikeus.ui.editor.components.ChecklistUI
import com.aus.notelikeus.ui.editor.components.EditorBottomBar
import com.aus.notelikeus.ui.editor.components.EditorBottomSheet
import com.aus.notelikeus.ui.editor.components.RichTextToolbar
import com.aus.notelikeus.ui.theme.getContentColor
import com.aus.notelikeus.ui.theme.isNoteColorDarkTheme
import com.aus.notelikeus.ui.theme.noteColorForTheme
import com.aus.notelikeus.ui.theme.noteColorsForTheme
import com.aus.notelikeus.util.DateUtils
import com.aus.notelikeus.ui.theme.AppType
import com.aus.notelikeus.ui.theme.NoteEmphasis
import com.aus.notelikeus.ui.components.AppSnackbar
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.ui.theme.Size
import com.aus.notelikeus.ui.components.ConfirmDialog
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.semantics.Role

private val EditorHorizontalPadding = Spacing.xl
private val EditorVerticalPadding = Spacing.xl
private val EditorBodyMinHeight = 280.dp

/** Readable measure for large screens; content centers inside the note-colored surface. */
private val EditorContentMaxWidth = Size.readingMeasure

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalSharedTransitionApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onStageUndo: (Note, UndoAction, String) -> Unit,
    isExpanded: Boolean = false
) {
    val state by viewModel.state.collectAsState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(Res.string.action_undo)
    val isDarkPalette = isNoteColorDarkTheme()
    val displayColorArgb = noteColorForTheme(state.color, isDarkPalette)
    val noteColor = if (displayColorArgb == 0) {
        MaterialTheme.colorScheme.surface
    } else {
        Color(displayColorArgb.toLong() and 0xffffffffL)
    }
    val contentColor = if (displayColorArgb == 0) {
        MaterialTheme.colorScheme.onSurface
    } else {
        noteColor.getContentColor(fallback = MaterialTheme.colorScheme.onSurface)
    }
    var showBottomSheet by remember { mutableStateOf(false) }
    var showReminderDialog by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    val bodyFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val editorTapInteraction = remember { MutableInteractionSource() }

    // Leaving the editor pops this destination, which clears the ViewModel and cancels the scope
    // saveNote() would have launched the write into. Await it first so navigating away cannot
    // discard the edit, and close regardless of the outcome so a failed write cannot trap the
    // user on the screen. The screen is gone before a snackbar could be read, so the failure goes
    // to the log — it is the only record that the edit was not stored.
    fun saveThenLeave() {
        scope.launch {
            runCatching { viewModel.saveNoteAndAwait() }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        AppLog.warn("EditorScreen", "Save on leave failed", error)
                    }
                }
            onBack()
        }
    }

    PlatformBackHandler { saveThenLeave() }

    LaunchedEffect(state.isNoteLoaded, state.id, state.checklist.isEmpty()) {
        if (state.isNoteLoaded && state.id == null && state.checklist.isEmpty()) {
            // Wait a frame so the body FocusRequester is attached.
            kotlinx.coroutines.delay(48)
            bodyFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val reminderSetMsg = stringResource(Res.string.reminder_set_confirmation)
    val reminderSetApproxMsg = stringResource(Res.string.reminder_set_confirmation_approximate)
    val reminderPermissionDeniedMsg = stringResource(Res.string.reminder_permission_denied)
    val reminderMustBeFutureMsg = stringResource(Res.string.reminder_must_be_future)
    val noteArchivedMsg = stringResource(Res.string.note_archived)
    val noteTrashedMsg = stringResource(Res.string.note_trashed)
    val noteSaveFailedMsg = stringResource(Res.string.note_save_failed)
    val noteDeleteFailedMsg = stringResource(Res.string.note_delete_failed)
    val reminderRemovedMsg = stringResource(Res.string.reminder_removed_confirmation)

    fun scheduleReminderIfAllowed(millis: Long) {
        viewModel.setReminder(millis)
        showReminderDialog = false
        scope.launch {
            snackbarHostState.showSnackbar(reminderSetMsg)
        }
    }

    fun confirmReminder(millis: Long) {
        if (millis <= DateUtils.currentTimeMillis()) {
            scope.launch {
                snackbarHostState.showSnackbar(reminderMustBeFutureMsg)
            }
            return
        }
        scheduleReminderIfAllowed(millis)
    }

    LaunchedEffect(state.saveFailed) {
        if (!state.saveFailed) return@LaunchedEffect
        snackbarHostState.showSnackbar(noteSaveFailedMsg)
        viewModel.clearSaveFailure()
    }

    val notePinnedMsg = stringResource(Res.string.note_pinned)
    val noteUnpinnedMsg = stringResource(Res.string.note_unpinned)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = noteColor,
        contentColor = contentColor
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            containerColor = Color.Transparent,
            contentColor = contentColor,
            snackbarHost = { AppSnackbar(hostState = snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = contentColor,
                        navigationIconContentColor = contentColor,
                        actionIconContentColor = contentColor
                    ),
                    title = {},
                    navigationIcon = {
                        if (!isExpanded) {
                            IconButton(onClick = { saveThenLeave() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(Res.string.cd_back),
                                    modifier = Modifier.size(Size.iconLarge)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showReminderDialog = true }) {
                            Icon(
                                if (state.reminderTimestamp != null) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                                contentDescription = stringResource(Res.string.set_reminder),
                                modifier = Modifier.size(Size.iconLarge)
                            )
                        }
                        IconButton(onClick = {
                            val willPin = !state.isPinned
                            viewModel.togglePin()
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (willPin) notePinnedMsg else noteUnpinnedMsg
                                )
                            }
                        }) {
                            Icon(
                                if (state.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = stringResource(Res.string.cd_pin_note),
                                modifier = Modifier.size(Size.iconLarge)
                            )
                        }
                        IconButton(onClick = {
                            val snapshot = state.let {
                                Note(
                                    id = it.id,
                                    title = it.title,
                                    content = it.content,
                                    timestamp = it.timestamp,
                                    color = it.color,
                                    isPinned = it.isPinned,
                                    isArchived = it.isArchived,
                                    isTrashed = it.isTrashed,
                                    labels = it.labels,
                                    checklist = it.checklist
                                )
                            }
                            viewModel.toggleArchive(onArchived = { archivedNote ->
                                onStageUndo(archivedNote, UndoAction.ARCHIVE, noteArchivedMsg)
                                onBack()
                            })
                        }) {
                            Icon(
                                Icons.Default.Archive,
                                contentDescription = stringResource(Res.string.cd_archive_note),
                                modifier = Modifier.size(Size.iconLarge)
                            )
                        }
                    }
                )
            },
            bottomBar = {
                EditorBottomBar(
                    timestamp = state.timestamp,
                    reminderTimestamp = state.reminderTimestamp,
                    onMoreClick = { showBottomSheet = true },
                    contentColor = contentColor
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .clickable(
                        interactionSource = editorTapInteraction,
                        indication = null
                    ) {
                        bodyFocusRequester.requestFocus()
                        keyboardController?.show()
                    }
            ) {
                Column(
                    // Order matters here, and it used to be wrong: `fillMaxSize()` ran before
                    // `widthIn`, so the width was already pinned to the parent by the time the cap
                    // applied and EditorContentMaxWidth never did anything. On a desktop note
                    // window the body ran the full width of the window instead of the intended
                    // reading column. Height still fills; only the width is capped and centred.
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = EditorContentMaxWidth)
                        .align(Alignment.TopCenter)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            horizontal = EditorHorizontalPadding,
                            vertical = EditorVerticalPadding
                        )
                ) {
                    BasicTextField(
                        value = state.title,
                        onValueChange = { viewModel.onTitleChange(it) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = AppType.editorTitle.copy(color = contentColor),
                        cursorBrush = SolidColor(contentColor),
                        decorationBox = { innerTextField ->
                            if (state.title.isEmpty()) {
                                Text(
                                    text = stringResource(Res.string.title_hint),
                                    style = AppType.editorTitle,
                                    color = contentColor.copy(alpha = NoteEmphasis.Secondary)
                                )
                            }
                            innerTextField()
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                    )
                    Spacer(modifier = Modifier.height(Size.iconSmall))

                    // Divider between title and body, matching the web editor's hairline.
                    HorizontalDivider(
                        thickness = Spacing.hairline,
                        color = contentColor.copy(alpha = NoteEmphasis.Decorative)
                    )

                    if (state.labels.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(Spacing.md))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            state.labels.forEach { label ->
                                Text(
                                    text = label.name,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = 0.3.sp
                                    ),
                                    color = contentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(contentColor.copy(alpha = NoteEmphasis.Decorative))
                                        .padding(horizontal = 10.dp, vertical = Spacing.xs)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Size.iconSmall))
                    if (state.checklist.isEmpty()) {
                        RichTextToolbar(
                            onBoldClick = { viewModel.applyBoldToSelection() },
                            onItalicClick = { viewModel.applyItalicToSelection() },
                            onListClick = { viewModel.applyBulletListToSelection() },
                            onChecklistClick = { viewModel.convertContentToChecklist() },
                            onLinkClick = { showLinkDialog = true },
                            contentColor = contentColor,
                            // A wash of the note's own content colour, so the toolbar reads as
                            // part of the note rather than a panel floating over it.
                            surfaceColor = contentColor.copy(alpha = NoteEmphasis.Wash),
                            modifier = Modifier
                                .padding(bottom = Spacing.md)
                                .align(Alignment.Start)
                        )
                    }
                    if (state.checklist.isNotEmpty()) {
                        ChecklistUI(
                            items = state.checklist,
                            onUpdate = { id, text, checked ->
                                viewModel.updateChecklistItem(id, text, checked)
                            },
                            onAdd = { viewModel.addChecklistItem() },
                            onRemove = { viewModel.removeChecklistItem(it) },
                            contentColor = contentColor
                        )
                    } else {
                        BasicTextField(
                            value = state.contentValue,
                            onValueChange = { viewModel.onContentValueChange(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = EditorBodyMinHeight)
                                .focusRequester(bodyFocusRequester),
                            textStyle = AppType.editorBody.copy(color = contentColor),
                            cursorBrush = SolidColor(contentColor),
                            decorationBox = { innerTextField ->
                                if (state.content.isEmpty()) {
                                    Text(
                                        text = stringResource(Res.string.note_hint),
                                        style = AppType.editorBody,
                                        color = contentColor.copy(alpha = NoteEmphasis.Secondary)
                                    )
                                }
                                innerTextField()
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(80.dp)) // Leave space for bottom bars
                }
            }
        }

        if (showBottomSheet) {
            EditorBottomSheet(
                selectedColor = state.color,
                onColorSelect = { viewModel.onColorChange(it) },
                allLabels = state.allLabels,
                selectedLabels = state.labels,
                onLabelToggle = { viewModel.toggleLabel(it) },
                onCreateLabel = { viewModel.createLabel(it) },
                onDeleteNote = {
                    scope.launch {
                        // Staging the undo and leaving on a failed write would report a delete
                        // that never happened, and offer to undo it.
                        val snapshot = runCatching { viewModel.trashNoteForDelete() }
                            .getOrElse { error ->
                                if (error is CancellationException) throw error
                                AppLog.warn("EditorScreen", "Trashing the note failed", error)
                                snackbarHostState.showSnackbar(noteDeleteFailedMsg)
                                return@launch
                            }
                        if (snapshot != null) {
                            onStageUndo(snapshot, UndoAction.TRASH, noteTrashedMsg)
                        }
                        onBack()
                    }
                },
                onDismiss = { showBottomSheet = false },
                isChecklist = state.checklist.isNotEmpty(),
                onConvertToChecklist = {
                    viewModel.convertContentToChecklist()
                    showBottomSheet = false
                },
                onConvertToText = {
                    viewModel.convertChecklistToContent()
                    showBottomSheet = false
                }
            )
        }

        if (showReminderDialog) {
            ReminderDialog(
                initialTimestamp = state.reminderTimestamp ?: (DateUtils.currentTimeMillis() + 3600000),
                onConfirm = { confirmReminder(it) },
                onRemove = if (state.reminderTimestamp != null) {
                    {
                        viewModel.clearReminder()
                        showReminderDialog = false
                        scope.launch {
                            snackbarHostState.showSnackbar(reminderRemovedMsg)
                        }
                    }
                } else null,
                onDismiss = { showReminderDialog = false }
            )
        }

        if (showLinkDialog) {
            LinkDialog(
                onConfirm = { url ->
                    viewModel.applyLinkToSelection(url)
                    showLinkDialog = false
                },
                onDismiss = { showLinkDialog = false }
            )
        }
    }
}

@Composable
fun ReminderDialog(
    initialTimestamp: Long,
    onConfirm: (Long) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.set_reminder)) },
        text = {
            Column {
                // initialTimestamp used to be computed by the caller and then ignored entirely,
                // so the dialog took the one fact it needed and threw it away. It is the real
                // reminder exactly when there is one to remove, which is when it is worth saying.
                if (onRemove != null) {
                    Text(
                        text = stringResource(
                            Res.string.reminder_currently_set,
                            DateUtils.formatDateTime(initialTimestamp) + ", " +
                                DateUtils.formatTime(initialTimestamp)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = NoteEmphasis.Secondary
                        ),
                        modifier = Modifier.padding(bottom = Spacing.sm)
                    )
                }
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.reminder_in_one_hour)) },
                    // Transparent, or the rows render on colorScheme.surface inside a
                    // dialog painted surfaceContainerHigh -- a white slab dropped into a
                    // grey card.
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(role = Role.Button) { onConfirm(DateUtils.currentTimeMillis() + 3600000) }
                )
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.reminder_tomorrow_morning)) },
                    // Transparent, or the rows render on colorScheme.surface inside a
                    // dialog painted surfaceContainerHigh -- a white slab dropped into a
                    // grey card.
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(role = Role.Button) { onConfirm(DateUtils.getTomorrowMorning()) }
                )
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.reminder_next_week)) },
                    // Transparent, or the rows render on colorScheme.surface inside a
                    // dialog painted surfaceContainerHigh -- a white slab dropped into a
                    // grey card.
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(role = Role.Button) { onConfirm(DateUtils.getNextWeek()) }
                )
            }
        },
        // No confirm button. Choosing a preset *is* the confirmation -- the "OK" that used to sit
        // here called onDismiss, so it was a second Cancel wearing the label of the opposite
        // action, and tapping it after picking nothing looked like it had done something.
        confirmButton = {
            if (onRemove != null) {
                TextButton(onClick = onRemove) {
                    Text(
                        text = stringResource(Res.string.action_remove),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        }
    )
}

@Composable
fun LinkDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    ConfirmDialog(
        title = stringResource(Res.string.link_dialog_title),
        message = "",
        confirmLabel = stringResource(Res.string.action_ok),
        // A blank URL is not a link. OK used to be tappable anyway, so it closed the dialog and
        // threw the interaction away without saying anything -- and before wrapAsLink learned to
        // handle a collapsed selection, it did that with a real URL typed in too.
        confirmEnabled = url.isNotBlank(),
        onConfirm = { onConfirm(url) },
        onDismiss = onDismiss,
        extraContent = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text(stringResource(Res.string.link_url_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}
