package com.aus.notelikeus.contract

import androidx.compose.ui.graphics.toArgb
import com.aus.notelikeus.ui.theme.NOTE_COLOR_KEYWORDS
import com.aus.notelikeus.ui.theme.NOTE_COLOR_NAME_RESOURCES
import com.aus.notelikeus.ui.theme.NOTE_COLOR_OPTIONS
import com.aus.notelikeus.ui.theme.NO_NOTE_COLOR
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `contracts/design/note-colors.json` against the native palette, `color:` keywords and the English
 * names in strings.xml. The web client asserts the same file against `theme/colors.ts`
 * (noteColors.contract.test.ts), so a swatch has one name, one keyword and one ARGB everywhere.
 */
class NoteColorsContractTest {

    private val contract = Json.parseToJsonElement(ContractFixtures.read("design/note-colors.json")).jsonObject
    private val entries = contract.getValue("colors").jsonArray.map { it.jsonObject }

    private fun argb(hex: String): Int = hex.removePrefix("0x").toLong(16).toInt()

    /** The English strings the names resolve to; read from source so the test needs no Compose runtime. */
    private val englishStrings: Map<String, String> by lazy {
        val xml = ContractFixtures.read("../composeApp/src/commonMain/composeResources/values/strings.xml")
        Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `contract lists every palette entry in order`() {
        assertEquals(NOTE_COLOR_OPTIONS.indices.toList(), entries.map { it.getValue("index").jsonPrimitive.int })
    }

    @Test
    fun `stored ARGB values match the palette`() {
        val expected = entries.map { argb(it.str("light")) to argb(it.str("dark")) }
        val actual = NOTE_COLOR_OPTIONS.map { it.light.toArgb() to it.dark.toArgb() }
        assertEquals(expected, actual)
    }

    @Test
    fun `search keywords match the palette`() {
        assertEquals(entries.map { it.str("keyword") }, NOTE_COLOR_KEYWORDS)
    }

    @Test
    fun `spoken names match strings xml`() {
        val actual = NOTE_COLOR_NAME_RESOURCES.map { englishStrings[it.key] }
        assertEquals(entries.map { it.str("name") }, actual)
    }

    @Test
    fun `a missing colour reads as no colour`() {
        assertEquals(argb(contract.getValue("noColor").jsonPrimitive.content), NO_NOTE_COLOR)
        assertEquals(NO_NOTE_COLOR, NOTE_COLOR_OPTIONS[0].light.toArgb())
    }

    private fun kotlinx.serialization.json.JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
}
