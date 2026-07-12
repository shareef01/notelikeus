package com.aus.notelikeus.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.aus.notelikeus.domain.model.ChecklistItem
import com.aus.notelikeus.domain.model.Label
import com.aus.notelikeus.domain.model.Note
import com.aus.notelikeus.ui.main.UndoAction
import kotlinx.coroutines.launch
import com.aus.notelikeus.MainActivity
import com.aus.notelikeus.R
import com.aus.notelikeus.ui.editor.components.ChecklistUI
import com.aus.notelikeus.ui.editor.components.EditorBottomBar
import com.aus.notelikeus.ui.editor.components.EditorBottomSheet
import com.aus.notelikeus.ui.editor.components.RichTextToolbar
import com.aus.notelikeus.ui.theme.getContentColor
import com.aus.notelikeus.ui.theme.EditorBodyStyle
import androidx.compose.ui.text.font.FontWeight
import com.aus.notelikeus.ui.theme.isNoteColorDarkTheme
import com.aus.notelikeus.ui.theme.noteColorsForTheme
import android.text.format.DateFormat
import java.util.Calendar

private val EditorHorizontalPadding = 16.dp
private val EditorVerticalPadding = 16.dp

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class
)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onStageUndo: (Note, UndoAction, String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.action_undo)
    val reminderExactAlarmHint = stringResource(R.string.reminder_exact_alarm_hint)
    val reminderPermissionDenied = stringResource(R.string.reminder_permission_denied)
    val reminderMustBeFuture = stringResource(R.string.reminder_must_be_future)
    val lockedNoteTitle = stringResource(R.string.locked_note)
    val noteNotFoundMessage = stringResource(R.string.note_not_found)
    val notePinnedMessage = stringResource(R.string.note_pinned)
    val noteUnpinnedMessage = stringResource(R.string.note_unpinned)
    val noteUnarchivedMessage = stringResource(R.string.note_unarchived)
    val noteArchivedMessage = stringResource(R.string.note_archived)
    val noteTrashedMessage = stringResource(R.string.note_trashed)
    val noteColor = if (state.color == 0) {
        MaterialTheme.colorScheme.background
    } else {
        Color(state.color)
    }
    val contentColor = if (state.color == 0) {
        MaterialTheme.colorScheme.onBackground
    } else {
        noteColor.getContentColor(fallback = MaterialTheme.colorScheme.onBackground)
    }
    var showBottomSheet by remember { mutableStateOf(false) }
    var showDateTimePicker by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var pendingReminderMillis by remember { mutableStateOf<Long?>(null) }

    fun scheduleReminderIfAllowed(millis: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            if (!alarmManager.canScheduleExactAlarms()) {
                scope.launch {
                    snackbarHostState.showSnackbar(reminderExactAlarmHint)
                }
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            }
        }
        viewModel.setReminder(millis)
        showDateTimePicker = false
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val millis = pendingReminderMillis
        pendingReminderMillis = null
        if (granted && millis != null) {
            scheduleReminderIfAllowed(millis)
        } else if (!granted) {
            scope.launch {
                snackbarHostState.showSnackbar(reminderPermissionDenied)
            }
        }
    }

    fun confirmReminder(millis: Long) {
        if (millis <= System.currentTimeMillis()) {
            scope.launch {
                snackbarHostState.showSnackbar(reminderMustBeFuture)
            }
            return
        }
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingReminderMillis = millis
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            scheduleReminderIfAllowed(millis)
        }
    }

    val isDark = isNoteColorDarkTheme()
    LaunchedEffect(state.isNoteLoaded, state.id) {
        if (state.id == null && state.isNoteLoaded) {
            val defaultColor = noteColorsForTheme(isDark).first().toArgb()
            viewModel.setInitialNoteColor(defaultColor)
        }
    }

    val isLoadingExistingNote = state.id != null && !state.isNoteLoaded && !state.noteNotFound
    val showLockOverlay = state.isNoteLoaded && state.isLocked && !state.isAccessGranted
    val imeVisible = WindowInsets.isImeVisible
    var hasPromptedLockAuth by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, state.isLocked) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.revokeAccessIfLocked()
                hasPromptedLockAuth = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(showLockOverlay) {
        if (showLockOverlay && !hasPromptedLockAuth) {
            hasPromptedLockAuth = true
            (context as MainActivity).showBiometricPrompt(
                title = lockedNoteTitle,
                onSuccess = { viewModel.grantAccess() },
                onError = { onBack() }
            )
        }
    }

    LaunchedEffect(state.noteNotFound) {
        if (state.noteNotFound) {
            snackbarHostState.showSnackbar(noteNotFoundMessage)
            onBack()
        }
    }

    val navigateBack: () -> Unit = {
        scope.launch {
            viewModel.flushPendingSave()
            onBack()
        }
    }

    BackHandler(onBack = navigateBack)

    if (showLockOverlay) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = noteColor
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = contentColor
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        navigationIconContentColor = contentColor,
                        actionIconContentColor = contentColor,
                        titleContentColor = contentColor
                    ),
                    windowInsets = WindowInsets.statusBars
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .navigationBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = stringResource(R.string.locked_note),
                    modifier = Modifier.size(64.dp),
                    tint = contentColor.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.locked_note_message),
                    style = MaterialTheme.typography.titleLarge,
                    color = contentColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.locked_note_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                FilledTonalButton(
                    onClick = {
                        (context as MainActivity).showBiometricPrompt(
                            title = lockedNoteTitle,
                            onSuccess = { viewModel.grantAccess() },
                            onError = { onBack() }
                        )
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = contentColor.copy(alpha = 0.16f),
                        contentColor = contentColor
                    )
                ) {
                    Text(stringResource(R.string.unlock))
                }
                TextButton(onClick = navigateBack) {
                    Text(stringResource(R.string.go_back), color = contentColor)
                }
                }
            }
        }
        return
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 56.dp)
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = MaterialTheme.shapes.medium,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    actionColor = MaterialTheme.colorScheme.inversePrimary
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = contentColor
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        showDateTimePicker = true
                    }) {
                        Icon(
                            if (state.reminderTimestamp != null) Icons.Default.NotificationsActive else Icons.Outlined.Notifications,
                            contentDescription = stringResource(R.string.set_reminder),
                            tint = contentColor
                        )
                    }
                    IconButton(onClick = {
                        val willPin = !state.isPinned
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.togglePin()
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (willPin) notePinnedMessage else noteUnpinnedMessage
                            )
                        }
                    }) {
                        Icon(
                            if (state.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = stringResource(R.string.cd_pin_note),
                            tint = contentColor
                        )
                    }
                    IconButton(onClick = {
                        val wasArchived = state.isArchived
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        if (wasArchived) {
                            viewModel.toggleArchive()
                            scope.launch {
                                snackbarHostState.showSnackbar(noteUnarchivedMessage)
                            }
                        } else {
                            viewModel.toggleArchive { snapshot ->
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = noteArchivedMessage,
                                        actionLabel = undoLabel,
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.undoArchive(snapshot)
                                    }
                                }
                            }
                        }
                    }) {
                        Icon(
                            if (state.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                            contentDescription = stringResource(R.string.cd_archive_note),
                            tint = contentColor
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = noteColor,
                    navigationIconContentColor = contentColor,
                    actionIconContentColor = contentColor,
                    titleContentColor = contentColor
                ),
                windowInsets = WindowInsets.statusBars
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = !imeVisible) {
                EditorBottomBar(
                    timestamp = state.timestamp,
                    reminderTimestamp = state.reminderTimestamp,
                    isSaving = state.isSaving,
                    onMoreClick = { showBottomSheet = true },
                    contentColor = contentColor,
                    modifier = Modifier.background(noteColor)
                )
            }
        },
        containerColor = noteColor,
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        val showFormattingToolbar = state.contentValue.selection.length > 0

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            if (isLoadingExistingNote) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                    color = contentColor,
                    trackColor = contentColor.copy(alpha = 0.18f),
                )
            }

            AnimatedVisibility(
                visible = !isLoadingExistingNote,
                enter = fadeIn(animationSpec = tween(120)),
                exit = fadeOut(animationSpec = tween(80)),
                modifier = Modifier.fillMaxSize(),
            ) {
            Box(modifier = Modifier.fillMaxSize()) {
            if (showBottomSheet) {
                EditorBottomSheet(
                    selectedColor = state.color,
                    onColorSelect = viewModel::onColorChange,
                    allLabels = state.allLabels,
                    selectedLabels = state.labels,
                    onLabelToggle = viewModel::toggleLabel,
                    onCreateLabel = viewModel::createLabel,
                    isLocked = state.isLocked,
                    onLockToggle = viewModel::toggleLock,
                    onDeleteNote = {
                        scope.launch {
                            val snapshot = viewModel.trashNoteForDelete()
                            if (snapshot != null) {
                                onStageUndo(
                                    snapshot,
                                    UndoAction.TRASH,
                                    noteTrashedMessage
                                )
                            }
                            onBack()
                        }
                    },
                    onDismiss = { showBottomSheet = false }
                )
            }

            if (showDateTimePicker) {
                ReminderPickerDialog(
                    initialMillis = state.reminderTimestamp ?: System.currentTimeMillis(),
                    hasExistingReminder = state.reminderTimestamp != null,
                    onConfirm = { millis -> confirmReminder(millis) },
                    onRemove = {
                        viewModel.clearReminder()
                        showDateTimePicker = false
                    },
                    onDismiss = { showDateTimePicker = false }
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

            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                EditorTextContent(
                    title = state.title,
                    contentValue = state.contentValue,
                    content = state.content,
                    checklist = state.checklist,
                    labels = state.labels,
                    contentColor = contentColor,
                    showFormattingToolbar = showFormattingToolbar,
                    onTitleChange = viewModel::onTitleChange,
                    onContentValueChange = viewModel::onContentValueChange,
                    onUpdateChecklistItem = viewModel::updateChecklistItem,
                    onAddChecklistItem = viewModel::addChecklistItem,
                    onRemoveChecklistItem = viewModel::removeChecklistItem,
                    onConvertChecklistToContent = viewModel::convertChecklistToContent,
                    onConvertContentToChecklist = viewModel::convertContentToChecklist,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    focusBodyOnOpen = state.id == null && state.isNoteLoaded,
                )
            }

            AnimatedVisibility(
                visible = showFormattingToolbar,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            ) {
                RichTextToolbar(
                    onBoldClick = viewModel::applyBoldToSelection,
                    onItalicClick = viewModel::applyItalicToSelection,
                    onListClick = viewModel::applyBulletListToSelection,
                    onChecklistClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (state.checklist.isEmpty()) viewModel.convertContentToChecklist()
                    },
                    onLinkClick = { showLinkDialog = true },
                    contentColor = contentColor,
                    surfaceColor = noteColor,
                    modifier = Modifier.animateContentSize()
                )
            }
            }
            }
        }
    }
}

