package com.aus.notelikeus.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.semantics.Role
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
import com.aus.notelikeus.ui.main.components.SideDrawerSectionLabel
import com.aus.notelikeus.ui.theme.BrandMarkIcon
import com.aus.notelikeus.ui.theme.Chrome
import com.aus.notelikeus.ui.theme.SignOutRose
import com.aus.notelikeus.ui.theme.SignOutRoseContainer
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.aus.notelikeus.ui.theme.AppType
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.ui.theme.Size
import com.aus.notelikeus.domain.model.SmartView
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.automirrored.outlined.LabelOff
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.StringResource
import com.aus.notelikeus.domain.model.SavedFilter
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder

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
    onSmartViewSelect: (SmartView) -> Unit,
    onSavedFilterSelect: (SavedFilter) -> Unit,
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
                    horizontal = if (collapsed) Spacing.sm else Spacing.xl,
                    vertical = if (collapsed) Spacing.lg else Spacing.xl
                )
        ) {
            if (collapsed) {
                BrandMarkIcon(
                    size = Spacing.xxxl,
                    backgroundColor = MaterialTheme.colorScheme.onSurface,
                    stripeColor = MaterialTheme.colorScheme.surface,
                    ringColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMarkIcon(
                            size = Size.controlHeight,
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
                .padding(top = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(if (collapsed) Spacing.xxs else Spacing.xs)
        ) {
            SideDrawerNavItem(
                label = stringResource(Res.string.nav_notes),
                icon = Icons.Outlined.Lightbulb,
                selectedIcon = Icons.Filled.Lightbulb,
                selected = state.currentFilter == NoteFilter.ACTIVE,
                count = state.totalNoteCount,
                collapsed = collapsed,
                onClick = { onFilterSelect(NoteFilter.ACTIVE) }
            )
            SideDrawerNavItem(
                label = stringResource(Res.string.nav_archive),
                icon = Icons.Outlined.Archive,
                selectedIcon = Icons.Filled.Archive,
                selected = state.currentFilter == NoteFilter.ARCHIVED,
                count = state.archivedNoteCount,
                collapsed = collapsed,
                onClick = { onFilterSelect(NoteFilter.ARCHIVED) }
            )
            SideDrawerNavItem(
                label = stringResource(Res.string.nav_trash),
                icon = Icons.Outlined.Delete,
                selectedIcon = Icons.Filled.Delete,
                selected = state.currentFilter == NoteFilter.TRASHED,
                count = state.trashedNoteCount,
                collapsed = collapsed,
                onClick = { onFilterSelect(NoteFilter.TRASHED) }
            )

            // Named queries sit with the scopes rather than in the Filters sheet, because they
            // answer the same kind of question the scopes do -- "which pile am I looking at" --
            // and burying a question that gets asked daily four taps into a sheet is what made
            // this app feel like a pile of features.
            Spacer(modifier = Modifier.height(Size.icon))
            if (!collapsed) {
                SideDrawerSectionLabel(text = stringResource(Res.string.nav_section_views))
            }

            SmartView.entries.forEach { view ->
                val count = state.smartViewCounts[view]
                SideDrawerNavItem(
                    label = stringResource(view.labelRes()),
                    icon = view.icon(selected = false),
                    selectedIcon = view.icon(selected = true),
                    selected = view.isActive(state.query),
                    // Null, not zero: an empty view is worth showing -- it is how you learn you
                    // have nothing unfinished -- but a "0" badge on every row is noise.
                    count = count?.takeIf { it > 0 },
                    collapsed = collapsed,
                    onClick = { onSmartViewSelect(view) }
                )
            }

            // Only when there are any. An empty "Saved" heading would advertise a feature by
            // showing the hole where its results go.
            if (state.savedFilters.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Size.icon))
                if (!collapsed) {
                    SideDrawerSectionLabel(text = stringResource(Res.string.nav_section_saved))
                }
                val narrowing = state.query.narrowingOnly()
                state.savedFilters.forEach { filter ->
                    SideDrawerNavItem(
                        label = filter.name,
                        icon = Icons.Outlined.BookmarkBorder,
                        selectedIcon = Icons.Filled.Bookmark,
                        selected = filter.query == narrowing,
                        collapsed = collapsed,
                        onClick = { onSavedFilterSelect(filter) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(Size.icon))
            if (!collapsed) {
                SideDrawerSectionLabel(text = stringResource(Res.string.nav_section_manage))
            }

            SideDrawerNavItem(
                label = stringResource(Res.string.nav_edit_labels),
                icon = Icons.AutoMirrored.Outlined.Label,
                selectedIcon = Icons.AutoMirrored.Filled.Label,
                selected = false,
                collapsed = collapsed,
                onClick = onEditLabels
            )
            SideDrawerNavItem(
                label = stringResource(Res.string.nav_settings),
                icon = Icons.Outlined.Settings,
                selectedIcon = Icons.Filled.Settings,
                selected = settingsSelected,
                collapsed = collapsed,
                onClick = onOpenSettings
            )
        }

        // Collapse toggle — only in permanent (expanded) mode
        if (isExpanded) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = if (collapsed) Spacing.sm else Spacing.lg),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.Divider)
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (collapsed) Spacing.sm else Spacing.lg)
                    .height(Size.chipHeight)
                    .clip(RoundedCornerShape(Spacing.md))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    .border(
                        width = Spacing.hairline,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(Spacing.md)
                    )
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (collapsed) "Expand sidebar" else "Collapse sidebar"
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onSidebarCollapsedChange(!collapsed)
                    }
                    .padding(horizontal = if (collapsed) Spacing.none else Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = if (collapsed) "Expand sidebar" else "Collapse sidebar",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    modifier = Modifier
                        .size(Size.icon)
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
            Spacer(modifier = Modifier.height(Spacing.sm))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = Spacing.lg),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.Divider)
            )
            Spacer(modifier = Modifier.height(Spacing.md))
            val email = state.cloudAccount.email
            if (state.cloudAccount.isGoogleAccount && !email.isNullOrBlank()) {
                SideDrawerAccountRow(email = email)
                Spacer(modifier = Modifier.height(Spacing.md))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg)
                        .height(Size.touchTarget)
                        .clip(RoundedCornerShape(Spacing.md))
                        .background(SignOutRoseContainer)
                        .border(
                            width = Spacing.hairline,
                            color = SignOutRose.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(Spacing.md)
                        )
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onCloudSignOut()
                        }
                        .padding(horizontal = Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = stringResource(Res.string.cloud_sign_out),
                        tint = SignOutRose,
                        modifier = Modifier.size(Size.iconMedium)
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        text = stringResource(Res.string.cloud_sign_out),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.15).sp
                        ),
                        color = SignOutRose
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.md))
            }
        }
        Spacer(modifier = Modifier.height(Spacing.sm).navigationBarsPadding())
    }
}


/** The drawer's label for a view. Kept next to the drawer, not on the enum: it is presentation. */
private fun SmartView.labelRes(): StringResource = when (this) {
    SmartView.REMINDERS -> Res.string.view_reminders
    SmartView.UNFINISHED -> Res.string.view_unfinished
    SmartView.UNLABELED -> Res.string.view_unlabeled
}

/** Outlined when idle, filled when selected -- the same pairing the scope rows use. */
private fun SmartView.icon(selected: Boolean): ImageVector = when (this) {
    SmartView.REMINDERS ->
        if (selected) Icons.Filled.NotificationsActive else Icons.Outlined.NotificationsNone
    SmartView.UNFINISHED ->
        if (selected) Icons.Filled.CheckBox else Icons.Outlined.CheckBoxOutlineBlank
    SmartView.UNLABELED ->
        if (selected) Icons.AutoMirrored.Filled.LabelOff
        else Icons.AutoMirrored.Outlined.LabelOff
}
