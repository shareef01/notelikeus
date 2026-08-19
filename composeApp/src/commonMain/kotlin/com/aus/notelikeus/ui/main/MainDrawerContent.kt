package com.aus.notelikeus.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aus.notelikeus.ui.main.components.SideDrawerAccountRow
import com.aus.notelikeus.ui.main.components.SideDrawerNavItem
import com.aus.notelikeus.ui.theme.NavIdentity
import com.aus.notelikeus.ui.main.components.SideDrawerSectionLabel
import com.aus.notelikeus.ui.theme.BrandMarkIcon
import com.aus.notelikeus.ui.theme.Chrome
import com.aus.notelikeus.ui.theme.SignOutRose
import com.aus.notelikeus.ui.theme.SignOutRoseContainer
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.aus.notelikeus.ui.theme.AppType

/**
 * The side drawer / navigation rail content, shared between the modal drawer (compact) and the
 * permanent one (expanded). Extracted from MainScreen so that screen reads as layout logic only.
 *
 * Navigation callbacks arrive pre-packaged from MainScreen: each already performs the haptic and
 * closes the modal drawer when needed.
 */
@Composable
internal fun MainDrawerContent(
    state: MainState,
    collapsed: Boolean,
    isExpanded: Boolean,
    settingsSelected: Boolean,
    onFilterSelect: (NoteFilter) -> Unit,
    onEditLabels: () -> Unit,
    onOpenSettings: () -> Unit,
    onCloudSignOut: () -> Unit,
    onSidebarCollapsedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Column(modifier = Modifier.fillMaxHeight()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(
                    horizontal = if (collapsed) 8.dp else 20.dp,
                    vertical = if (collapsed) 16.dp else 20.dp
                )
        ) {
            if (collapsed) {
                BrandMarkIcon(
                    size = 32.dp,
                    backgroundColor = MaterialTheme.colorScheme.onSurface,
                    stripeColor = MaterialTheme.colorScheme.surface,
                    ringColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMarkIcon(
                            size = 36.dp,
                            backgroundColor = MaterialTheme.colorScheme.onSurface,
                            stripeColor = MaterialTheme.colorScheme.surface,
                            ringColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                stringResource(Res.string.app_name),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.3).sp,
                                    fontSize = 15.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(Res.string.drawer_tagline_short),
                                style = AppType.chromeLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(if (collapsed) 2.dp else 4.dp)
        ) {
            SideDrawerNavItem(
                label = stringResource(Res.string.nav_notes),
                icon = Icons.Outlined.Lightbulb,
                selectedIcon = Icons.Filled.Lightbulb,
                selected = state.currentFilter == NoteFilter.ACTIVE,
                count = state.totalNoteCount,
                collapsed = collapsed,
                identityColor = NavIdentity.Notes.resolve(),
                onClick = { onFilterSelect(NoteFilter.ACTIVE) }
            )
            SideDrawerNavItem(
                label = stringResource(Res.string.nav_archive),
                icon = Icons.Outlined.Archive,
                selectedIcon = Icons.Filled.Archive,
                selected = state.currentFilter == NoteFilter.ARCHIVED,
                count = state.archivedNoteCount,
                collapsed = collapsed,
                identityColor = NavIdentity.Archive.resolve(),
                onClick = { onFilterSelect(NoteFilter.ARCHIVED) }
            )
            SideDrawerNavItem(
                label = stringResource(Res.string.nav_trash),
                icon = Icons.Outlined.Delete,
                selectedIcon = Icons.Filled.Delete,
                selected = state.currentFilter == NoteFilter.TRASHED,
                count = state.trashedNoteCount,
                collapsed = collapsed,
                identityColor = NavIdentity.Trash.resolve(),
                onClick = { onFilterSelect(NoteFilter.TRASHED) }
            )

            Spacer(modifier = Modifier.height(20.dp))
            if (!collapsed) {
                SideDrawerSectionLabel(text = stringResource(Res.string.nav_section_manage))
            }

            SideDrawerNavItem(
                label = stringResource(Res.string.nav_edit_labels),
                icon = Icons.AutoMirrored.Outlined.Label,
                selectedIcon = Icons.AutoMirrored.Filled.Label,
                selected = false,
                collapsed = collapsed,
                identityColor = NavIdentity.Labels.resolve(),
                onClick = onEditLabels
            )
            SideDrawerNavItem(
                label = stringResource(Res.string.nav_settings),
                icon = Icons.Outlined.Settings,
                selectedIcon = Icons.Filled.Settings,
                selected = settingsSelected,
                collapsed = collapsed,
                identityColor = NavIdentity.Settings.resolve(),
                onClick = onOpenSettings
            )
        }

        // Collapse toggle — only in permanent (expanded) mode
        if (isExpanded) {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = if (collapsed) 8.dp else 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.Divider)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (collapsed) 8.dp else 16.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onSidebarCollapsedChange(!collapsed)
                    }
                    .padding(horizontal = if (collapsed) 0.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowLeft,
                    contentDescription = if (collapsed) "Expand sidebar" else "Collapse sidebar",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(if (collapsed) 0f else 180f)
                )
                if (!collapsed) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Collapse",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
            }
        }

        if (!collapsed) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.Divider)
            )
            Spacer(modifier = Modifier.height(12.dp))
            val email = state.cloudAccount.email
            if (state.cloudAccount.isGoogleAccount && !email.isNullOrBlank()) {
                SideDrawerAccountRow(email = email)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SignOutRoseContainer)
                        .border(
                            width = 1.dp,
                            color = SignOutRose.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onCloudSignOut()
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = stringResource(Res.string.cloud_sign_out),
                        tint = SignOutRose,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.cloud_sign_out),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.15).sp
                        ),
                        color = SignOutRose
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp).navigationBarsPadding())
    }
}
