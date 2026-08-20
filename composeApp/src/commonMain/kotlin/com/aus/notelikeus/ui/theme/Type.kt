package com.aus.notelikeus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import notelikeus.composeapp.generated.resources.Res
import notelikeus.composeapp.generated.resources.inter_bold
import notelikeus.composeapp.generated.resources.inter_medium
import notelikeus.composeapp.generated.resources.inter_regular
import notelikeus.composeapp.generated.resources.inter_semibold
import org.jetbrains.compose.resources.Font

/**
 * Inter, actually loaded.
 *
 * This used to be `val InterFontFamily = FontFamily.Default` — the name said Inter, the renderer
 * drew Roboto on Android and Segoe UI on Windows, while the web client shipped real Inter. Three
 * clients, three typefaces, one token pretending otherwise. It also meant the negative letter
 * spacing throughout this file was Inter's metrics applied to fonts with different ones, which is
 * why headings read slightly tight.
 *
 * Four weights are shipped — 400/500/600/700 — because those are the four the UI actually renders
 * (`FontWeight.SemiBold` 25 times, `Medium` 14, `Bold` 12, `Normal` 6). Black is not shipped: its
 * only references were `displayLarge` and `displaySmall` below, and no screen uses either role.
 *
 * Static weights rather than the variable font on purpose. `InterVariable.ttf` would be roughly
 * half the size in one file, but the `wght` axis has to be applied by the platform, and Android's
 * `Typeface` and Skia's do it through different mechanisms — the plausible failure is that both
 * targets render 400 with synthetic emboldening, which looks *almost* right and would be easy to
 * ship without noticing. Four static files cannot fail that way.
 */
@Composable
private fun interFontFamily(): FontFamily {
    val regular = Font(Res.font.inter_regular, FontWeight.Normal)
    val medium = Font(Res.font.inter_medium, FontWeight.Medium)
    val semiBold = Font(Res.font.inter_semibold, FontWeight.SemiBold)
    val bold = Font(Res.font.inter_bold, FontWeight.Bold)
    // Remembered so the FontFamily keeps its identity across recompositions. Without this a new
    // instance every frame would change Typography's identity and recompose the entire tree.
    return remember(regular, medium, semiBold, bold) {
        FontFamily(regular, medium, semiBold, bold)
    }
}

private fun inter(
    family: FontFamily,
    weight: FontWeight,
    size: Float,
    lineHeight: Float,
    letterSpacing: Float = 0f
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp
)

/**
 * The Material 3 type scale, in Inter.
 *
 * Built inside composition because the font resources can only be read there. [NotelikeusTheme]
 * calls this once and hands the result to `MaterialTheme`; nothing else should.
 */
@Composable
fun rememberNotelikeusTypography(): Typography {
    val family = interFontFamily()
    return remember(family) {
        Typography(
            // displayLarge/displaySmall are declared for completeness but no screen uses them.
            // They ask for Bold rather than Black because Black is not among the shipped weights,
            // and a request for an absent weight is silently satisfied by the nearest one — better
            // to say what will actually render.
            displayLarge = inter(family, FontWeight.Bold, 32f, 40f, -1f),
            displaySmall = inter(family, FontWeight.Bold, 28f, 36f, -0.75f),
            headlineMedium = inter(family, FontWeight.Bold, 24f, 32f, -0.5f),
            headlineSmall = inter(family, FontWeight.SemiBold, 20f, 28f, -0.25f),
            titleLarge = inter(family, FontWeight.SemiBold, 20f, 28f, -0.5f),
            titleMedium = inter(family, FontWeight.SemiBold, 16f, 24f, -0.25f),
            titleSmall = inter(family, FontWeight.Medium, 14f, 20f, 0f),
            bodyLarge = inter(family, FontWeight.Normal, 16f, 24f, 0.25f),
            bodyMedium = inter(family, FontWeight.Normal, 14f, 20f, 0.15f),
            bodySmall = inter(family, FontWeight.Normal, 12f, 16f, 0.1f),
            labelLarge = inter(family, FontWeight.SemiBold, 14f, 20f, 0.5f),
            labelMedium = inter(family, FontWeight.Medium, 12f, 16f, 0.5f),
            labelSmall = inter(family, FontWeight.Medium, 11f, 16f, 0.5f)
        )
    }
}

/**
 * Type roles the Material scale has no slot for.
 *
 * Each derives from a `MaterialTheme.typography` entry rather than being built from scratch, so
 * the font family arrives automatically and cannot drift from the scale. That is the whole reason
 * these are composable getters and not top-level `val`s: a `val` would have to name a family at
 * file scope, which is exactly how `InterFontFamily = FontFamily.Default` went unnoticed.
 */
object AppType {

    /** Note card title — 18/28, matching the web client's `note-title`. */
    val noteCardTitle: TextStyle
        @Composable get() = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            lineHeight = 28.sp,
            letterSpacing = (-0.36).sp
        )

    /** Note card body preview — 15/24, matching the web client's `note-body`. */
    val noteCardBody: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.075.sp
        )

    /** Chrome overline — section headers, drawer labels. Matches web `section-label`. */
    val chromeLabel: TextStyle
        @Composable get() = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.88.sp
        )

    /** Editor title. Sized for continuity with [noteCardTitle] across the open transition. */
    val editorTitle: TextStyle
        @Composable get() = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            letterSpacing = (-0.66).sp
        )

    /** Editor body. */
    val editorBody: TextStyle
        @Composable get() = MaterialTheme.typography.bodyLarge.copy(
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.8.sp,
            letterSpacing = 0.16.sp
        )
}
