package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import com.aus.notelikeus.domain.model.AppTheme
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.ui.components.AppFilterChip
import com.aus.notelikeus.ui.main.CloudAccount
import com.aus.notelikeus.ui.main.CloudSyncStatus
import com.aus.notelikeus.util.AppConfig
import com.aus.notelikeus.ui.theme.BrandMarkIcon
import com.aus.notelikeus.ui.theme.Chrome
import com.aus.notelikeus.ui.theme.AppType
import androidx.compose.material.icons.filled.Contrast
import com.aus.notelikeus.domain.model.AccentColor
import com.aus.notelikeus.domain.model.ThemeBase
import com.aus.notelikeus.domain.model.ThemePreference
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.ui.theme.Size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role

private val SettingsIconSize = Spacing.xxl
private val SettingsRowHorizontal = Spacing.lg
private val SettingsRowVertical = Spacing.md

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(
    onDismiss: () -> Unit,
    noteCount: Int,
    viewMode: NoteViewMode,
    sortOrder: NoteSortOrder,
    themePreference: ThemePreference,
    isAppLockEnabled: Boolean,
    cloudSyncStatus: CloudSyncStatus = CloudSyncStatus.Unknown,
    cloudSyncedNoteCount: Int = 0,
    cloudAccount: CloudAccount = CloudAccount(),
    isCloudAutoSyncEnabled: Boolean = true,
    signInError: String? = null,
    onViewModeChange: (NoteViewMode) -> Unit,
    onSortOrderChange: (NoteSortOrder) -> Unit,
    onThemeBaseChange: (ThemeBase) -> Unit,
    onAccentChange: (AccentColor) -> Unit,
    onAmoledChange: (Boolean) -> Unit,
    onAppLockChange: (Boolean) -> Unit,
    onExportClick: () -> Unit,
    onExportNotesOnlyClick: () -> Unit = {},
    onExportDeviceBackupClick: () -> Unit = {},
    onImportClick: () -> Unit,
    onCloudSyncClick: () -> Unit = {},
    onCloudRestoreClick: () -> Unit = {},
    onSignInClick: () -> Unit = {},
    onSignOutClick: () -> Unit = {},
    onDeleteAccountClick: () -> Unit = {},
    onCloudAutoSyncChange: (Boolean) -> Unit = {},
    onDiagnosticsClick: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    val canSync = cloudAccount.isSignedIn && cloudSyncStatus != CloudSyncStatus.Syncing

    if (showPrivacyPolicy) {
        PrivacyPolicyDialog(onDismiss = { showPrivacyPolicy = false })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xxl)
                .navigationBarsPadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.lg)
            ) {
                BrandMarkIcon(
                    size = Size.chipHeightCompact,
                    backgroundColor = MaterialTheme.colorScheme.onSurface,
                    stripeColor = MaterialTheme.colorScheme.surface,
                    ringColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.width(Size.iconTiny))
                Column {
                    Text(
                        text = stringResource(Res.string.app_name),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = stringResource(Res.string.settings_local_app),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.Divider)
            )

            // Read top to bottom: who you are and how sync is doing, how the app looks, your data,
            // then about. Sign out and Delete account stay last, away from routine settings: they
            // are the destructive actions here, and Sign out can also delete the cloud copy.
            SettingsSectionHeader(
                title = stringResource(Res.string.section_account_sync),
                isFirst = true
            )
            // One row for the account, the cloud's state and the note count. These used to be a
            // separate Insights section that repeated what the account and sync rows said.
            SettingsRow(
                icon = if (cloudAccount.isSignedIn) Icons.Default.AccountCircle else Icons.Default.CloudOff,
                title = if (cloudAccount.isSignedIn && !cloudAccount.email.isNullOrBlank()) {
                    cloudAccount.email
                } else {
                    stringResource(Res.string.cloud_status_signed_out)
                },
                subtitle = stringResource(
                    Res.string.settings_account_summary,
                    accountStatusLabel(cloudSyncStatus, cloudAccount),
                    pluralStringResource(Res.plurals.settings_note_count, noteCount, noteCount),
                ),
            )
            if (!cloudAccount.isSignedIn) {
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.Login,
                    title = stringResource(Res.string.cloud_sign_in),
                    subtitle = stringResource(Res.string.cloud_sign_in_subtitle),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onSignInClick()
                    }
                )
            }
            if (!signInError.isNullOrBlank()) {
                Text(
                    text = signInError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 68.dp)
                )
            }
            SettingsToggleListItem(
                icon = Icons.Default.Sync,
                title = stringResource(Res.string.cloud_auto_sync),
                subtitle = stringResource(Res.string.cloud_auto_sync_subtitle),
                checked = isCloudAutoSyncEnabled,
                enabled = cloudAccount.isSignedIn,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onCloudAutoSyncChange(it)
                }
            )
            SettingsRow(
                icon = Icons.Default.CloudUpload,
                title = stringResource(Res.string.cloud_sync_now),
                subtitle = syncNowSubtitle(cloudSyncStatus, cloudSyncedNoteCount),
                enabled = canSync,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onCloudSyncClick()
                }
            )
            SettingsRow(
                icon = Icons.Default.CloudDownload,
                title = stringResource(Res.string.cloud_restore),
                subtitle = stringResource(Res.string.cloud_restore_subtitle),
                enabled = canSync,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onCloudRestoreClick()
                }
            )

            SettingsSectionDivider()
            SettingsSectionHeader(title = stringResource(Res.string.section_appearance))
            ThemePicker(
                preference = themePreference,
                onBaseChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onThemeBaseChange(it)
                },
                onAccentChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onAccentChange(it)
                }
            )
            // Only meaningful on a dark scheme: on light there is no background to make blacker.
            // Disabled rather than hidden, so the control does not appear and vanish as the base
            // changes -- and so someone on System can still see it exists.
            SettingsToggleListItem(
                icon = Icons.Default.Contrast,
                title = stringResource(Res.string.amoled_title),
                subtitle = stringResource(Res.string.amoled_subtitle),
                checked = themePreference.amoled,
                enabled = themePreference.base != ThemeBase.LIGHT,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onAmoledChange(it)
                }
            )
            // Hidden where the platform can't verify the user — offering the toggle would imply a
            // protection the lock screen cannot actually provide.
            if (AppConfig.supportsAppLock) {
                SettingsToggleListItem(
                    icon = Icons.Default.Lock,
                    title = stringResource(Res.string.app_lock_title),
                    subtitle = stringResource(Res.string.app_lock_subtitle),
                    checked = isAppLockEnabled,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onAppLockChange(it)
                    }
                )
            }

            SettingsSectionDivider()
            SettingsSectionHeader(title = stringResource(Res.string.section_layout))
            // Visible choices rather than rows that cycle on tap, so the options are on screen
            // before the tap instead of being discovered one at a time after it.
            SettingsChoiceRow(
                icon = Icons.Default.ViewModule,
                title = stringResource(Res.string.default_view_mode),
                options = SettingsViewChoices,
                selected = SettingsViewChoices.firstOrNull { it.matches(viewMode) },
                labelFor = { stringResource(viewModeLabelRes(it.mode)) },
                onSelect = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    // Grid keeps a column count chosen elsewhere; only switching to Grid picks one.
                    if (!it.matches(viewMode)) onViewModeChange(it.mode)
                }
            )
            SettingsChoiceRow(
                icon = Icons.AutoMirrored.Filled.Sort,
                title = stringResource(Res.string.sort_order),
                options = NoteSortOrder.entries,
                selected = sortOrder,
                labelFor = { stringResource(sortOrderChoiceLabelRes(it)) },
                onSelect = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onSortOrderChange(it)
                }
            )

            SettingsSectionDivider()
            SettingsSectionHeader(title = stringResource(Res.string.section_data))
            SettingsRow(
                icon = Icons.Default.Backup,
                title = stringResource(Res.string.backup_export),
                subtitle = stringResource(Res.string.export_backup_subtitle),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onExportClick()
                }
            )
            SettingsRow(
                icon = Icons.Default.Backup,
                title = stringResource(Res.string.export_device_backup),
                subtitle = stringResource(Res.string.export_device_backup_subtitle),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onExportDeviceBackupClick()
                }
            )
            SettingsRow(
                icon = Icons.Default.Backup,
                title = stringResource(Res.string.export_notes_only),
                subtitle = stringResource(Res.string.export_notes_only_subtitle),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onExportNotesOnlyClick()
                }
            )
            SettingsRow(
                icon = Icons.Default.Upload,
                title = stringResource(Res.string.backup_import),
                subtitle = stringResource(Res.string.import_backup_subtitle),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onImportClick()
                }
            )

            SettingsSectionDivider()
            SettingsSectionHeader(title = stringResource(Res.string.section_about))
            SettingsRow(
                icon = Icons.Default.PrivacyTip,
                title = stringResource(Res.string.privacy_policy),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    showPrivacyPolicy = true
                }
            )
            SettingsRow(
                icon = Icons.Default.Info,
                title = stringResource(Res.string.diagnostics_title),
                subtitle = stringResource(Res.string.diagnostics_subtitle),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onDiagnosticsClick()
                }
            )
            SettingsRow(
                icon = Icons.Default.Info,
                title = stringResource(Res.string.app_version, AppConfig.versionName)
            )

            if (cloudAccount.isSignedIn) {
                SettingsSectionDivider()
                SettingsSectionHeader(title = stringResource(Res.string.section_account))
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    title = stringResource(Res.string.cloud_sign_out),
                    subtitle = stringResource(Res.string.cloud_sign_out_subtitle),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onSignOutClick()
                    }
                )
                SettingsRow(
                    icon = Icons.Default.DeleteForever,
                    title = stringResource(Res.string.account_delete),
                    subtitle = stringResource(Res.string.account_delete_subtitle),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onDeleteAccountClick()
                    }
                )
            }
        }
    }
}

