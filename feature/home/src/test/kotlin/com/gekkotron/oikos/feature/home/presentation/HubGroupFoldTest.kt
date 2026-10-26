package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.HUB_GROUP_DEVICE_ID
import com.gekkotron.oikos.model.HubGroup
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HubGroupFoldTest {
    private val areas = listOf(Area("living", "Living room"), Area("bedroom", "Bedroom"))

    private fun shutter(id: String, area: String = "living") =
        testEntity(id, Kind.COVER, id, area = area, capabilities = Capabilities(position = true))

    private fun hubCover(id: String, area: String = "living") =
        testEntity("group_$id", Kind.COVER, id, area = area, deviceId = HUB_GROUP_DEVICE_ID, capabilities = Capabilities(position = true))

    private fun build(entities: List<Entity>, hub: Map<String, HubGroup>, states: Map<String, EntityState> = emptyMap()) =
        HomeUiBuilder.build(
            entities.associateBy { it.uniqueId }, states, areas, RoomFilter.All,
            LinkState.Connected(TransportMode.DEMO), Locale.US, hubGroups = hub,
        )

    @Test
    fun `a hub cover group folds its members into one wide group card`() {
        val spec = HubGroup("living", Kind.COVER, "Living shutters", listOf("bay", "side"), hideMembers = true)
        val ui = build(listOf(shutter("bay"), shutter("side"), hubCover("living")), mapOf("living" to spec))

        val group = ui.cards.single { it.isHubGroup }
        assertEquals("group_living", group.id)
        assertEquals("Living shutters", group.name)
        assertTrue(group.wide)
        assertEquals(TapAction.OPEN_SHEET, group.tap)
        assertEquals(listOf("bay", "side"), group.members.map { it.id })
        // Members never show as singles beside the group card.
        assertFalse(ui.cards.any { it.id == "bay" || it.id == "side" })
    }

    @Test
    fun `the folded hub card inherits its members shared area`() {
        val spec = HubGroup("living", Kind.COVER, "Living", listOf("bay", "side"))
        val ui = build(listOf(shutter("bay"), shutter("side"), hubCover("living")), mapOf("living" to spec))
        assertEquals("living", ui.cards.single { it.isHubGroup }.areaId)
    }

    @Test
    fun `mismatched member areas fall back to Unassigned`() {
        val spec = HubGroup("mixed", Kind.COVER, "Mixed", listOf("bay", "bed"))
        val ui = build(
            listOf(shutter("bay", area = "living"), shutter("bed", area = "bedroom"), hubCover("mixed", area = "living")),
            mapOf("mixed" to spec),
        )
        assertEquals(UNASSIGNED_AREA, ui.cards.single { it.isHubGroup }.areaId)
    }

    @Test
    fun `a hub group whose entity is absent leaves things untouched`() {
        // The config topic hasn't landed yet, only the definition — don't invent a card.
        val spec = HubGroup("living", Kind.COVER, "Living", listOf("bay", "side"))
        val ui = build(listOf(shutter("bay"), shutter("side")), mapOf("living" to spec))
        // Falls back to the existing client-side room-group fold.
        val group = ui.cards.single { it.isGroup }
        assertFalse(group.isHubGroup)
        assertEquals("group:living:cover", group.id)
    }

    @Test
    fun `cover state and position aggregate from the real member states`() {
        val spec = HubGroup("living", Kind.COVER, "Living", listOf("bay", "side"))
        val states = mapOf(
            "bay" to EntityState("bay", StateValue.Cover(CoverState.OPEN, 100)),
            "side" to EntityState("side", StateValue.Cover(CoverState.CLOSED, 0)),
        )
        val ui = build(listOf(shutter("bay"), shutter("side"), hubCover("living")), mapOf("living" to spec), states)
        val group = ui.cards.single { it.isHubGroup }
        val value = group.value as CardValue.CoverGroup
        assertEquals(1, value.open)
        assertEquals(2, value.total)
        assertEquals(listOf(100, 0), value.positions)
    }

    @Test
    fun `empty hubGroups keeps the old behaviour exactly`() {
        val ui = build(listOf(shutter("bay"), shutter("side")), emptyMap())
        val group = ui.cards.single { it.isGroup }
        assertFalse(group.isHubGroup)
        assertEquals("group:living:cover", group.id)
    }

    @Test
    fun `a non-cover kind in the spec is ignored for v1`() {
        // Spec §4 non-goals: lights, switches, etc. land in a later pass.
        val spec = HubGroup("living_lights", Kind.LIGHT, "Living lights", listOf("l1", "l2"))
        val lightUi = testEntity("group_living_lights", Kind.LIGHT, "Living lights", area = "living", deviceId = HUB_GROUP_DEVICE_ID)
        val ui = build(
            listOf(
                testEntity("l1", Kind.LIGHT, area = "living"),
                testEntity("l2", Kind.LIGHT, area = "living"),
                lightUi,
            ),
            mapOf("living_lights" to spec),
        )
        // No HubGroup fold happened — the hub entity + members all still show.
        assertFalse(ui.cards.any { it.isHubGroup })
    }
}
