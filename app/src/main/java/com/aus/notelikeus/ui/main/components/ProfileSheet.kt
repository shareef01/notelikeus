package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Stars
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aus.notelikeus.BuildConfig
import com.aus.notelikeus.R
import com.aus.notelikeus.domain.model.AppTheme
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.ui.main.CloudAccount
import com.aus.notelikeus.ui.main.CloudSyncStatus
import com.aus.notelikeus.ui.theme.BrandMarkIcon
import com.aus.notelikeus.ui.theme.Chrome
import com.aus.notelikeus.ui.theme.ChromeLabelStyle

private val SettingsIconSize = 24.dp
private val SettingsRowHorizontal = 16.dp
private val SettingsRowVertical = 12.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(
    onDismiss: () -> Unit,
    noteCount: Int,
    viewMode: NoteViewMode,
    sortOrder: NoteSortOrder,
    appTheme: AppTheme,
    isAppLockEnabled: Boolean,
    cloudSyncStatus: CloudSyncStatus = CloudSyncStatus.Unknown,
    cloudSyncedNoteCount: Int = 0,
    cloudAccount: CloudAccount = CloudAccount(),
    isCloudAutoSyncEnabled: Boolean = true,
    onViewModeChange: (NoteViewMode) -> Unit,
    onSortOrderChange: (NoteSortOrder) -> Unit,
    onAppThemeChange: (AppTheme) -> Unit,
    onAppLockChange: (Boolean) -> Unit,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
    onCloudSyncClick: () -> Unit = {},
    onCloudRestoreClick: () -> Unit = {},
    onGoogleSignInClick: () -> Unit = {},
    onGoogleSignOutClick: () -> Unit = {},
    onCloudAutoSyncChange: (Boolean) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    val canSync = cloudAccount.isGoogleAccount && cloudSyncStatus != CloudSyncStatus.Syncing

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
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                BrandMarkIcon(
                    size = 48.dp,
                    backgroundColor = MaterialTheme.colorScheme.onSurface,
                    stripeColor = MaterialTheme.colorScheme.surface
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = stringResource(R.string.settings_local_app),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.Divider)
            )

            SettingsSectionHeader(
                title = stringResource(R.string.section_layout),
                isFirst = true
            )
            SettingsCycleListItem(
                icon = Icons.Default.ViewModule,
                title = stringResource(R.string.default_view_mode),
                subtitle = stringResource(viewModeLabelRes(viewMode)),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onViewModeChange(viewMode.next())
                }
            )
            SettingsCycleListItem(
                icon = Icons.Default.Sort,
                title = stringResource(R.string.sort_order),
                subtitle = stringResource(sortOrderLabelRes(sortOrder)),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onSortOrderChange(sortOrder.next())
                }
            )

            SettingsSectionDivider()
            SettingsSectionHeader(title = stringResource(R.string.section_appearance))
            ThemePicker(
                value = appTheme,
                onChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onAppThemeChange(it)
                }
            )
            SettingsToggleListItem(
                icon = Icons.Default.Lock,
                title = stringResource(R.string.app_lock_title),
                subtitle = stringResource(R.string.app_lock_subtitle),
                checked = isAppLockEnabled,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onAppLockChange(it)
                }
            )

            SettingsSectionDivider()
            SettingsSectionHeader(title = stringResource(R.string.section_insights))
            SettingsRow(
                icon = Icons.Default.Description,
                title = stringResource(R.string.total_notes),
                subtitle = noteCount.toString()
            )
            SettingsRow(
                icon = when (cloudSyncStatus) {
                    CloudSyncStatus.Connected, CloudSyncStatus.Synced -> Icons.Default.CloudDone
                    CloudSyncStatus.Syncing -> Icons.Default.CloudSync
                    CloudSyncStatus.Error, CloudSyncStatus.Offline -> Icons.Default.CloudOff
                    CloudSyncStatus.Unknown -> Icons.Default.CloudQueue
                },
                title = stringResource(R.string.cloud_sync),
                subtitle = cloudStatusLabel(cloudSyncStatus, cloudSyncedNoteCount, cloudAccount)
            )

            SettingsSectionDivider()
            SettingsSectionHeader(title = stringResource(R.string.section_account))
            if (cloudAccount.isGoogleAccount && !cloudAccount.email.isNullOrBlank()) {
                SettingsRow(
                    icon = Icons.Default.AccountCircle,
                    title = cloudAccount.email,
                    subtitle = stringResource(R.string.cloud_signed_in_as)
                )
                SettingsRow(
                    icon = Icons.Default.Logout,
                    title = stringResource(R.string.cloud_sign_out),
                    subtitle = stringResource(R.string.cloud_sign_out_subtitle),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onGoogleSignOutClick()
                    }
                )
            } else {
                SettingsRow(
                    icon = Icons.Default.Login,
                    title = stringResource(R.string.cloud_sign_in_google),
                    subtitle = stringResource(R.string.cloud_sign_in_subtitle),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onGoogleSignInClick()
                    }
                )
            }
            SettingsToggleListItem(
                icon = Icons.Default.Sync,
                title = stringResource(R.string.cloud_auto_sync),
                subtitle = stringResource(R.string.cloud_auto_sync_subtitle),
                checked = isCloudAutoSyncEnabled,
                enabled = cloudAccount.isGoogleAccount,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onCloudAutoSyncChange(it)
                }
            )
            SettingsRow(
                icon = Icons.Default.CloudUpload,
                title = stringResource(R.string.cloud_sync_now),
                subtitle = when (cloudSyncStatus) {
                    CloudSyncStatus.Syncing -> stringResource(R.string.cloud_sync_in_progress)
                    CloudSyncStatus.Synced -> stringResource(
                        R.string.cloud_sync_last,
                        cloudSyncedNoteCount
                    )
                    CloudSyncStatus.Offline, CloudSyncStatus.Error ->
                        stringResource(R.string.cloud_sync_offline)
                    else -> stringResource(R.string.cloud_sync_subtitle)
                },
                enabled = canSync,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onCloudSyncClick()
                }
            )
            SettingsRow(
                icon = Icons.Default.CloudDownload,
                title = stringResource(R.string.cloud_restore),
                subtitle = stringResource(R.string.cloud_restore_subtitle),
                enabled = canSync,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onCloudRestoreClick()
                }
            )
            SettingsRow(
                icon = Icons.Default.Backup,
                title = stringResource(R.string.backup_export),
                subtitle = stringResource(R.string.export_backup_subtitle),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onExportClick()
                }
            )
            SettingsRow(
                icon = Icons.Default.Upload,
                title = stringResource(R.string.backup_import),
                subtitle = stringResource(R.string.import_backup_subtitle),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onImportClick()
                }
            )

            SettingsSectionDivider()
            SettingsSectionHeader(title = stringResource(R.string.section_about))
            SettingsRow(
                icon = Icons.Default.Stars,
                title = stringResource(R.string.premium_subscription),
                subtitle = stringResource(R.string.coming_soon_detail)
            )
            SettingsRow(
                icon = Icons.Default.PrivacyTip,
                title = stringResource(R.string.privacy_policy),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    showPrivacyPolicy = true
                }
            )
            SettingsRow(
                icon = Icons.Default.Info,
                title = stringResource(R.string.app_version, BuildConfig.VERSION_NAME)
            )
        }
    }
}

