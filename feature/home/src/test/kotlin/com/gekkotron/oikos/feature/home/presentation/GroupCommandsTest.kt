package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.feature.home.testEntity
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class GroupCommandsTest {
    private fun group(vararg e: com.gekkotron.oikos.model.Entity, states: Map<String, EntityState> = emptyMap()): CardUi =
        HomeUiBuilder.build(e.associateBy { it.uniqueId }, states, listOf(Area("living", "Living room")), RoomFilter.All, LinkState.Connected(TransportMode.DEMO), Locale.US)
            .cards.single { it.isGroup }

    private val covers = group(
        testEntity("a", Kind.COVER, area = "living", capabilities = Capabilities(position = true)),
        testEntity("b", Kind.COVER, area = "living", capabilities = Capabilities(position = false)),
        testEntity("c", Kind.COVER, area = "living", capabilities = Capabilities(position = true)),
        states = mapOf("c" to EntityState("c", StateValue.Cover(CoverState.OPEN, 100), available = false)),
    )

    @Test
    fun `open, stop and close go to every available member`() {
        assertEquals(listOf("a", "b"), GroupCommands.targets(covers, Command.Open))
        assertEquals(listOf("a", "b"), GroupCommands.targets(covers, Command.Stop))
    }

    @Test
    fun `position goes only to members that support it`() {
        assertEquals(listOf("a"), GroupCommands.targets(covers, Command.SetPosition(50)))
    }

    @Test
    fun `brightness goes only to dimmable lights, plain on and off to all`() {
        val lights = group(
            testEntity("x", Kind.LIGHT, area = "living", capabilities = Capabilities(brightness = true)),
            testEntity("y", Kind.LIGHT, area = "living"),
        )
        assertEquals(listOf("x"), GroupCommands.targets(lights, Command.TurnOn(brightness = 120)))
        assertEquals(listOf("x", "y"), GroupCommands.targets(lights, Command.TurnOn()))
        assertEquals(listOf("x", "y"), GroupCommands.targets(lights, Command.TurnOff))
    }

    @Test
    fun `a hub group routes every command to the group entity, hub fans out`() {
        // The sheet only cares about targets(); isHubGroup=true short-circuits member iteration.
        val hub = covers.copy(id = "group_living", isHubGroup = true)
        assertEquals(listOf("group_living"), GroupCommands.targets(hub, Command.Open))
        assertEquals(listOf("group_living"), GroupCommands.targets(hub, Command.Stop))
        assertEquals(listOf("group_living"), GroupCommands.targets(hub, Command.SetPosition(50)))
        assertEquals(listOf("group_living"), GroupCommands.targets(hub, Command.TurnOff))
    }

    @Test
    fun `toggle is absolute - any on turns all off`() {
        val lights = group(
            testEntity("x", Kind.LIGHT, area = "living"), testEntity("y", Kind.LIGHT, area = "living"),
            states = mapOf("x" to EntityState("x", StateValue.Light(true, 255))),
        )
        assertEquals(Command.TurnOff, GroupCommands.toggle(lights))
        val off = group(testEntity("x", Kind.LIGHT, area = "living"), testEntity("y", Kind.LIGHT, area = "living"))
        assertEquals(Command.TurnOn(), GroupCommands.toggle(off))
    }
}
