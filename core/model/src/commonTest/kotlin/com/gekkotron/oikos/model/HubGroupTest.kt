package com.gekkotron.oikos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HubGroupTest {
    @Test
    fun `a valid cover group payload parses`() {
        val payload = """{"name":"Living shutters","kind":"cover","members":["a","b"],"hideMembers":true}"""
        val spec = HubGroup.parse("living_cover", payload)
        assertEquals(HubGroup("living_cover", Kind.COVER, "Living shutters", listOf("a", "b"), true), spec)
    }

    @Test
    fun `hideMembers defaults to true when missing`() {
        val payload = """{"name":"g","kind":"light","members":["x"]}"""
        assertEquals(true, HubGroup.parse("g", payload)?.hideMembers)
    }

    @Test
    fun `unknown fields do not break parsing`() {
        val payload = """{"name":"g","kind":"cover","members":["a"],"hideMembers":false,"color":"red"}"""
        val spec = HubGroup.parse("g", payload)
        assertEquals(Kind.COVER, spec?.kind)
        assertEquals(false, spec?.hideMembers)
    }

    @Test
    fun `empty or malformed payload yields null`() {
        assertNull(HubGroup.parse("g", ""))
        assertNull(HubGroup.parse("g", "   "))
        assertNull(HubGroup.parse("g", "not-json"))
        assertNull(HubGroup.parse("g", "[1,2,3]"))
    }

    @Test
    fun `missing required field yields null`() {
        assertNull(HubGroup.parse("g", """{"kind":"cover","members":[]}""")) // no name
        assertNull(HubGroup.parse("g", """{"name":"g","members":[]}""")) // no kind
        assertNull(HubGroup.parse("g", """{"name":"g","kind":"cover"}""")) // no members
    }

    @Test
    fun `unknown kind value yields null`() {
        assertNull(HubGroup.parse("g", """{"name":"g","kind":"doorbell","members":[]}"""))
    }
}
