package com.gekkotron.oikos.flows

import kotlin.test.Test
import kotlin.test.assertEquals

class FlowDtoParseTest {
    @Test
    fun `parses minimal flow with defaults`() {
        val dto = flowsJson.decodeFromString<FlowDto>("""{"id":"a","name":"Morning"}""")
        assertEquals(FlowDto(id = "a", name = "Morning", area = null, enabled = true), dto)
    }

    @Test
    fun `parses full flow and ignores unknown fields`() {
        val dto = flowsJson.decodeFromString<FlowDto>(
            """{"id":"b","name":"Night","area":"bedroom","enabled":false,"description":"x","mode":"single","nodes":[],"edges":[]}""",
        )
        assertEquals(FlowDto("b", "Night", "bedroom", false), dto)
    }
}
