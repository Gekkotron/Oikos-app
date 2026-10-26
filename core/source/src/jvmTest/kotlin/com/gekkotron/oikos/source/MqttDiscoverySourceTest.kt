package com.gekkotron.oikos.source

import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.transport.MqttTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Diagnostic
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.MqttMessage
import com.gekkotron.oikos.transport.PublishFailed
import com.gekkotron.oikos.transport.testing.FakeMqttTransport
import com.gekkotron.oikos.voice.VoiceAction
import com.gekkotron.oikos.voice.VoiceIntent
import com.gekkotron.oikos.voice.VoiceLang
import com.gekkotron.oikos.voice.VoiceShortcut
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MqttDiscoverySourceTest {
    private val lightTopic = "homeassistant/light/0x01/light/config"
    private val lightId = "0x01_light_zigbee2mqtt"
    private val lightConfig =
        """{"schema":"json","command_topic":"z2m/lampe/set","state_topic":"z2m/lampe","brightness":true,"unique_id":"$lightId","name":null,"device":{"identifiers":["z_lampe"],"name":"Lampe","suggested_area":"Salon"}}"""

    private fun switchConfig(id: String, extra: String = "") =
        """{"command_topic":"sw/$id/set","state_topic":"sw/$id","unique_id":"$id"$extra}"""

    private class Harness(
        val transport: FakeMqttTransport,
        val source: MqttDiscoverySource,
        val diagnostics: MutableList<Diagnostic>,
    )

    private fun TestScope.start(transport: FakeMqttTransport = FakeMqttTransport()): Harness {
        val diagnostics = mutableListOf<Diagnostic>()
        val source = MqttDiscoverySource(transport, backgroundScope, diagnostics = { diagnostics += it })
        runCurrent()
        return Harness(transport, source, diagnostics)
    }

    private fun TestScope.settle() {
        advanceTimeBy(MqttDiscoverySource.SUBSCRIPTION_DEBOUNCE.inWholeMilliseconds + 1)
        runCurrent()
    }

    private suspend fun Harness.state(id: String) = source.states().first().getValue(id)

    @Test
    fun `link mirrors the transport`() = runTest {
        val h = start()
        h.transport.setLink(LinkState.Offline("test"))
        assertEquals(LinkState.Offline("test"), h.source.link.value)
    }

    @Test
    fun `subscribes to the ha prefix and to oikos first`() = runTest {
        val h = start()
        assertEquals(setOf("homeassistant/#", "oikos/#"), h.transport.subscriptionHistory.first())
    }

    @Test
    fun `oikos-prefix discovery becomes an entity`() = runTest {
        val h = start()
        h.transport.deliver(
            "oikos/alarm_control_panel/alarm/house/config",
            """{"unique_id":"oikos_alarm_house","command_topic":"oikos/alarm/panel/set","state_topic":"oikos/alarm/panel"}""",
        )
        runCurrent()
        assertTrue("oikos_alarm_house" in h.source.entities.value)
    }

    @Test
    fun `retained config becomes an entity and its state topic is subscribed after the debounce`() = runTest {
        val transport = FakeMqttTransport()
        transport.deliver(lightTopic, lightConfig, retain = true)
        val h = start(transport)

        val light = h.source.entities.value.getValue(lightId)
        assertEquals("Lampe", light.name)
        assertEquals("Salon", light.area)
        assertEquals(setOf("homeassistant/#", "oikos/#"), h.transport.subscriptions)

        settle()
        assertTrue("z2m/lampe" in h.transport.subscriptions)
        h.transport.deliver("z2m/lampe", """{"state":"ON","brightness":254}""")
        runCurrent()
        assertEquals(StateValue.Light(true, 254), h.state(lightId).value)
    }

    @Test
    fun `subscription updates are debounced`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/a/config", switchConfig("a"))
        h.transport.deliver("homeassistant/switch/b/config", switchConfig("b"))
        h.transport.deliver("homeassistant/switch/c/config", switchConfig("c"))
        runCurrent()
        settle()
        assertEquals(2, h.transport.subscriptionHistory.size)
        assertTrue(h.transport.subscriptions.containsAll(setOf("sw/a", "sw/b", "sw/c")))
    }

    @Test
    fun `override name and area take precedence and can be cleared`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        h.transport.deliver("oikos/ui/$lightId", """{"name":"Lampe salon","area":"Cuisine","favorite":true}""")
        runCurrent()
        var light = h.source.entities.value.getValue(lightId)
        assertEquals("Lampe salon", light.name)
        assertEquals("Cuisine", light.area)
        assertTrue(light.ui.favorite)

        h.transport.deliver("oikos/ui/$lightId", "")
        runCurrent()
        light = h.source.entities.value.getValue(lightId)
        assertEquals("Lampe", light.name)
        assertEquals("Salon", light.area)
    }

    @Test
    fun `areas resolve by id or name and are ordered declared, implicit, unassigned`() = runTest {
        val h = start()
        h.transport.deliver("oikos/ui/_areas", """{"areas":[{"id":"garage","name":"Garage","order":2},{"id":"kitchen","name":"Cuisine","order":1}]}""")
        h.transport.deliver(lightTopic, lightConfig) // suggested_area Salon -> implicit
        h.transport.deliver("homeassistant/switch/a/config", switchConfig("a"))
        h.transport.deliver("homeassistant/switch/b/config", switchConfig("b"))
        h.transport.deliver("oikos/ui/a", """{"area":"Cuisine"}""")
        runCurrent()

        assertEquals("kitchen", h.source.entities.value.getValue("a").area)
        assertEquals(UNASSIGNED_AREA, h.source.entities.value.getValue("b").area)
        assertEquals(listOf("kitchen", "garage", "Salon", UNASSIGNED_AREA), h.source.areas.value.map { it.id })
    }

    @Test
    fun `empty config removes the entity and its state`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        h.transport.deliver(lightTopic, "")
        runCurrent()
        assertFalse(lightId in h.source.entities.value)
        assertFalse(lightId in h.source.states().first())
    }

    // Review Focus 4
    @Test
    fun `config replaced under the same topic drops the old entity`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/plug/config", switchConfig("old"))
        runCurrent()
        h.transport.deliver("homeassistant/switch/plug/config", switchConfig("new"))
        runCurrent()
        assertEquals(setOf("new"), h.source.entities.value.keys)
    }

    // Review Focus 3
    @Test
    fun `malformed config does not disturb other entities`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        h.transport.deliver("homeassistant/switch/bad/config", "{oops")
        runCurrent()
        assertEquals(setOf(lightId), h.source.entities.value.keys)
        assertEquals(1, h.diagnostics.size)
        assertTrue("homeassistant/switch/bad/config" in h.diagnostics.single().message)

        h.transport.deliver("homeassistant/switch/bad/config", switchConfig("fixed"))
        runCurrent()
        assertEquals(setOf(lightId, "fixed"), h.source.entities.value.keys)
    }

    @Test
    fun `send publishes, marks pending, and a state message confirms`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        settle()
        h.transport.deliver("z2m/lampe", """{"state":"OFF"}""")
        runCurrent()

        assertEquals(CommandResult.Accepted, h.source.send(lightId, Command.TurnOn()))
        assertEquals("z2m/lampe/set" to """{"state":"ON"}""", h.transport.publishedStrings().last())
        assertTrue(h.state(lightId).pending)

        h.transport.deliver("z2m/lampe", """{"state":"ON"}""")
        runCurrent()
        assertFalse(h.state(lightId).pending)
        assertEquals(StateValue.Light(true, null), h.state(lightId).value)
    }

    @Test
    fun `unconfirmed command reverts and is reported`() = runTest {
        val h = start()
        val failures = mutableListOf<CommandFailure>()
        backgroundScope.launch { h.source.commandFailures.collect { failures += it } }
        h.transport.deliver("homeassistant/switch/a/config", switchConfig("a"))
        runCurrent()
        settle()
        h.transport.deliver("sw/a", "OFF")
        runCurrent()

        h.source.send("a", Command.TurnOn())
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, failures.size)
        assertEquals(StateValue.OnOff(false), h.state("a").value)
        assertFalse(h.state("a").pending)
    }

    @Test
    fun `optimistic entity without state topic is confirmed immediately`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/o/config", """{"command_topic":"o/set","unique_id":"o"}""")
        runCurrent()
        h.source.send("o", Command.TurnOn())
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(StateValue.OnOff(true), h.state("o").value)
        assertFalse(h.state("o").pending)
    }

    @Test
    fun `send rejects unknown, unavailable and unsupported`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/u/config", switchConfig("u", ""","availability_topic":"sw/u/avail""""))
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        assertIs<CommandResult.Rejected>(h.source.send("nope", Command.TurnOn()))
        assertEquals(CommandResult.Rejected("unavailable"), h.source.send("u", Command.TurnOn()))
        assertIs<CommandResult.Rejected>(h.source.send(lightId, Command.SetPosition(10)))
        assertTrue(h.transport.published.isEmpty())
    }

    @Test
    fun `publish failure is rejected and leaves no pending state`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        h.transport.failPublishes = true
        assertIs<CommandResult.Rejected>(h.source.send(lightId, Command.TurnOn()))
        assertFalse(h.state(lightId).pending)
        assertEquals(StateValue.Unknown, h.state(lightId).value)
    }

    @Test
    fun `setOverride applies locally and publishes retained JSON`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        h.source.setOverride(lightId, UiOverride(name = "X"))
        assertEquals("X", h.source.entities.value.getValue(lightId).name)
        assertEquals(MqttMessage("oikos/ui/$lightId", """{"name":"X"}""".encodeToByteArray(), retained = true), h.transport.published.last())
        runCurrent()
        assertEquals("X", h.source.entities.value.getValue(lightId).name)
    }

    @Test
    fun `setOverride rejects ids that cannot be topics`() = runTest {
        val h = start()
        assertFailsWith<IllegalArgumentException> { h.source.setOverride("a#b", UiOverride()) }
        assertFailsWith<IllegalArgumentException> { h.source.setOverride("a+b", UiOverride()) }
        assertFailsWith<IllegalArgumentException> { h.source.setOverride("_areas", UiOverride()) }
        assertFailsWith<IllegalArgumentException> { h.source.setOverride("", UiOverride()) }
    }

    @Test
    fun `setAreas publishes the areas document`() = runTest {
        val h = start()
        h.source.setAreas(listOf(Area("salon", "Salon", order = 1)))
        assertEquals("oikos/ui/_areas" to """{"areas":[{"id":"salon","name":"Salon","order":1}]}""", h.transport.publishedStrings().last())
        assertEquals(listOf("salon"), h.source.areas.value.map { it.id })
    }

    @Test
    fun `status and unknown topics are harmless`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/status", "online")
        h.transport.deliver("oikos/ui/x", "not json")
        runCurrent()
        assertTrue(h.source.entities.value.isEmpty())
        assertEquals(1, h.diagnostics.size)
    }

    private fun z2mSwitch(id: String, deviceName: String, extraDevice: String = "") =
        """{"command_topic":"z/$id/set","state_topic":"z/$id","unique_id":"$id","name":null,"device":{"identifiers":["$id"],"name":"$deviceName"$extraDevice}}"""

    @Test
    fun `a hierarchical device name gives the room when there is no suggested_area, and suggested_area wins`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/p/config", z2mSwitch("p", "rooms/julien/prise"))
        h.transport.deliver("homeassistant/switch/q/config", z2mSwitch("q", "rooms/julien/radio", ""","suggested_area":"Bureau""""))
        runCurrent()
        val p = h.source.entities.value.getValue("p")
        assertEquals("prise", p.name)
        assertEquals("julien", p.area)
        assertEquals("Bureau", h.source.entities.value.getValue("q").area)
        assertEquals(Area("julien", "Julien"), h.source.areas.value.first { it.id == "julien" })
        assertEquals("prise", h.source.devices.value.getValue("p").name)
    }

    @Test
    fun `a declared area matches a path room by name ignoring case`() = runTest {
        val h = start()
        h.source.setAreas(listOf(Area("bedroom_j", "Julien")))
        h.transport.deliver("homeassistant/switch/p/config", z2mSwitch("p", "rooms/julien/prise"))
        runCurrent()
        assertEquals("bedroom_j", h.source.entities.value.getValue("p").area)
        assertEquals(listOf("bedroom_j"), h.source.areas.value.map { it.id })
    }

    @Test
    fun `an override still beats the path room`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/p/config", z2mSwitch("p", "rooms/julien/prise"))
        runCurrent()
        h.source.setOverride("p", UiOverride(area = "kitchen"))
        assertEquals("kitchen", h.source.entities.value.getValue("p").area)
    }

    @Test
    fun `a default override clears the retained topic instead of publishing an empty object`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        val discoveredName = h.source.entities.value.getValue(lightId).name
        h.source.setOverride(lightId, UiOverride(name = "X"))
        h.source.setOverride(lightId, UiOverride())
        val last = h.transport.published.last()
        assertEquals("oikos/ui/$lightId", last.topic)
        assertEquals(0, last.payload.size)
        assertTrue(last.retained)
        assertEquals(discoveredName, h.source.entities.value.getValue(lightId).name)
        assertEquals(UiOverride(), h.source.entities.value.getValue(lightId).ui)
    }

    @Test
    fun `a failed override publish restores the previous override and rethrows`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        h.source.setOverride(lightId, UiOverride(name = "X"))
        h.transport.failPublishes = true
        assertFailsWith<PublishFailed> { h.source.setOverride(lightId, UiOverride(name = "Y")) }
        assertEquals("X", h.source.entities.value.getValue(lightId).name)
    }

    @Test
    fun `a failed first override publish leaves no override behind`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        runCurrent()
        h.transport.failPublishes = true
        assertFailsWith<PublishFailed> { h.source.setOverride(lightId, UiOverride(hidden = true)) }
        assertFalse(h.source.entities.value.getValue(lightId).ui.hidden)
    }

    @Test
    fun `a failed areas publish restores the previous areas and rethrows`() = runTest {
        val h = start()
        h.source.setAreas(listOf(Area("salon", "Salon")))
        h.transport.failPublishes = true
        assertFailsWith<PublishFailed> { h.source.setAreas(listOf(Area("salon", "Salon"), Area("kitchen", "Kitchen"))) }
        assertEquals(listOf("salon"), h.source.areas.value.map { it.id })
    }


    /** Publishes wait on a gate while [gated], like a link that died silently and times out later. */
    private class GatedTransport(val inner: FakeMqttTransport = FakeMqttTransport()) : MqttTransport by inner {
        var gated = false
        val pending = ArrayDeque<CompletableDeferred<Unit>>()

        override suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
            if (gated) CompletableDeferred<Unit>().also { pending += it }.await()
            inner.publish(topic, payload, qos, retain)
        }

        fun failNext() = pending.removeFirst().completeExceptionally(PublishFailed("timed out"))
    }

    // Final review I1
    @Test
    fun `overlapping failed override writes roll back to what the broker has`() = runTest {
        val t = GatedTransport()
        val source = MqttDiscoverySource(t, backgroundScope)
        runCurrent()
        t.inner.deliver(lightTopic, lightConfig)
        runCurrent()
        source.setOverride(lightId, UiOverride(name = "P"))
        runCurrent()
        t.gated = true
        val a = async { runCatching { source.setOverride(lightId, UiOverride(name = "A")) } }
        val b = async { runCatching { source.setOverride(lightId, UiOverride(name = "B")) } }
        runCurrent()
        t.failNext()
        runCurrent()
        t.failNext()
        runCurrent()
        assertTrue(a.await().isFailure)
        assertTrue(b.await().isFailure)
        assertEquals("P", source.entities.value.getValue(lightId).name)
    }

    // Final review I1
    @Test
    fun `overlapping failed areas writes roll back to what the broker has`() = runTest {
        val t = GatedTransport()
        val source = MqttDiscoverySource(t, backgroundScope)
        runCurrent()
        source.setAreas(listOf(Area("salon", "Salon")))
        runCurrent()
        t.gated = true
        val a = async { runCatching { source.setAreas(listOf(Area("salon", "Salon"), Area("sejour", "Séjour"))) } }
        val b = async { runCatching { source.setAreas(listOf(Area("salon", "Salon"), Area("sejour", "Séjour"), Area("cuisine", "Cuisine"))) } }
        runCurrent()
        t.failNext()
        runCurrent()
        t.failNext()
        runCurrent()
        assertTrue(a.await().isFailure && b.await().isFailure)
        assertEquals(listOf("salon"), source.areas.value.map { it.id })
    }

    private fun fixture(name: String) = javaClass.getResource("/discovery/$name")!!.readText()

    private val shutterCoverTopic = "homeassistant/cover/847127fffe08106c/shutter_south/config"
    private val shutterMovingTopic = "homeassistant/sensor/847127fffe08106c/shutter_south_moving/config"
    private val shutterCoverId = "0x847127fffe08106c_cover_zigbee2mqtt"
    private val shutterMovingId = "0x847127fffe08106c_moving_zigbee2mqtt"

    @Test
    fun `a light's device numbers are folded into it regardless of arrival order`() = runTest {
        val lightTopic = "homeassistant/light/0x1051dbfffe1a4058/light/config"
        val numberTopic = "homeassistant/number/0x1051dbfffe1a4058/effect_intensity/config"
        val lightId = "0x1051dbfffe1a4058_light_zigbee2mqtt"
        val numberId = "0x1051dbfffe1a4058_effect_intensity_zigbee2mqtt"
        for (order in listOf(listOf(lightTopic, numberTopic), listOf(numberTopic, lightTopic))) {
            val h = start()
            for (topic in order) h.transport.deliver(topic, fixture(if (topic == lightTopic) "zigled_light.json" else "zigled_effect_intensity.json"))
            runCurrent()
            assertEquals(lightId, h.source.entities.value.getValue(numberId).partOf)
            assertNull(h.source.entities.value.getValue(lightId).partOf)
        }
    }

    @Test
    fun `a Z2M Moving sensor is attached to its cover regardless of arrival order`() = runTest {
        val cover = fixture("z2m_shutter_cover.json")
        val moving = fixture("z2m_shutter_moving.json")

        val coverFirst = start()
        coverFirst.transport.deliver(shutterCoverTopic, cover)
        coverFirst.transport.deliver(shutterMovingTopic, moving)
        runCurrent()
        assertEquals(shutterCoverId, coverFirst.source.entities.value.getValue(shutterMovingId).partOf)
        assertNull(coverFirst.source.entities.value.getValue(shutterCoverId).partOf)

        val movingFirst = start()
        movingFirst.transport.deliver(shutterMovingTopic, moving)
        movingFirst.transport.deliver(shutterCoverTopic, cover)
        runCurrent()
        assertEquals(shutterCoverId, movingFirst.source.entities.value.getValue(shutterMovingId).partOf)
    }

    @Test
    fun `a sensor on another topic or reading a field the cover does not use stays unattached`() = runTest {
        val h = start()
        h.transport.deliver(shutterCoverTopic, fixture("z2m_shutter_cover.json"))
        h.transport.deliver(
            "homeassistant/sensor/847127fffe08106c/elsewhere/config",
            """{"state_topic":"zigbee2mqtt/rooms/living/other","value_template":"{{ value_json[\"moving\"] }}","unique_id":"other_topic_sensor","device":{"identifiers":["zigbee2mqtt_0x847127fffe08106c"]}}""",
        )
        h.transport.deliver(
            "homeassistant/sensor/847127fffe08106c/battery/config",
            """{"state_topic":"zigbee2mqtt/rooms/living/shutter_south","value_template":"{{ value_json[\"battery\"] }}","unique_id":"battery_sensor","device":{"identifiers":["zigbee2mqtt_0x847127fffe08106c"]}}""",
        )
        runCurrent()
        assertNull(h.source.entities.value.getValue("other_topic_sensor").partOf)
        assertNull(h.source.entities.value.getValue("battery_sensor").partOf)
    }

    @Test
    fun `removing the cover clears partOf on the sensor`() = runTest {
        val h = start()
        h.transport.deliver(shutterCoverTopic, fixture("z2m_shutter_cover.json"))
        h.transport.deliver(shutterMovingTopic, fixture("z2m_shutter_moving.json"))
        runCurrent()
        assertEquals(shutterCoverId, h.source.entities.value.getValue(shutterMovingId).partOf)

        h.transport.deliver(shutterCoverTopic, "")
        runCurrent()
        assertNull(h.source.entities.value.getValue(shutterMovingId).partOf)
        assertFalse(shutterCoverId in h.source.entities.value)
    }

    // Final review I3
    @Test
    fun `raw rooms that differ only in case are one room`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/p/config", z2mSwitch("p", "rooms/salon/prise"))
        h.transport.deliver("homeassistant/switch/q/config", z2mSwitch("q", "rooms/Salon/lampe"))
        h.transport.deliver("homeassistant/switch/r/config", z2mSwitch("r", "radio", ""","suggested_area":"SALON""""))
        runCurrent()
        val areas = listOf("p", "q", "r").map { h.source.entities.value.getValue(it).area }.toSet()
        assertEquals(1, areas.size, "got $areas")
        assertEquals(areas, h.source.areas.value.map { it.id }.toSet())
    }

    @Test
    fun `voice shortcut topic is subscribed via the oikos prefix`() = runTest {
        val h = start()
        assertEquals(setOf("homeassistant/#", "oikos/#"), h.transport.subscriptions)
    }

    @Test
    fun `an incoming shortcut appears in the shortcuts flow`() = runTest {
        val h = start()
        h.transport.deliver(
            "oikos/voice/shortcut/abc1234567890abc1234567890abc123",
            """{"v":1,"phrase":"il fait sombre","lang":"fr","action":"TURN_ON","entityIds":["lampe"]}""",
        )
        runCurrent()
        val map = h.source.shortcuts.first { it.isNotEmpty() }
        assertEquals(1, map.size)
        assertEquals("il fait sombre", map.values.first().phrase)
    }

    @Test
    fun `saveShortcut publishes a retained payload under the id topic`() = runTest {
        val h = start()
        val sc = VoiceShortcut(
            id = "abc1234567890abc1234567890abc123",
            phrase = "test",
            lang = VoiceLang.FR,
            intent = VoiceIntent(VoiceAction.TURN_ON, listOf("lampe")),
        )
        h.source.saveShortcut(sc)
        val last = h.transport.published.last()
        assertEquals("oikos/voice/shortcut/${sc.id}", last.topic)
        assertEquals(true, last.retained)
        assertTrue(last.payloadString().contains("\"phrase\":\"test\""))
    }

    @Test
    fun `deleteShortcut publishes an empty retained payload`() = runTest {
        val h = start()
        h.source.deleteShortcut("abc1234567890abc1234567890abc123")
        val last = h.transport.published.last()
        assertEquals("oikos/voice/shortcut/abc1234567890abc1234567890abc123", last.topic)
        assertEquals(0, last.payload.size)
        assertEquals(true, last.retained)
    }

    @Test
    fun `a switch with showAs light is a light that still speaks switch payloads`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/a/config", switchConfig("a", ""","payload_on":"1","payload_off":"0""""))
        runCurrent()
        settle()
        assertEquals(Kind.SWITCH, h.source.entities.value.getValue("a").kind)

        h.transport.deliver("oikos/ui/a", """{"showAs":"light"}""")
        runCurrent()
        val e = h.source.entities.value.getValue("a")
        assertEquals(Kind.LIGHT, e.kind)
        assertEquals(Kind.SWITCH, e.protocolKind)

        h.transport.deliver("sw/a", "0")
        runCurrent()
        assertEquals(StateValue.Light(false), h.state("a").value)
        assertEquals(CommandResult.Accepted, h.source.send("a", Command.TurnOn()))
        assertEquals("sw/a/set" to "1", h.transport.publishedStrings().last())

        h.transport.deliver("oikos/ui/a", "")
        runCurrent()
        assertEquals(Kind.SWITCH, h.source.entities.value.getValue("a").kind)
    }

    @Test
    fun `legacy asLight on a switch still promotes to light`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/a/config", switchConfig("a"))
        runCurrent()
        settle()
        h.transport.deliver("oikos/ui/a", """{"asLight":true}""")
        runCurrent()
        val e = h.source.entities.value.getValue("a")
        assertEquals(Kind.LIGHT, e.kind)
        assertEquals(Kind.SWITCH, e.protocolKind)
        assertEquals("light", e.ui.showAs)
    }

    @Test
    fun `showAs door keeps a switch as switch`() = runTest {
        val h = start()
        h.transport.deliver("homeassistant/switch/a/config", switchConfig("a"))
        runCurrent()
        settle()
        h.transport.deliver("oikos/ui/a", """{"showAs":"door"}""")
        runCurrent()
        val e = h.source.entities.value.getValue("a")
        assertEquals(Kind.SWITCH, e.kind)
        assertEquals("door", e.ui.showAs)
    }

    @Test
    fun `showAs leaves a real light alone`() = runTest {
        val h = start()
        h.transport.deliver(lightTopic, lightConfig)
        h.transport.deliver("oikos/ui/$lightId", """{"showAs":"light"}""")
        runCurrent()
        val e = h.source.entities.value.getValue(lightId)
        assertEquals(Kind.LIGHT, e.kind)
        assertEquals(Kind.LIGHT, e.protocolKind)
    }

    // --- Hub cover groups spec §2.2 ---

    @Test
    fun `a hub group definition payload surfaces in hubGroups`() = runTest {
        val h = start()
        h.transport.deliver(
            "oikos/group/living/definition",
            """{"name":"Living shutters","kind":"cover","members":["a","b"],"hideMembers":true}""",
        )
        runCurrent()
        val spec = h.source.hubGroups.value.getValue("living")
        assertEquals("Living shutters", spec.name)
        assertEquals(Kind.COVER, spec.kind)
        assertEquals(listOf("a", "b"), spec.members)
        assertTrue(spec.hideMembers)
    }

    @Test
    fun `an empty payload removes a known hub group`() = runTest {
        val h = start()
        h.transport.deliver(
            "oikos/group/living/definition",
            """{"name":"g","kind":"cover","members":["a"]}""",
        )
        runCurrent()
        assertTrue("living" in h.source.hubGroups.value)
        h.transport.deliver("oikos/group/living/definition", "")
        runCurrent()
        assertFalse("living" in h.source.hubGroups.value)
    }

    @Test
    fun `an invalid hub group payload is reported and ignored`() = runTest {
        val h = start()
        h.transport.deliver("oikos/group/living/definition", "not-json")
        runCurrent()
        assertFalse("living" in h.source.hubGroups.value)
        assertTrue(h.diagnostics.any { it.message.contains("invalid group spec") })
    }
}
