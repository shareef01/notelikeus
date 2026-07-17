package com.aus.notelikeus.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
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
import com.aus.notelikeus.ui.navigation.LocalAnimatedVisibilityScope
import com.aus.notelikeus.ui.navigation.LocalSharedTransitionScope
import com.aus.notelikeus.ui.theme.EditorBodyStyle
import com.aus.notelikeus.ui.theme.EditorTitleStyle
import com.aus.notelikeus.ui.theme.getContentColor
import com.aus.notelikeus.ui.theme.isNoteColorDarkTheme
import com.aus.notelikeus.ui.theme.noteColorsForTheme
import android.text.format.DateFormat
import java.util.Calendar

private val EditorHorizontalPadding = 16.dp
private val EditorVerticalPadding = 16.dp

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalSharedTransitionApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onStageUndo: (Note, UndoAction, String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.action_undo)
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
                    snackbarHostState.showSnackbar(context.getString(R.string.reminder_exact_alarm_hint))
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
        scope.launch {
            snackbarHostState.showSnackbar(context.getString(R.string.reminder_set_confirmation))
        }
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
                snackbarHostState.showSnackbar(context.getString(R.string.reminder_permission_denied))
            }
        }
    }

    fun confirmReminder(millis: Long) {
        if (millis <= System.currentTimeMillis()) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.reminder_must_be_future))
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

    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

    val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                rememberSharedContentState(key = "note-${state.id}"),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    } else {
        Modifier
    }

    val showLockOverlay = state.isNoteLoaded && state.isLocked && !state.isAccessGranted
    val imeVisible = WindowInsets.isImeVisible
    var hasPromptedLockAuth by remember { mutableStateOf(false) }

    LaunchedEffect(showLockOverlay) {
        if (showLockOverlay && !hasPromptedLockAuth) {
            hasPromptedLockAuth = true
            (context as MainActivity).showBiometricPrompt(
                title = context.getString(R.string.locked_note),
                onSuccess = { viewModel.grantAccess() },
                onError = { onBack() }
            )
        }
    }

    LaunchedEffect(state.noteNotFound) {
        if (state.noteNotFound) {
            snackbarHostState.showSnackbar(context.getString(R.string.note_not_found))
            onBack()
        }
    }

    if (showLockOverlay) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = noteColor
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
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
                        .padding(horizontal = 32.dp)
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
                Spacer(modifier = Modifier.height(24.dp))
                FilledTonalButton(
                    onClick = {
                        (context as MainActivity).showBiometricPrompt(
                            title = context.getString(R.string.locked_note),
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
                TextButton(onClick = onBack) {
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
            )
        },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { 
                        viewModel.saveNote()
                        onBack() 
                    }) {
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
                                context.getString(
                                    if (willPin) R.string.note_pinned else R.string.note_unpinned
                                )
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
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.toggleArchive { snapshot ->
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.note_archived),
                                    actionLabel = undoLabel,
                                    duration = SnackbarDuration.Short
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.undoArchive(snapshot)
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
                    onMoreClick = { showBottomSheet = true },
                    contentColor = contentColor,
                    modifier = Modifier.background(noteColor)
                )
            }
        },
        containerColor = noteColor,
        modifier = Modifier.fillMaxSize().then(sharedModifier)
    ) { paddingValues ->
        val showFormattingToolbar = state.contentValue.selection.length > 0

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
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
                                    context.getString(R.string.note_trashed)
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
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.reminder_removed_confirmation))
                        }
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
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
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
                    onConvertChecklistToContent = viewModel::convertChecklistToContent
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
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
            textStyle = EditorTitleStyle.copy(color = contentColor),
            cursorBrush = SolidColor(contentColor),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                if (title.isEmpty()) {
                    Text(
                        text = stringResource(R.string.title_hint),
                        style = EditorTitleStyle,
                        color = contentColor.copy(alpha = 0.5f)
                    )
                }
                innerTextField()
            }
        )

        if (labels.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                labels.forEach { label ->
                    SuggestionChip(
                        onClick = {},
                        enabled = false,
                        label = {
                            Text(
                                text = label.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor
                            )
                        },
                        shape = CircleShape,
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = contentColor.copy(alpha = 0.12f),
                            labelColor = contentColor,
                            disabledContainerColor = contentColor.copy(alpha = 0.12f),
                            disabledLabelColor = contentColor
                        ),
                        border = null
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
                contentColor = contentColor
            )
        } else {
            val markdownTransformation = remember(contentColor) {
                MarkdownVisualTransformation(contentColor)
            }
            BasicTextField(
                value = contentValue,
                onValueChange = onContentValueChange,
                textStyle = EditorBodyStyle.copy(color = contentColor),
                visualTransformation = markdownTransformation,
                cursorBrush = SolidColor(contentColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                decorationBox = { innerTextField ->
                    if (content.isEmpty()) {
                        Text(
                            text = stringResource(R.string.note_hint),
                            style = EditorBodyStyle,
                            color = contentColor.copy(alpha = 0.5f)
                        )
                    }
                    innerTextField()
                }
            )
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
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.link_url_hint)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )
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
