package com.aus.notelikeus.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
val InterFontFamily = FontFamily.Default

private fun inter(
    weight: FontWeight,
    size: Float,
    lineHeight: Float,
    letterSpacing: Float = 0f
) = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp
)

val Typography = Typography(
    displayLarge = inter(FontWeight.Black, 32f, 40f, -1f),
    displaySmall = inter(FontWeight.Black, 28f, 36f, -0.75f),
    headlineMedium = inter(FontWeight.Bold, 24f, 32f, -0.5f),
    headlineSmall = inter(FontWeight.SemiBold, 20f, 28f, -0.25f),
    titleLarge = inter(FontWeight.SemiBold, 20f, 28f, -0.5f),
    titleMedium = inter(FontWeight.SemiBold, 16f, 24f, -0.25f),
    titleSmall = inter(FontWeight.Medium, 14f, 20f, 0f),
    bodyLarge = inter(FontWeight.Normal, 16f, 24f, 0.25f),
    bodyMedium = inter(FontWeight.Normal, 14f, 20f, 0.15f),
    bodySmall = inter(FontWeight.Normal, 12f, 16f, 0.1f),
    labelLarge = inter(FontWeight.SemiBold, 14f, 20f, 0.5f),
    labelMedium = inter(FontWeight.Medium, 12f, 16f, 0.5f),
    labelSmall = inter(FontWeight.Medium, 11f, 16f, 0.5f)
)

/**
 * Note card title — matches web note-title: 18px / 28px line-height / -0.02em / SemiBold.
 */
val NoteCardTitleStyle = inter(FontWeight.SemiBold, 18f, 28f, -0.36f)

/**
 * Note card body preview — matches web note-body: 15px / 1.6 line-height / 0.005em.
 */
val NoteCardBodyStyle = inter(FontWeight.Normal, 15f, 24f, 0.075f)

/** Chrome overline — matches web `section-label`: 11px / 16px / 0.08em / SemiBold. */
val ChromeLabelStyle = inter(FontWeight.SemiBold, 11f, 16f, 0.88f)

/**
 * Editor typography — aligned with card styles for shared-element continuity.
 */
val EditorTitleStyle = inter(FontWeight.SemiBold, 22f, 28f, -0.66f)

val EditorBodyStyle = inter(FontWeight.Normal, 16f, 24.8f, 0.16f)
