package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Palette
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aus.notelikeus.BuildConfig
import com.aus.notelikeus.R
import com.aus.notelikeus.domain.model.NoteSortOrder
import com.aus.notelikeus.domain.model.NoteViewMode
import com.aus.notelikeus.ui.main.CloudAccount
import com.aus.notelikeus.ui.main.CloudSyncStatus
import com.aus.notelikeus.ui.theme.BrandMarkIcon

private val SettingsIconSize = 24.dp
private val SettingsSectionTopPadding = 24.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(
    onDismiss: () -> Unit,
    noteCount: Int,
    viewMode: NoteViewMode,
    sortOrder: NoteSortOrder,
    useMonochromeTheme: Boolean,
    isTrueDarkMode: Boolean,
    isAppLockEnabled: Boolean,
    cloudSyncStatus: CloudSyncStatus = CloudSyncStatus.Unknown,
    cloudSyncedNoteCount: Int = 0,
    cloudAccount: CloudAccount = CloudAccount(),
    isCloudAutoSyncEnabled: Boolean = true,
    onViewModeChange: (NoteViewMode) -> Unit,
    onSortOrderChange: (NoteSortOrder) -> Unit,
    onMonochromeThemeChange: (Boolean) -> Unit,
    onTrueDarkModeChange: (Boolean) -> Unit,
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
    val listItemColors = ListItemDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surface
    )

    if (showPrivacyPolicy) {
        PrivacyPolicyDialog(onDismiss = { showPrivacyPolicy = false })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
                .navigationBarsPadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                BrandMarkIcon(
                    size = 56.dp,
                    backgroundColor = MaterialTheme.colorScheme.onSurface,
                    stripeColor = MaterialTheme.colorScheme.surface
                )
                Spacer(modifier = Modifier.width(16.dp))
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
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
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
            SettingsToggleListItem(
                icon = Icons.Default.Palette,
                title = stringResource(R.string.monochrome_theme),
                subtitle = stringResource(R.string.monochrome_theme_subtitle),
                checked = useMonochromeTheme,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onMonochromeThemeChange(it)
                }
            )
            SettingsToggleListItem(
                icon = Icons.Default.DarkMode,
                title = stringResource(R.string.true_dark_mode),
                subtitle = stringResource(R.string.true_dark_mode_subtitle),
                checked = isTrueDarkMode,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onTrueDarkModeChange(it)
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
            ListItem(
                headlineContent = { Text(stringResource(R.string.total_notes)) },
                supportingContent = {
                    Text(
                        text = noteCount.toString(),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                },
                leadingContent = {
                    SettingsLeadingIcon(
                        icon = Icons.Default.Description,
                        contentDescription = stringResource(R.string.total_notes)
                    )
                },
                colors = listItemColors
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.cloud_sync)) },
                supportingContent = {
                    Text(cloudStatusLabel(cloudSyncStatus, cloudSyncedNoteCount, cloudAccount))
                },
                leadingContent = {
                    SettingsLeadingIcon(
                        icon = when (cloudSyncStatus) {
                            CloudSyncStatus.Connected, CloudSyncStatus.Synced -> Icons.Default.CloudDone
                            CloudSyncStatus.Syncing -> Icons.Default.CloudSync
                            CloudSyncStatus.Error, CloudSyncStatus.Offline -> Icons.Default.CloudOff
                            CloudSyncStatus.Unknown -> Icons.Default.CloudQueue
                        },
                        contentDescription = stringResource(R.string.cloud_sync)
                    )
                },
                colors = listItemColors
            )

            SettingsSectionDivider()
            SettingsSectionHeader(title = stringResource(R.string.section_account))
            if (cloudAccount.isGoogleAccount && !cloudAccount.email.isNullOrBlank()) {
                ListItem(
                    headlineContent = { Text(cloudAccount.email) },
                    supportingContent = { Text(stringResource(R.string.cloud_signed_in_as)) },
                    leadingContent = {
                        SettingsLeadingIcon(
                            icon = Icons.Default.AccountCircle,
                            contentDescription = stringResource(R.string.cloud_signed_in_as)
                        )
                    },
                    colors = listItemColors
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.cloud_sign_out)) },
                    supportingContent = { Text(stringResource(R.string.cloud_sign_out_subtitle)) },
                    leadingContent = {
                        SettingsLeadingIcon(
                            icon = Icons.Default.Logout,
                            contentDescription = stringResource(R.string.cloud_sign_out)
                        )
                    },
                    colors = listItemColors,
                    modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onGoogleSignOutClick()
                    }
                )
            } else {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.cloud_sign_in_google)) },
                    supportingContent = { Text(stringResource(R.string.cloud_sign_in_subtitle)) },
                    leadingContent = {
                        SettingsLeadingIcon(
                            icon = Icons.Default.Login,
                            contentDescription = stringResource(R.string.cloud_sign_in_google)
                        )
                    },
                    colors = listItemColors,
                    modifier = Modifier.clickable {
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
            ListItem(
                headlineContent = { Text(stringResource(R.string.cloud_sync_now)) },
                supportingContent = {
                    Text(
                        when (cloudSyncStatus) {
                            CloudSyncStatus.Syncing -> stringResource(R.string.cloud_sync_in_progress)
                            CloudSyncStatus.Synced -> stringResource(
                                R.string.cloud_sync_last,
                                cloudSyncedNoteCount
                            )
                            CloudSyncStatus.Offline, CloudSyncStatus.Error ->
                                stringResource(R.string.cloud_sync_offline)
                            else -> stringResource(R.string.cloud_sync_subtitle)
                        }
                    )
                },
                leadingContent = {
                    SettingsLeadingIcon(
                        icon = Icons.Default.CloudUpload,
                        contentDescription = stringResource(R.string.cloud_sync_now)
                    )
                },
                colors = listItemColors,
                modifier = Modifier.clickable(
                    enabled = canSync,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onCloudSyncClick()
                    }
                )
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.cloud_restore)) },
                supportingContent = { Text(stringResource(R.string.cloud_restore_subtitle)) },
                leadingContent = {
                    SettingsLeadingIcon(
                        icon = Icons.Default.CloudDownload,
                        contentDescription = stringResource(R.string.cloud_restore)
                    )
                },
                colors = listItemColors,
                modifier = Modifier.clickable(
                    enabled = canSync,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onCloudRestoreClick()
                    }
                )
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.backup_export)) },
                supportingContent = { Text(stringResource(R.string.export_backup_subtitle)) },
                leadingContent = {
                    SettingsLeadingIcon(
                        icon = Icons.Default.Backup,
                        contentDescription = stringResource(R.string.backup_export)
                    )
                },
                colors = listItemColors,
                modifier = Modifier.clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onExportClick()
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.backup_import)) },
                supportingContent = { Text(stringResource(R.string.import_backup_subtitle)) },
                leadingContent = {
                    SettingsLeadingIcon(
                        icon = Icons.Default.Upload,
                        contentDescription = stringResource(R.string.backup_import)
                    )
                },
                colors = listItemColors,
                modifier = Modifier.clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onImportClick()
                }
            )

            SettingsSectionDivider()
            SettingsSectionHeader(title = stringResource(R.string.section_about))
            ListItem(
                headlineContent = { Text(stringResource(R.string.premium_subscription)) },
                supportingContent = { Text(stringResource(R.string.coming_soon_detail)) },
                leadingContent = {
                    SettingsLeadingIcon(
                        icon = Icons.Default.Stars,
                        contentDescription = stringResource(R.string.premium_subscription)
                    )
                },
                colors = listItemColors
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.privacy_policy)) },
                leadingContent = {
                    SettingsLeadingIcon(
                        icon = Icons.Default.PrivacyTip,
                        contentDescription = stringResource(R.string.privacy_policy)
                    )
                },
                colors = listItemColors,
                modifier = Modifier.clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    showPrivacyPolicy = true
                }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME)) },
                leadingContent = {
                    SettingsLeadingIcon(
                        icon = Icons.Default.Info,
                        contentDescription = stringResource(R.string.section_about)
                    )
                },
                colors = listItemColors
            )
        }
    }
}

@Composable
private fun SettingsSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
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
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        ),
        modifier = modifier.padding(
            start = 16.dp,
            end = 16.dp,
            top = if (isFirst) 16.dp else 24.dp, // Disciplined 24.dp Top Padding
            bottom = 8.dp
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
fun SettingsCycleListItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            SettingsLeadingIcon(icon = icon, contentDescription = title)
        },
        trailingContent = {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.semantics { invisibleToUser() },
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.clickable(onClick = onClick)
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
    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else 0.38f
                )
            )
        },
        leadingContent = {
            SettingsLeadingIcon(icon = icon, contentDescription = title)
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.clickable(enabled = enabled) {
            onCheckedChange(!checked)
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
