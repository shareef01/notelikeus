package com.aus.notelikeus.ui.main.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.aus.notelikeus.domain.model.AccentColor
import com.aus.notelikeus.domain.model.ThemeBase
import com.aus.notelikeus.domain.model.ThemePreference
import com.aus.notelikeus.ui.theme.AppType
import com.aus.notelikeus.ui.theme.Chrome
import com.aus.notelikeus.ui.theme.Motion
import com.aus.notelikeus.ui.theme.Radius
import com.aus.notelikeus.ui.theme.Size
import com.aus.notelikeus.ui.theme.Spacing
import com.aus.notelikeus.ui.theme.colorSchemeFor
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.accent_blue
import notelikeus.composeapp.generated.resources.accent_green
import notelikeus.composeapp.generated.resources.accent_label
import notelikeus.composeapp.generated.resources.accent_neutral
import notelikeus.composeapp.generated.resources.theme_auto
import notelikeus.composeapp.generated.resources.theme_base_label
import notelikeus.composeapp.generated.resources.theme_dark
import notelikeus.composeapp.generated.resources.theme_light
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private fun themeBaseLabelRes(base: ThemeBase): StringResource = when (base) {
    ThemeBase.SYSTEM -> Res.string.theme_auto
    ThemeBase.LIGHT -> Res.string.theme_light
    ThemeBase.DARK -> Res.string.theme_dark
}

private fun accentLabelRes(accent: AccentColor): StringResource = when (accent) {
    AccentColor.NEUTRAL -> Res.string.accent_neutral
    AccentColor.BLUE -> Res.string.accent_blue
    AccentColor.GREEN -> Res.string.accent_green
}

/**
 * Base theme and accent, as two short rows of swatches.
 *
 * Every swatch previews the **real scheme**: its fill and dot come from [colorSchemeFor] applied
 * to the preference that tapping it would produce. The previous picker painted them from
 * seventeen hardcoded colour literals, which is how a preview drifts from the thing it previews —
 * nothing connected `Color(0xFF0F1610)` to the Forest surface except someone having typed both.
 *
 * The accent row reflects the chosen base too, so picking green on Light shows the light green
 * rather than the dark one.
 */
@Composable
fun ThemePicker(
    preference: ThemePreference,
    onBaseChange: (ThemeBase) -> Unit,
    onAccentChange: (AccentColor) -> Unit,
    modifier: Modifier = Modifier
) {
    val systemDark = isSystemInDarkTheme()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        PickerRowLabel(stringResource(Res.string.theme_base_label))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ThemeBase.entries.forEach { base ->
                val candidate = preference.copy(base = base)
                // SYSTEM resolves two ways, so its swatch shows both halves rather than
                // committing to whichever the device happens to be set to right now.
                val isSystem = base == ThemeBase.SYSTEM
                val scheme = colorSchemeFor(
                    if (isSystem) candidate.copy(base = ThemeBase.LIGHT) else candidate,
                    systemDark
                )
                val altSurface = if (isSystem) {
                    colorSchemeFor(candidate.copy(base = ThemeBase.DARK), systemDark).surface
                } else {
                    null
                }
                SwatchItem(
                    label = stringResource(themeBaseLabelRes(base)),
                    surface = scheme.surface,
                    surfaceAlt = altSurface,
                    dot = scheme.primary,
                    selected = preference.base == base,
                    onClick = { onBaseChange(base) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        PickerRowLabel(stringResource(Res.string.accent_label))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            AccentColor.entries.forEach { accent ->
                val scheme = colorSchemeFor(preference.copy(accent = accent), systemDark)
                SwatchItem(
                    label = stringResource(accentLabelRes(accent)),
                    surface = scheme.surface,
                    surfaceAlt = null,
                    dot = scheme.primary,
                    selected = preference.accent == accent,
                    onClick = { onAccentChange(accent) },
                    modifier = Modifier.weight(1f),
                    prominentDot = true
                )
            }
        }
    }
}

@Composable
private fun PickerRowLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = AppType.chromeLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SwatchItem(
    label: String,
    surface: Color,
    surfaceAlt: Color?,
    dot: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Draw the dot large and centred rather than small and low.
     *
     * The accent row needs it. Every dark scheme's surface is near-black by design, so an accent
     * swatch filled with its surface reads as "another black circle" and the hue survives only in
     * a 8dp dot. The base row does not need it: those swatches differ in their fill.
     */
    prominentDot: Boolean = false
) {
    val wash by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = Chrome.SoftWash)
        } else {
            Color.Transparent
        },
        animationSpec = Motion.standard(),
        label = "swatch_wash"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (selected) Spacing.xxs else Spacing.hairline,
        animationSpec = Motion.quick(),
        label = "swatch_border_width"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = Chrome.SelectedBorder)
        },
        animationSpec = Motion.standard(),
        label = "swatch_border_color"
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            .background(wash)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        val fill = if (surfaceAlt != null) {
            Modifier.background(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0.0f to surface,
                        0.495f to surface,
                        0.505f to surfaceAlt,
                        1.0f to surfaceAlt
                    ),
                    start = Offset.Zero,
                    end = Offset(100f, 100f)
                )
            )
        } else {
            Modifier.background(surface)
        }

        Box(
            modifier = Modifier
                .size(Size.touchTarget)
                .clip(CircleShape)
                .border(width = borderWidth, color = borderColor, shape = CircleShape)
                .then(fill),
            contentAlignment = Alignment.Center
        ) {
            if (!selected) {
                Box(
                    modifier = if (prominentDot) {
                        Modifier.size(Size.iconLarge)
                    } else {
                        Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = -Spacing.sm)
                            .size(Spacing.sm)
                    }
                        .clip(CircleShape)
                        .background(dot)
                )
            } else {
                // The check sits on the swatch's own surface, so its disc takes that scheme's
                // primary and the tick takes that scheme's surface. The old picker carried a
                // hardcoded `lightCheck` boolean per theme to approximate this by hand.
                Box(
                    modifier = Modifier
                        .size(Size.icon)
                        .clip(CircleShape)
                        .background(dot),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(Size.iconTiny),
                        tint = surface
                    )
                }
            }
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 12.sp,
                letterSpacing = (-0.1).sp
            ),
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