@Composable
private fun EditorTextContent(
    title: String,
    contentValue: TextFieldValue,
    content: String,
    checklist: List<ChecklistItem>,
    labels: List<Label>,
    contentColor: Color,
    showFormattingToolbar: Boolean,
    onTitleChange: (String) -> Unit,
    onContentValueChange: (TextFieldValue) -> Unit,
    onUpdateChecklistItem: (Long, String, Boolean) -> Unit,
    onAddChecklistItem: () -> Unit,
    onRemoveChecklistItem: (Long) -> Unit,
    onConvertChecklistToContent: () -> Unit,
    onConvertContentToChecklist: () -> Unit,
    modifier: Modifier = Modifier,
    focusBodyOnOpen: Boolean = false,
) {
    val titleStyle = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.Bold,
        color = contentColor,
    )
    val bodyStyle = EditorBodyStyle.copy(color = contentColor)
    val placeholderColor = contentColor.copy(alpha = 0.5f)
    val bodyFocusRequester = remember { FocusRequester() }

    LaunchedEffect(focusBodyOnOpen) {
        if (focusBodyOnOpen && checklist.isEmpty()) {
            bodyFocusRequester.requestFocus()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = EditorHorizontalPadding,
                end = EditorHorizontalPadding,
                top = EditorVerticalPadding,
                bottom = EditorVerticalPadding
            )
    ) {
        BasicTextField(
            value = title,
            onValueChange = onTitleChange,
            textStyle = titleStyle,
            cursorBrush = SolidColor(contentColor),
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (title.isEmpty()) {
                        Text(
                            text = stringResource(R.string.title_hint),
                            style = titleStyle,
                            color = placeholderColor,
                        )
                    }
                    innerTextField()
                }
            }
        )

        if (labels.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                labels.forEach { label ->
                    SuggestionChip(
                        onClick = {},
                        enabled = false,
                        label = {
                            Text(
                                label.name,
                                color = contentColor,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = contentColor.copy(alpha = 0.12f),
                            labelColor = contentColor,
                        ),
                        border = null,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (checklist.isNotEmpty()) {
            ChecklistUI(
                items = checklist,
                onUpdate = onUpdateChecklistItem,
                onAdd = onAddChecklistItem,
                onRemove = onRemoveChecklistItem,
                onConvertToText = onConvertChecklistToContent,
                contentColor = contentColor,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else {
            val markdownTransformation = remember(contentColor) {
                MarkdownVisualTransformation(contentColor)
            }
            BasicTextField(
                value = contentValue,
                onValueChange = onContentValueChange,
                textStyle = bodyStyle,
                visualTransformation = markdownTransformation,
                cursorBrush = SolidColor(contentColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(bodyFocusRequester),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                    ) {
                        if (content.isEmpty()) {
                            Text(
                                text = stringResource(R.string.note_hint),
                                style = bodyStyle,
                                color = placeholderColor,
                                modifier = Modifier.align(Alignment.TopStart),
                            )
                        }
                        innerTextField()
                    }
                }
            )

            TextButton(
                onClick = onConvertContentToChecklist,
                contentPadding = PaddingValues(top = 12.dp),
            ) {
                Text(
                    text = if (content.isBlank()) {
                        stringResource(R.string.add_checklist)
                    } else {
                        stringResource(R.string.convert_to_checklist)
                    },
                    color = contentColor.copy(alpha = 0.75f),
                )
            }
        }

        if (showFormattingToolbar) {
            Spacer(modifier = Modifier.height(64.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderPickerDialog(
    initialMillis: Long,
    hasExistingReminder: Boolean,
    onConfirm: (Long) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val calendar = remember(initialMillis) {
        Calendar.getInstance().apply { timeInMillis = initialMillis }
    }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    val timePickerState = rememberTimePickerState(
        initialHour = calendar.get(Calendar.HOUR_OF_DAY),
        initialMinute = calendar.get(Calendar.MINUTE),
        is24Hour = DateFormat.is24HourFormat(context)
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val date = datePickerState.selectedDateMillis ?: initialMillis
                onConfirm(combineDateAndTime(date, timePickerState.hour, timePickerState.minute))
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            if (hasExistingReminder) {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.action_remove)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SuggestionChip(
                    onClick = {
                        onConfirm(System.currentTimeMillis() + 60 * 60 * 1000L)
                    },
                    label = { Text(stringResource(R.string.reminder_in_one_hour)) }
                )
                SuggestionChip(
                    onClick = {
                        val cal = Calendar.getInstance().apply {
                            add(Calendar.DAY_OF_YEAR, 1)
                            set(Calendar.HOUR_OF_DAY, 9)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        onConfirm(cal.timeInMillis)
                    },
                    label = { Text(stringResource(R.string.reminder_tomorrow_morning)) }
                )
                SuggestionChip(
                    onClick = {
                        onConfirm(System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L)
                    },
                    label = { Text(stringResource(R.string.reminder_next_week)) }
                )
            }
            DatePicker(state = datePickerState)
            TimePicker(state = timePickerState)
        }
    }
}

@Composable
private fun LinkDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(R.string.link_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.link_dialog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.link_url_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank()
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
