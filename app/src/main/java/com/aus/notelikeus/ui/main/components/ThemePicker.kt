package com.aus.notelikeus.ui.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aus.notelikeus.R
import com.aus.notelikeus.domain.model.AppTheme
import com.aus.notelikeus.domain.model.appThemeLabelRes

private data class ThemeSwatchMeta(
    val surface: Color,
    val accent: Color,
    val surfaceAlt: Color? = null,
    val lightCheck: Boolean = false,
)

private val ThemeOrder = listOf(
    AppTheme.AUTO,
    AppTheme.LIGHT,
    AppTheme.DARK,
    AppTheme.TRUE_DARK,
    AppTheme.MIDNIGHT,
    AppTheme.FOREST,
)

private val ThemeSwatches: Map<AppTheme, ThemeSwatchMeta> = mapOf(
    AppTheme.AUTO to ThemeSwatchMeta(
        surface = Color(0xFFF7F7F7),
        surfaceAlt = Color(0xFF1C1C1C),
        accent = Color(0xFF8B8B8B),
        lightCheck = true,
    ),
    AppTheme.LIGHT to ThemeSwatchMeta(
        surface = Color(0xFFFFFFFF),
        accent = Color(0xFF111111),
        lightCheck = true,
    ),
    AppTheme.DARK to ThemeSwatchMeta(
        surface = Color(0xFF1C1C1C),
        accent = Color(0xFFF5F5F5),
    ),
    AppTheme.TRUE_DARK to ThemeSwatchMeta(
        surface = Color(0xFF000000),
        accent = Color(0xFFFFFFFF),
    ),
    AppTheme.MIDNIGHT to ThemeSwatchMeta(
        surface = Color(0xFF0C111C),
        accent = Color(0xFF8EB6FF),
    ),
    AppTheme.FOREST to ThemeSwatchMeta(
        surface = Color(0xFF0F1610),
        accent = Color(0xFF8FD49A),
    ),
)

@Composable
fun ThemePicker(
    value: AppTheme,
    onChange: (AppTheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ThemeOrder.chunked(3).forEach { rowThemes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                rowThemes.forEach { theme ->
                    ThemePickerItem(
                        label = stringResource(appThemeLabelRes(theme)),
                        meta = ThemeSwatches.getValue(theme),
                        selected = value == theme,
                        onClick = { onChange(theme) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemePickerItem(
    label: String,
    meta: ThemeSwatchMeta,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                else Color.Transparent
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val fillModifier = if (meta.surfaceAlt != null) {
            Modifier.background(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0.0f to meta.surface,
                        0.495f to meta.surface,
                        0.505f to meta.surfaceAlt,
                        1.0f to meta.surfaceAlt,
                    ),
                    start = Offset.Zero,
                    end = Offset(100f, 100f),
                )
            )
        } else {
            Modifier.background(meta.surface)
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                    },
                    shape = CircleShape
                )
                .then(fillModifier),
            contentAlignment = Alignment.Center
        ) {
            if (meta.surfaceAlt != null && !selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = 10.dp, y = 10.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF111111))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-10).dp, y = (-10).dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF5F5F5))
                )
            } else if (!selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (-8).dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(meta.accent)
                )
            }

            if (selected) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (meta.lightCheck) Color(0xFF171717) else Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = if (meta.lightCheck) Color.White else Color(0xFF171717)
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
