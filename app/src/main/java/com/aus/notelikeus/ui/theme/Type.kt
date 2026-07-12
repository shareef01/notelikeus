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
    bodyLarge = inter(FontWeight.Normal, 16f, 26f, 0.15f),
    bodyMedium = inter(FontWeight.Normal, 14f, 22f, 0.1f),
    bodySmall = inter(FontWeight.Normal, 12f, 18f, 0.1f),
    labelLarge = inter(FontWeight.SemiBold, 14f, 20f, 0.3f),
    labelMedium = inter(FontWeight.Medium, 12f, 16f, 0.3f),
    labelSmall = inter(FontWeight.Medium, 11f, 14f, 0.3f)
)

/**
 * Card typography — optimized for scanability at a glance.
 */
val NoteCardTitleStyle = inter(FontWeight.SemiBold, 17f, 23f, -0.4f)

/**
 * Card body — slightly tighter than bodyLarge for card density.
 */
val NoteCardBodyStyle = inter(FontWeight.Normal, 14f, 21f, 0f)

/**
 * Editor typography — larger body for comfortable long-form writing.
 */
val EditorTitleStyle = NoteCardTitleStyle

val EditorBodyStyle = inter(FontWeight.Normal, 16f, 26f, 0f)
