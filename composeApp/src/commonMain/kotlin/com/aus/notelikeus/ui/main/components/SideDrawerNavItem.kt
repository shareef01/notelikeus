package com.aus.notelikeus.ui.main.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aus.notelikeus.ui.theme.Chrome
import com.aus.notelikeus.ui.theme.AppType

/**
 * A destination row in the side drawer.
 *
 * Icons take one colour role, not a per-destination hue. The five signature colours these rows
 * used to carry (sky, amber, rose, violet, teal) read as unrelated icon sets rather than a system,
 * and — worse — they left *selection* with no colour of its own to signal with, because every row
 * was already saturated. Colour now means one of two things in this app: a note's colour, or the
 * user's chosen accent. Selection is the accent; everything else is neutral.
 *
 * @param icon shown when the row is not selected; prefer the outlined variant.
 * @param selectedIcon shown when it is; prefer the filled variant of the same glyph.
 */
@Composable
fun SideDrawerNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedIcon: ImageVector = icon,
    count: Int? = null,
    collapsed: Boolean = false,
) {
    val shape = RoundedCornerShape(12.dp)
    val accent = MaterialTheme.colorScheme.primary
    val wash by animateColorAsState(
        targetValue = if (selected) {
            accent.copy(alpha = Chrome.SoftWash)
        } else {
            Color.Transparent
        },
        label = "drawer_nav_wash"
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "drawer_nav_icon"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (collapsed) 0.dp else 10.dp)
            .height(44.dp)
            .clip(shape)
            .background(wash)
            .clickable(onClick = onClick)
            .padding(
                start = if (collapsed) 0.dp else 12.dp,
                end = if (collapsed) 0.dp else 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.spacedBy(12.dp)
    ) {
        if (!collapsed) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(accent)
                )
            } else {
                Spacer(modifier = Modifier.width(2.dp))
            }
        }

        Icon(
            imageVector = if (selected) selectedIcon else icon,
            contentDescription = label,
            modifier = Modifier.size(22.dp),
            tint = iconTint
        )

        if (!collapsed) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    letterSpacing = (-0.15).sp
                ),
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        if (!collapsed && count != null && count > 0) {
            Box(
                modifier = Modifier
                    .height(22.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (selected) accent.copy(alpha = 0.2f)
                        else accent.copy(alpha = 0.08f)
                    )
                    .padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    ),
                    color = if (selected) {
                        accent
                    } else {
                        accent.copy(alpha = 0.85f)
                    }
                )
            }
        }
    }
}

@Composable
fun SideDrawerSectionLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text.uppercase(),
        style = AppType.chromeLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 22.dp, end = 12.dp, top = 4.dp, bottom = 6.dp)
    )
}

@Composable
fun SideDrawerAccountRow(
    email: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = Chrome.SelectedWash)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = email.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Signed in",
                style = AppType.chromeLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = email,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    letterSpacing = (-0.15).sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
