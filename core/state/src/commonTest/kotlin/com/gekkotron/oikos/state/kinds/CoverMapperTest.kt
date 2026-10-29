package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.StateValue.Cover
import com.gekkotron.oikos.model.StateValue.Unknown
import com.gekkotron.oikos.state.bindings
import kotlin.test.Test
import kotlin.test.assertEquals

class CoverMapperTest {
    private val z2m = bindings(
        "cover",
        """{"command_topic":"z/set","state_topic":"z","value_template":"{{ value_json.state }}","state_open":"OPEN","state_closed":"CLOSE",
           "position_topic":"z","position_template":"{{ value_json.position }}",
           "set_position_topic":"z/set","set_position_template":"{ \"position\": {{ position }} }"}""",
    )

    private fun publishes(topic: String, payload: String) = Encoded.Publish(listOf(Outgoing(topic, payload, false, 0)))

    @Test
    fun `state and position on one topic`() {
        assertEquals(Decoded.State(Cover(CoverState.OPEN, 42)), CoverMapper.decode(z2m, "z", """{"position":42,"state":"OPEN"}""", Unknown))
    }

    @Test
    fun `statement template on state still tracks position`() {
        // zigbee2mqtt's real cover value_template uses {% if %}, which is outside the subset.
        val b = bindings(
            "cover",
            """{"state_topic":"z","value_template":"{% if value_json.moving %}{{ value_json.moving }}{% else %}STOP{% endif %}",
               "position_topic":"z","position_template":"{{ value_json.position }}"}""",
        )
        assertEquals(Decoded.State(Cover(CoverState.OPEN, 60)), CoverMapper.decode(b, "z", """{"position":60}""", Unknown))
    }

    @Test
    fun `position without state derives open or closed`() {
        val b = bindings("cover", """{"position_topic":"p"}""")
        assertEquals(Decoded.State(Cover(CoverState.CLOSED, 0)), CoverMapper.decode(b, "p", "0", Unknown))
        assertEquals(Decoded.State(Cover(CoverState.OPEN, 60)), CoverMapper.decode(b, "p", "60", Unknown))
    }

    @Test
    fun `inverted position bounds`() {
        val b = bindings("cover", """{"position_topic":"p","set_position_topic":"p/set","position_open":0,"position_closed":100}""")
        assertEquals(Decoded.State(Cover(CoverState.OPEN, 75)), CoverMapper.decode(b, "p", "25", Unknown))
        assertEquals(publishes("p/set", "25"), CoverMapper.encode(b, Command.SetPosition(75), Unknown))
    }

    @Test
    fun `zigbee2mqtt 2_14 shutter value_template decodes state and position from one message`() {
        // real zigbee2mqtt 2.14 discovery config for a Tuya curtain/blind switch (TS130F); state_topic == position_topic.
        val b = bindings(
            "cover",
            """{"state_topic":"zigbee2mqtt/rooms/living/shutter_south",
               "position_topic":"zigbee2mqtt/rooms/living/shutter_south",
               "position_template":"{{ value_json[\"position\"] }}",
               "state_open":"OPEN","state_closed":"CLOSE","state_opening":"UP","state_closing":"DOWN","state_stopped":"STOP",
               "value_template":"{% if \"position\" in value_json and value_json[\"position\"] == 0 and \"state\" in value_json and value_json[\"state\"] == \"CLOSE\" %}CLOSE{% elif \"position\" in value_json and value_json[\"position\"] == 100 and \"state\" in value_json and value_json[\"state\"] == \"OPEN\" %}OPEN{% elif \"moving\" in value_json and value_json[\"moving\"] == \"UP\" %}UP{% elif \"moving\" in value_json and value_json[\"moving\"] == \"DOWN\" %}DOWN{% elif \"moving\" in value_json and value_json[\"moving\"] == \"STOP\" and \"position\" in value_json %}{% if value_json[\"position\"] == 0 %}CLOSE{% else %}OPEN{% endif %}{% elif \"state\" in value_json and value_json[\"state\"] == \"OPEN\" %}OPEN{% elif \"state\" in value_json and value_json[\"state\"] == \"CLOSE\" %}CLOSE{% else %}STOP{% endif %}"}""",
        )
        val topic = "zigbee2mqtt/rooms/living/shutter_south"
        assertEquals(
            Decoded.State(Cover(CoverState.OPENING, 40)),
            CoverMapper.decode(b, topic, """{"position":40,"moving":"UP","state":"OPEN"}""", Unknown),
        )
        assertEquals(
            Decoded.State(Cover(CoverState.CLOSING, 40)),
            CoverMapper.decode(b, topic, """{"position":40,"moving":"DOWN","state":"OPEN"}""", Unknown),
        )
        assertEquals(
            Decoded.State(Cover(CoverState.CLOSED, 0)),
            CoverMapper.decode(b, topic, """{"position":0,"moving":"STOP","state":"CLOSE"}""", Unknown),
        )
        assertEquals(
            Decoded.State(Cover(CoverState.OPEN, 40)),
            CoverMapper.decode(b, topic, """{"position":40,"moving":"STOP","state":"OPEN"}""", Unknown),
        )
        assertEquals(
            Decoded.State(Cover(CoverState.OPEN, 100)),
            CoverMapper.decode(b, topic, """{"position":100,"state":"OPEN"}""", Unknown),
        )
        // Starting from an end stop: Z2M's template tests "fully closed/open" before "moving", so it answers CLOSE/OPEN.
        assertEquals(
            Decoded.State(Cover(CoverState.OPENING, 0)),
            CoverMapper.decode(b, topic, """{"position":0,"moving":"UP","state":"CLOSE"}""", Unknown),
        )
        assertEquals(
            Decoded.State(Cover(CoverState.CLOSING, 100)),
            CoverMapper.decode(b, topic, """{"position":100,"moving":"DOWN","state":"OPEN"}""", Unknown),
        )
    }