@Composable
private fun SettingsSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 8.dp),
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
        style = ChromeLabelStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            start = SettingsRowHorizontal,
            end = SettingsRowHorizontal,
            top = if (isFirst) 16.dp else 20.dp,
            bottom = 4.dp
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
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = SettingsRowHorizontal, vertical = SettingsRowVertical)
            .alpha(contentAlpha),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsLeadingIcon(icon = icon, contentDescription = title)
        Spacer(modifier = Modifier.width(14.dp))
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
            Spacer(modifier = Modifier.width(8.dp))
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
                modifier = Modifier.semantics { invisibleToUser() },
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
        modifier = modifier,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    )
}

@Composable
private fun cloudStatusLabel(
    status: CloudSyncStatus,
    syncedCount: Int,
    account: CloudAccount
): String {
    if (account.isGoogleAccount && !account.email.isNullOrBlank()) {
        val localPart = account.email.substringBefore('@')
        if (localPart.length <= 12) return localPart
        return localPart.take(10) + "…"
    }
    return when (status) {
        CloudSyncStatus.Unknown -> stringResource(R.string.cloud_status_checking)
        CloudSyncStatus.Connected -> stringResource(R.string.cloud_status_ready)
        CloudSyncStatus.Offline -> stringResource(R.string.cloud_status_offline)
        CloudSyncStatus.Syncing -> stringResource(R.string.cloud_status_syncing)
        CloudSyncStatus.Synced -> syncedCount.toString()
        CloudSyncStatus.Error -> stringResource(R.string.cloud_status_error)
    }
}
