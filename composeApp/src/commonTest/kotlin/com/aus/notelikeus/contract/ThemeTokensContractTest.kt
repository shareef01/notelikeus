package com.aus.notelikeus.contract

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.aus.notelikeus.domain.model.AccentColor
import com.aus.notelikeus.domain.model.ThemeBase
import com.aus.notelikeus.domain.model.ThemePreference
import com.aus.notelikeus.ui.theme.colorSchemeFor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `contracts/design/theme-colors.json` against [colorSchemeFor]. The web client asserts the same file
 * against globals.css (themeTokens.test.ts), so a theme renders the same colours on every client.
 */
class ThemeTokensContractTest {

    private val contract = Json.parseToJsonElement(ContractFixtures.read("design/theme-colors.json")).jsonObject

    private fun Color.hex(): String =
        "#" + (toArgb() and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    private fun ColorScheme.roles(): Map<String, String> = mapOf(
        "background" to background.hex(),
        "surface" to surface.hex(),
        "surfaceVariant" to surfaceVariant.hex(),
        "onSurface" to onSurface.hex(),
        "secondary" to secondary.hex(),
        "muted" to onSurfaceVariant.hex(),
        "outline" to outline.hex(),
    )

    @Test
    fun `every theme matches the contract`() {
        val mismatches = contract.getValue("themes").jsonObject.mapNotNull { (name, entry) ->
            val compose = entry.jsonObject.getValue("compose").jsonObject
            val preference = ThemePreference(
                base = ThemeBase.valueOf(compose.getValue("base").jsonPrimitive.content),
                amoled = compose.getValue("amoled").jsonPrimitive.boolean,
                accent = AccentColor.valueOf(compose.getValue("accent").jsonPrimitive.content),
            )
            val expected = (entry.jsonObject.getValue("colors") as JsonObject).mapValues { it.value.jsonPrimitive.content }
            val actual = colorSchemeFor(preference, systemDark = preference.base == ThemeBase.DARK).roles()
            if (actual == expected) null else "$name: expected $expected, got $actual"
        }
        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun `light accents tint the primary`() {
        val accents = contract.getValue("lightAccentPrimary").jsonObject
        for ((accent, key) in listOf(AccentColor.BLUE to "blue", AccentColor.GREEN to "green")) {
            val scheme = colorSchemeFor(ThemePreference(base = ThemeBase.LIGHT, accent = accent), systemDark = false)
            assertEquals(accents.getValue(key).jsonPrimitive.content, scheme.primary.hex(), "light $key")
        }
    }
}