    @Test
    fun `a known position overrides a contradicting open or closed state`() {
        assertEquals(Decoded.State(Cover(CoverState.OPEN, 50)), CoverMapper.decode(z2m, "z", """{"position":50,"state":"CLOSE"}""", Unknown))
        assertEquals(Decoded.State(Cover(CoverState.CLOSED, 0)), CoverMapper.decode(z2m, "z", """{"position":0,"state":"OPEN"}""", Unknown))
    }

    @Test
    fun `a state on its own topic does not contradict the last known position`() {
        val b = bindings("cover", """{"state_topic":"s","position_topic":"p"}""")
        val halfOpen = (CoverMapper.decode(b, "p", "50", Unknown) as Decoded.State).value
        assertEquals(Decoded.State(Cover(CoverState.OPEN, 50)), CoverMapper.decode(b, "s", "closed", halfOpen))
        assertEquals(Decoded.State(Cover(CoverState.OPEN, 50)), CoverMapper.decode(b, "s", "stopped", halfOpen))
        assertEquals(Decoded.State(Cover(CoverState.CLOSING, 50)), CoverMapper.decode(b, "s", "closing", halfOpen))
    }

    @Test
    fun `without a position the reported state is kept`() {
        val b = bindings("cover", """{"state_topic":"s"}""")
        assertEquals(Decoded.State(Cover(CoverState.STOPPED)), CoverMapper.decode(b, "s", "stopped", Unknown))
        assertEquals(Decoded.State(Cover(CoverState.CLOSED)), CoverMapper.decode(b, "s", "closed", Unknown))
    }

    @Test
    fun `a moving field is ignored when the cover's template does not read it`() {
        assertEquals(
            Decoded.State(Cover(CoverState.OPEN, 42)),
            CoverMapper.decode(z2m, "z", """{"position":42,"state":"OPEN","moving":"UP"}""", Unknown),
        )
    }

    @Test
    fun `unrecognised state is ignored`() {
        val b = bindings("cover", """{"state_topic":"s"}""")
        assertEquals(Decoded.Ignored, CoverMapper.decode(b, "s", "wobbling", Unknown))
        assertEquals(Decoded.State(Cover(CoverState.CLOSING)), CoverMapper.decode(b, "s", "closing", Unknown))
    }

    @Test
    fun `commands`() {
        assertEquals(publishes("z/set", "OPEN"), CoverMapper.encode(z2m, Command.Open, Unknown))
        assertEquals(publishes("z/set", "CLOSE"), CoverMapper.encode(z2m, Command.Close, Unknown))
        assertEquals(publishes("z/set", "STOP"), CoverMapper.encode(z2m, Command.Stop, Unknown))
        assertEquals(publishes("z/set", """{ "position": 30 }"""), CoverMapper.encode(z2m, Command.SetPosition(30), Unknown))
        assertEquals(publishes("z/set", "OPEN"), CoverMapper.encode(z2m, Command.Toggle, Cover(CoverState.CLOSED, 0)))
        assertEquals(publishes("z/set", "CLOSE"), CoverMapper.encode(z2m, Command.Toggle, Cover(CoverState.OPEN, 100)))
    }

    @Test
    fun `prediction`() {
        assertEquals(Cover(CoverState.OPENING, 0), CoverMapper.predict(Command.Open, Cover(CoverState.CLOSED, 0)))
        assertEquals(Cover(CoverState.OPENING, 70), CoverMapper.predict(Command.SetPosition(70), Cover(CoverState.OPEN, 20)))
        assertEquals(setOf("z"), CoverMapper.stateTopics(z2m))
    }
}