/** The three view choices. Grid stands for every column count, so a 4-column grid still reads as Grid. */
private enum class SettingsViewChoice(val mode: NoteViewMode) {
    GRID(NoteViewMode.GRID_2),
    LIST(NoteViewMode.LIST),
    COMPACT(NoteViewMode.COMPACT);

    fun matches(current: NoteViewMode): Boolean = when (this) {
        GRID -> current.columns > 1
        LIST -> current == NoteViewMode.LIST
        COMPACT -> current == NoteViewMode.COMPACT
    }
}

private val SettingsViewChoices = SettingsViewChoice.entries

/** Short labels for the sort chips; the menu's "Manual (drag to reorder)" is too long for one. */
private fun sortOrderChoiceLabelRes(order: NoteSortOrder): StringResource = when (order) {
    NoteSortOrder.MANUAL -> Res.string.sort_manual_short
    else -> sortOrderLabelRes(order)
}

/** A settings row whose options are all visible, as chips under its title. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SettingsChoiceRow(
    icon: ImageVector,
    title: String,
    options: List<T>,
    selected: T?,
    labelFor: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsRowHorizontal, vertical = SettingsRowVertical)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsLeadingIcon(icon = icon, contentDescription = null)
            Spacer(modifier = Modifier.width(Size.iconTiny))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.padding(start = SettingsIconSize + Size.iconTiny, top = Spacing.xs)
        ) {
            options.forEach { option ->
                AppFilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = labelFor(option),
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun syncNowSubtitle(status: CloudSyncStatus, syncedCount: Int): String = when (status) {
    CloudSyncStatus.Syncing -> stringResource(Res.string.cloud_sync_in_progress)
    // A successful sync that moved nothing is the steady state, not a failure to sync anything,
    // so it says so rather than reporting "0 notes".
    CloudSyncStatus.Synced -> if (syncedCount > 0) {
        stringResource(Res.string.cloud_sync_last, syncedCount)
    } else {
        stringResource(Res.string.cloud_sync_up_to_date)
    }
    CloudSyncStatus.Offline, CloudSyncStatus.Error -> stringResource(Res.string.cloud_sync_offline)
    else -> stringResource(Res.string.cloud_sync_subtitle)
}

/** The account's state in a word or two, for the summary row. */
@Composable
private fun accountStatusLabel(status: CloudSyncStatus, account: CloudAccount): String {
    // Without an account there is nothing to check, so it must not say it is checking.
    if (!account.isSignedIn) return stringResource(Res.string.settings_local_only)
    return when (status) {
        CloudSyncStatus.Unknown -> stringResource(Res.string.cloud_signed_in_as)
        CloudSyncStatus.Connected -> stringResource(Res.string.cloud_status_ready)
        CloudSyncStatus.Offline -> stringResource(Res.string.cloud_status_offline)
        CloudSyncStatus.Syncing -> stringResource(Res.string.cloud_status_syncing)
        CloudSyncStatus.Synced -> stringResource(Res.string.cloud_sync_up_to_date)
        CloudSyncStatus.Error -> stringResource(Res.string.cloud_status_error)
    }
}

