package com.aus.notelikeus.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.aus.notelikeus.R

/**
 * Premium Typography System
 * Font: Inter (Geometric/Highly Readable)
 */
val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val InterFontFamily = FontFamily(
    Font(googleFont = GoogleFont("Inter"), fontProvider = provider)
)

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
 * Constraint: Premium Typography
 * Note Titles: SemiBold, 18.sp, -0.5.sp kerning.
 */
val NoteCardTitleStyle = inter(FontWeight.SemiBold, 18f, 25f, -0.5f)

/**
 * Constraint: Body Text
 * Body: Regular, 14.sp, 1.4em line height (19.6sp).
 */
val NoteCardBodyStyle = inter(FontWeight.Normal, 14f, 19.6f, 0f)

/**
 * Editor typography — aligned with card styles for shared-element continuity.
 */
val EditorTitleStyle = NoteCardTitleStyle

val EditorBodyStyle = inter(FontWeight.Normal, 16f, 22.4f, 0f)
