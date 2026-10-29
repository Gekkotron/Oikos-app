package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomGroupsTest {
    private val areas = listOf(Area("living", "Living room"), Area("kitchen", "Kitchen"))

    private fun build(
        vararg entities: Entity,
        states: Map<String, EntityState> = emptyMap(),
        selected: RoomFilter = RoomFilter.All,
        areaList: List<Area> = areas,
        query: String = "",
    ) = HomeUiBuilder.build(entities.associateBy { it.uniqueId }, states, areaList, selected, LinkState.Connected(TransportMode.DEMO), Locale.US, query = query)

    private fun shutter(id: String, area: String = "living", ui: UiOverride = UiOverride(), position: Boolean = true) =
        testEntity(id, Kind.COVER, id, area = area, ui = ui, capabilities = Capabilities(position = position))

    private fun light(id: String, area: String = "living", ui: UiOverride = UiOverride(), brightness: Boolean = true) =
        testEntity(id, Kind.LIGHT, id, area = area, ui = ui, capabilities = Capabilities(brightness = brightness))

    private fun cover(id: String, state: CoverState, position: Int?) = id to EntityState(id, StateValue.Cover(state, position))

    @Test
    fun `two shutters in a room fold into one wide group card`() {
        val s = build(shutter("bay"), shutter("side"), light("lamp"))
        val group = s.cards.single { it.isGroup }
        assertEquals("group:living:cover", group.id)
        assertEquals(listOf("bay", "side"), group.members.map { it.id })
        assertTrue(group.wide)
        assertEquals(TapAction.OPEN_SHEET, group.tap)
        assertTrue(group.hasSheet)
        assertEquals("", group.name)
        assertEquals(listOf("group:living:cover", "lamp"), s.cards.map { it.id })
    }

    @Test
    fun `a single shutter or an unassigned pair stays as single cards`() {
        val s = build(shutter("bay"), shutter("a", area = "Unassigned"), shutter("b", area = "Unassigned"))
        assertFalse(s.cards.any { it.isGroup })
    }

    @Test
    fun `a group dissolves below two members`() {
        val hidden = build(shutter("bay"), shutter("side", ui = UiOverride(hidden = true)))
        assertEquals(listOf("bay"), hidden.cards.map { it.id })
        val moved = build(shutter("bay"), shutter("side", area = "kitchen"))
        assertEquals(setOf("bay", "side"), moved.cards.map { it.id }.toSet())
        val ungrouped = build(shutter("bay"), shutter("side", ui = UiOverride(ungrouped = true)))
        assertEquals(setOf("bay", "side"), ungrouped.cards.map { it.id }.toSet())
    }

    @Test
    fun `individually-favorited members of the same room group fold under Favorites too`() {
        val e = arrayOf(shutter("bay", ui = UiOverride(favorite = true)), shutter("side", ui = UiOverride(favorite = true)))
        assertTrue(build(*e, selected = RoomFilter.Area("living")).cards.single().isGroup)
        assertTrue(build(*e, selected = RoomFilter.Favorites).cards.single().isGroup)
        // "living" matches both through their room name (SearchMatch also searches the area name).
        assertEquals(listOf("bay", "side"), build(*e, query = "living").cards.map { it.id }.sorted())
    }

    @Test
    fun `a room with a group pinned to Favorites lifts the whole group there`() {
        val favLiving = listOf(Area("living", "Living room", groupFavorites = setOf("cover")), Area("kitchen", "Kitchen"))
        val s = build(shutter("bay"), shutter("side"), light("lamp"), areaList = favLiving, selected = RoomFilter.Favorites)
        val group = s.cards.single { it.isGroup }
        assertEquals("group:living:cover", group.id)
        assertTrue(group.ui.favorite)
        assertEquals(listOf("group:living:cover"), s.cards.map { it.id })
    }

    @Test
    fun `a group pin draws the Favorites chip even when no device is individually favorited`() {
        val favLiving = listOf(Area("living", "Living room", groupFavorites = setOf("cover")))
        val s = build(shutter("bay"), shutter("side"), areaList = favLiving)
        assertTrue(s.chips.any { it.filter == RoomFilter.Favorites })
    }

    @Test
    fun `the group lifts above individual devices in the same room`() {
        val s = build(
            testEntity("aaa", Kind.SWITCH, "aaa", area = "living"),
            shutter("zz1"),
            testEntity("mmm", Kind.SWITCH, "mmm", area = "living"),
            shutter("bbb"),
        )
        assertEquals(listOf("group:living:cover", "aaa", "mmm"), s.cards.map { it.id })
    }

    @Test
    fun `a cover group aggregates open count, motion, positions and status`() {
        val s = build(
            shutter("a"), shutter("b"), shutter("c", position = false),
            states = mapOf(
                cover("a", CoverState.OPEN, 100),
                cover("b", CoverState.CLOSING, 40),
                cover("c", CoverState.CLOSED, null),
                "b" to EntityState("b", StateValue.Cover(CoverState.CLOSING, 40), pending = true),
            ),
        )
        val g = s.cards.single()
        assertEquals(CardValue.CoverGroup(open = 1, total = 3, moving = CoverState.CLOSING, positions = listOf(100, 40, 0)), g.value)
        assertTrue(g.on)
        assertTrue(g.pending)
        assertTrue(g.capabilities.position)
    }

    @Test
    fun `a shutter stopped midway counts open, with or without a position`() {
        val s = build(
            shutter("a", position = false), shutter("b"), shutter("c"),
            states = mapOf(cover("a", CoverState.STOPPED, null), cover("b", CoverState.STOPPED, 30), cover("c", CoverState.STOPPED, 0)),
        )
        assertEquals(2, (s.cards.single().value as CardValue.CoverGroup).open)
    }

    @Test
    fun `opening wins over closing when members move both ways`() {
        val s = build(shutter("a"), shutter("b"), states = mapOf(cover("a", CoverState.OPENING, 10), cover("b", CoverState.CLOSING, 90)))
        assertEquals(CoverState.OPENING, (s.cards.single().value as CardValue.CoverGroup).moving)
    }

    @Test
    fun `a light group opens its sheet on tap and counts lights on`() {
        val s = build(
            light("a"), light("b", brightness = false), light("c"),
            states = mapOf("a" to EntityState("a", StateValue.Light(true, 200)), "c" to EntityState("c", StateValue.Light(false, null))),
        )
        val g = s.cards.single()
        assertEquals("group:living:light", g.id)
        assertEquals(TapAction.OPEN_SHEET, g.tap)
        assertEquals(CardValue.LightGroup(onCount = 1, total = 3), g.value)
        assertTrue(g.on)
        assertTrue(g.capabilities.brightness)
    }

    @Test
    fun `available if any member is, stale if any member is`() {
        val s = build(
            shutter("a"), shutter("b"),
            states = mapOf(
                "a" to EntityState("a", StateValue.Cover(CoverState.OPEN, 100), available = false),
                "b" to EntityState("b", StateValue.Cover(CoverState.OPEN, 100), stale = true),
            ),
        )
        val g = s.cards.single()
        assertTrue(g.available)
        assertTrue(g.stale)
    }

    @Test
    fun `a custom group name comes from the area`() {
        val s = build(shutter("a"), shutter("b"), areaList = listOf(Area("living", "Living room", groupNames = mapOf("cover" to "Baie"))))
        assertEquals("Baie", s.cards.single().name)
    }

    @Test
    fun `members and their companions are found by id`() {
        val s = build(shutter("a"), shutter("b"))
        assertNotNull(s.card("a"))
        assertNotNull(s.card("group:living:cover"))
    }

    @Test
    fun `groupable marks covers and lights that have a peer in their room, even when ungrouped`() {
        val s = build(shutter("a"), shutter("b", ui = UiOverride(ungrouped = true)), light("solo"), selected = RoomFilter.All)
        val byId = (s.cards + s.cards.flatMap { it.members }).associateBy { it.id }
        assertTrue(byId.getValue("a").groupable)
        assertTrue(byId.getValue("b").groupable)
        assertFalse(byId.getValue("solo").groupable)
    }

    @Test
    fun `a switch shown as a light joins its room's light group`() {
        val lamp = testEntity("plug", Kind.LIGHT, "plug", area = "living", ui = UiOverride(showAs = "light")).copy(protocolKind = Kind.SWITCH)
        val s = build(lamp, light("lamp"))
        val group = s.cards.single { it.isGroup }
        assertEquals("group:living:light", group.id)
        assertEquals(listOf("lamp", "plug"), group.members.map { it.id }.sorted())
    }
}