@Composable
private fun SettingsSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = Spacing.sm),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.Divider)
    )
}

@Composable
fun SettingsSectionHeader(
    title: String,
    isFirst: Boolean = false,
    modifier: Modifier = Modifier
) {
    Text(
        text = title.uppercase(),
        style = AppType.chromeLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            start = SettingsRowHorizontal,
            end = SettingsRowHorizontal,
            top = if (isFirst) Spacing.lg else Spacing.xl,
            bottom = Spacing.xs
        )
    )
}

@Composable
fun SettingsLeadingIcon(
    icon: ImageVector,
    contentDescription: String?
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = Modifier.size(SettingsIconSize),
        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.62f)
    )
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    /**
     * Set alongside [onClick] to make the row a switch rather than a button.
     *
     * It drives the semantics only -- [onClick] still does the work. Without it a settings toggle
     * announced as "Pure black, button", which is the state of the setting withheld from exactly
     * the person who cannot see the switch.
     */
    checked: Boolean? = null,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(
                when {
                    onClick != null && checked != null -> Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = { onClick() }
                    )
                    onClick != null ->
                        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    else -> Modifier
                }
            )
            .padding(horizontal = SettingsRowHorizontal, vertical = SettingsRowVertical)
            .alpha(contentAlpha),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Null: the title is rendered as text on the very next line, and this row merges its
        // descendants, so describing the icon too announced every setting twice.
        SettingsLeadingIcon(icon = icon, contentDescription = null)
        Spacer(modifier = Modifier.width(Size.iconTiny))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(Spacing.sm))
            trailing()
        }
    }
}

@Composable
fun SettingsCycleListItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        modifier = modifier,
        trailing = {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.semantics { hideFromAccessibility() },
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Chrome.SelectedBorder)
            )
        }
    )
}

@Composable
fun SettingsToggleListItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        checked = checked,
        modifier = modifier,
        trailing = {
            // Null, so the switch pictures the state rather than being a second control inside a
            // row that already toggles. The row owns the interaction and reports it.
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled
            )
        }
    )
}
