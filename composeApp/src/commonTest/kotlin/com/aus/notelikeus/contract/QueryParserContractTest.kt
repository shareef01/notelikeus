package com.aus.notelikeus.contract

import com.aus.notelikeus.domain.query.NoteQueryParser
import com.aus.notelikeus.domain.query.ParsedQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `contracts/query/parser-vectors.json` against [NoteQueryParser]. The web client runs the same file
 * through its port (`noteQueryParser.contract.test.ts`), so `is:pinned` means one thing on every
 * client (NL-04).
 */
class QueryParserContractTest {

    private val contract = Json.parseToJsonElement(ContractFixtures.read("query/parser-vectors.json")).jsonObject
    private val clock = contract.getValue("clock").jsonObject
    private val todayStart = clock.getValue("todayStart").jsonPrimitive.long
    private val dayMillis = clock.getValue("dayMillis").jsonPrimitive.long

    private fun dayStart(offset: Int): Long = todayStart + offset * dayMillis

    /** UTC midnight of a civil date, or null when it does not exist; the contract's clock. */
    private fun utcDayStart(year: Int, month: Int, day: Int): Long? {
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if (day > days[month - 1]) return null
        // Days from 1970-01-01 to the civil date (Howard Hinnant's days_from_civil).
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (era * 146097L + doe - 719468) * dayMillis
    }

    private fun JsonElement.longOrNull(): Long? = if (this is JsonNull) null else jsonPrimitive.long
    private fun JsonElement.stringOrNull(): String? = if (this is JsonNull) null else jsonPrimitive.content
    private fun JsonElement.strings(): List<String> = (this as JsonArray).map { it.jsonPrimitive.content }

    private fun ParsedQuery.normal(): Map<String, Any?> = mapOf(
        "text" to text,
        "labelNames" to labelNames,
        "colorNames" to colorNames,
        "flags" to flags.map { it.name }.sorted(),
        "scope" to scope?.name,
        "dateField" to dateField?.name,
        "before" to before,
        "after" to after,
        "unknown" to unknown,
    )

    @Test
    fun `every vector parses as the contract says`() {
        val mismatches = contract.getValue("cases").jsonArray.mapNotNull { case ->
            val input = case.jsonObject.getValue("input").jsonPrimitive.content
            val e = case.jsonObject.getValue("expected").jsonObject
            val expected = mapOf(
                "text" to e.getValue("text").jsonPrimitive.content,
                "labelNames" to e.getValue("labelNames").strings(),
                "colorNames" to e.getValue("colorNames").strings(),
                "flags" to e.getValue("flags").strings(),
                "scope" to e.getValue("scope").stringOrNull(),
                "dateField" to e.getValue("dateField").stringOrNull(),
                "before" to e.getValue("before").longOrNull(),
                "after" to e.getValue("after").longOrNull(),
                "unknown" to e.getValue("unknown").strings(),
            )
            val actual = NoteQueryParser.parse(input, ::dayStart, ::utcDayStart).normal()
            if (actual == expected) null else "\"$input\": expected $expected, got $actual"
        }
        assertEquals(emptyList(), mismatches)
    }
}
