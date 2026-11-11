// ver 1.4.0 | 2026-11-11 22:30
package app.motioncare.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GleoCallTest {

    // the schema file is read from the module folder; Gradle runs unit tests there
    private val schemaFile = File("src/main/assets/schema.json")
    private val manifestFile = File("src/main/AndroidManifest.xml")

    @Test
    fun each_call_is_found_by_its_action_name() {
        assertEquals(GleoCall.RELIEF, GleoCall.fromAction("MOTION_SICKNESS_RELIEF"))
        assertEquals(GleoCall.RECOVERED, GleoCall.fromAction("MOTION_SICKNESS_RECOVERED"))
        assertEquals(GleoCall.REST_STOP, GleoCall.fromAction("REST_STOP_WAYPOINT"))
    }

    @Test
    fun each_call_is_also_found_with_the_package_name_in_front() {
        assertEquals(GleoCall.RELIEF, GleoCall.fromAction("app.motioncare.MOTION_SICKNESS_RELIEF"))
        assertEquals(GleoCall.RECOVERED, GleoCall.fromAction("app.motioncare.MOTION_SICKNESS_RECOVERED"))
        assertEquals(GleoCall.REST_STOP, GleoCall.fromAction("app.motioncare.REST_STOP_WAYPOINT"))
    }

    @Test
    fun a_prefix_from_another_package_is_ignored() {
        assertNull(GleoCall.fromAction("other.app.MOTION_SICKNESS_RELIEF"))
        assertNull(GleoCall.fromAction("app.motioncare."))
    }

    @Test
    fun unknown_or_missing_actions_are_ignored() {
        assertNull(GleoCall.fromAction("ADD_REMINDER"))
        assertNull(GleoCall.fromAction(""))
        assertNull(GleoCall.fromAction(null))
    }

    @Test
    fun action_names_are_upper_snake_case_and_unique() {
        val names = GleoCall.values().map { it.action }
        assertEquals(names.size, names.toSet().size)
        names.forEach { assertTrue(it, Regex("[A-Z]+(_[A-Z]+)*").matches(it)) }
    }

    @Test
    fun at_most_three_voice_calls() {
        assertTrue(GleoCall.values().size <= 3)
    }

    @Test
    fun no_call_needs_a_screen() {
        GleoCall.values().forEach { assertFalse(it.needsScreen) }
    }

    // ---- the rest-area call ----

    @Test
    fun rest_action_and_place_are_read_from_plain_and_escaped_text() {
        assertEquals(RestAction.ADD, GleoRest.action("{\"action\":\"ADD\",\"place\":\"평택\"}"))
        assertEquals("평택", GleoRest.place("{\"action\":\"ADD\",\"place\":\"평택\"}"))
        assertEquals(RestAction.REMOVE, GleoRest.action("{ \"action\" : \"remove\" }"))
        assertEquals(RestAction.REPLACE, GleoRest.action("{\\\"action\\\":\\\"REPLACE\\\",\\\"place\\\":\\\"안성\\\"}"))
        assertEquals("안성", GleoRest.place("{\\\"action\\\":\\\"REPLACE\\\",\\\"place\\\":\\\"안성\\\"}"))
    }

    @Test
    fun an_escaped_korean_place_is_decoded() {
        assertEquals("평택", GleoRest.place("{\"place\":\"\\uD3C9\\uD0DD\"}"))
    }

    @Test
    fun a_missing_or_blank_place_gives_nothing_and_a_missing_action_with_a_place_is_add() {
        assertNull(GleoRest.place("{\"action\":\"REMOVE\"}"))
        assertNull(GleoRest.place("{\"place\":\"  \"}"))
        assertNull(GleoRest.place(null))
        assertEquals(RestAction.ADD, GleoRest.action("{\"place\":\"평택\"}"))
        assertNull(GleoRest.action("{}"))
        assertNull(GleoRest.action("{\"action\":\"MOVE\"}"))
    }

    @Test
    fun schema_rest_actions_are_exactly_the_ones_in_the_code() {
        val at = schema().indexOf("\"name\": \"REST_STOP_WAYPOINT\"")
        assertTrue(at >= 0)
        val enumText = Regex("\"enum\"\\s*:\\s*\\[([^\\]]*)\\]").find(schema().substring(at))!!.groupValues[1]
        val inFile = Regex("\"([A-Z_]+)\"").findAll(enumText).map { it.groupValues[1] }.toSet()
        assertEquals(RestAction.entries.map { it.name }.toSet(), inFile)
    }

    // ---- the schema file the Gleo AI app reads must match the code ----

    private fun schema(): String {
        assertTrue("schema.json is missing: ${schemaFile.absolutePath}", schemaFile.exists())
        return schemaFile.readText()
    }

    @Test
    fun schema_names_exactly_the_calls_in_the_code() {
        val inFile = Regex("\"name\"\\s*:\\s*\"([A-Z_]+)\"").findAll(schema()).map { it.groupValues[1] }.toList()
        assertEquals(GleoCall.values().map { it.action }.sorted(), inFile.sorted())
    }

    @Test
    fun schema_components_match_needsScreen() {
        val text = schema()
        GleoCall.values().forEach { call ->
            val at = text.indexOf("\"name\": \"${call.action}\"")
            assertTrue("no entry for ${call.action}", at >= 0)
            val tail = text.substring(at)
            val comp = Regex("\"component\"\\s*:\\s*\"(\\w+)\"").find(tail)!!.groupValues[1]
            assertEquals(call.action, if (call.needsScreen) "activity" else "service", comp)
        }
    }

    @Test
    fun schema_top_level_fields_are_present_and_the_package_matches() {
        val text = schema()
        assertTrue(text.contains("\"packageName\": \"app.motioncare\""))
        listOf("appDescription", "invokeWord", "version", "actionCalls").forEach {
            assertTrue(it, text.contains("\"$it\""))
        }
    }

    @Test
    fun schema_text_is_english_for_every_description() {
        val descriptions = Regex("\"(?:app)?[dD]escription\"\\s*:\\s*\"([^\"]*)\"").findAll(schema()).map { it.groupValues[1] }.toList()
        assertTrue(descriptions.size >= 4) // app + three functions
        descriptions.forEach { d -> assertTrue(d, d.all { it.code < 128 }) }
    }

    @Test
    fun schema_has_no_example_phrases_in_descriptions() {
        val descriptions = Regex("\"(?:app)?[dD]escription\"\\s*:\\s*\"([^\"]*)\"").findAll(schema()).map { it.groupValues[1].lowercase() }.toList()
        descriptions.forEach { d ->
            assertTrue(d, !d.contains("for example"))
            assertTrue(d, !d.contains("e.g."))
            assertTrue(d, !d.contains("such as"))
        }
    }

    @Test
    fun manifest_lists_an_action_for_every_call() {
        assertTrue(manifestFile.exists())
        val text = manifestFile.readText()
        GleoCall.values().forEach { call ->
            assertTrue(call.action, text.contains("<action android:name=\"${call.action}\""))
            val prefixed = GleoCall.PACKAGE_PREFIX + call.action
            assertTrue(prefixed, text.contains("<action android:name=\"$prefixed\""))
        }
    }

    @Test
    fun manifest_lists_the_boot_permission_and_receiver() {
        val text = manifestFile.readText()
        assertTrue(text.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(text.contains("android.intent.action.BOOT_COMPLETED"))
    }

    // ---- the seat word of the relief call ----

    @Test
    fun every_single_seat_value_names_its_seat() {
        assertEquals(setOf(Seat.ROW_1_RIGHT), GleoSeat.fromValue("FRONT_PASSENGER"))
        assertEquals(setOf(Seat.ROW_2_LEFT), GleoSeat.fromValue("SECOND_ROW_DRIVER_SIDE"))
        assertEquals(setOf(Seat.ROW_2_CENTER), GleoSeat.fromValue("SECOND_ROW_CENTER"))
        assertEquals(setOf(Seat.ROW_2_RIGHT), GleoSeat.fromValue("SECOND_ROW_PASSENGER_SIDE"))
        assertEquals(setOf(Seat.ROW_3_LEFT), GleoSeat.fromValue("THIRD_ROW_DRIVER_SIDE"))
        assertEquals(setOf(Seat.ROW_3_CENTER), GleoSeat.fromValue("THIRD_ROW_CENTER"))
        assertEquals(setOf(Seat.ROW_3_RIGHT), GleoSeat.fromValue("THIRD_ROW_PASSENGER_SIDE"))
    }

    @Test
    fun every_group_value_names_its_seats() {
        assertEquals(setOf(Seat.ROW_2_LEFT, Seat.ROW_2_CENTER, Seat.ROW_2_RIGHT), GleoSeat.fromValue("SECOND_ROW_ALL"))
        assertEquals(setOf(Seat.ROW_3_LEFT, Seat.ROW_3_CENTER, Seat.ROW_3_RIGHT), GleoSeat.fromValue("THIRD_ROW_ALL"))
        assertEquals(Seat.entries.filter { it.row >= 2 }.toSet(), GleoSeat.fromValue("ALL_REAR_SEATS"))
        assertEquals(setOf(Seat.ROW_1_RIGHT, Seat.ROW_2_RIGHT, Seat.ROW_3_RIGHT), GleoSeat.fromValue("PASSENGER_SIDE_FRONT_AND_REAR"))
        assertEquals(setOf(Seat.ROW_2_LEFT, Seat.ROW_3_LEFT), GleoSeat.fromValue("DRIVER_SIDE_REAR"))
        assertEquals(Seat.entries.filter { it != Seat.ROW_1_LEFT }.toSet(), GleoSeat.fromValue("ALL_PASSENGER_SEATS"))
    }

    @Test
    fun every_seat_but_the_drivers_can_be_named_and_no_value_names_the_driver() {
        val named = GleoSeat.values.values.flatten().toSet()
        Seat.entries.filter { it != Seat.ROW_1_LEFT }.forEach { assertTrue(it.key, it in named) }
        assertFalse(Seat.ROW_1_LEFT in named)
    }

    @Test
    fun an_unknown_or_missing_seat_value_is_ignored() {
        assertTrue(GleoSeat.fromValue("DRIVER").isEmpty())
        assertTrue(GleoSeat.fromValue("").isEmpty())
        assertTrue(GleoSeat.fromValue(null).isEmpty())
    }

    @Test
    fun the_seat_is_read_from_plain_and_escaped_parameter_text() {
        assertEquals(setOf(Seat.ROW_3_CENTER), GleoSeat.fromParamText("{\"seat\":\"THIRD_ROW_CENTER\"}"))
        assertEquals(setOf(Seat.ROW_3_CENTER), GleoSeat.fromParamText("{ \"seat\" : \"third_row_center\" }"))
        assertEquals(setOf(Seat.ROW_1_RIGHT), GleoSeat.fromParamText("{\\\"seat\\\":\\\"FRONT_PASSENGER\\\"}"))
        assertEquals(setOf(Seat.ROW_2_LEFT, Seat.ROW_3_LEFT), GleoSeat.fromParamText("{\"seat\":\"DRIVER_SIDE_REAR\"}"))
    }

    @Test
    fun parameter_text_without_a_known_seat_gives_nothing() {
        assertTrue(GleoSeat.fromParamText("{}").isEmpty())
        assertTrue(GleoSeat.fromParamText("").isEmpty())
        assertTrue(GleoSeat.fromParamText(null).isEmpty())
        assertTrue(GleoSeat.fromParamText("{\"seat\":\"BACK\"}").isEmpty())
        assertTrue(GleoSeat.fromParamText("{\"title\":\"seat\"}").isEmpty())
    }

    @Test
    fun schema_seat_values_are_exactly_the_ones_in_the_code() {
        val enumText = Regex("\"enum\"\\s*:\\s*\\[([^\\]]*)\\]").find(schema())!!.groupValues[1]
        val inFile = Regex("\"([A-Z_]+)\"").findAll(enumText).map { it.groupValues[1] }.toSet()
        assertEquals(GleoSeat.values.keys, inFile)
    }

    @Test
    fun the_seat_is_optional_in_the_schema() {
        val at = schema().indexOf("\"name\": \"MOTION_SICKNESS_RELIEF\"")
        val block = schema().substring(at, schema().indexOf("\"name\": \"MOTION_SICKNESS_RECOVERED\""))
        assertTrue(block.contains("\"seat\""))
        assertTrue(block.contains("\"required\": []"))
    }
}
