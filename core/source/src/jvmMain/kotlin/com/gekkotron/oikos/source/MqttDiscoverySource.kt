package com.gekkotron.oikos.source

import com.gekkotron.oikos.flows.FlowsSink
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.AreasDocument
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Diagnostic
import com.gekkotron.oikos.model.DiagnosticSink
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.HubGroup
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.OikosJson
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.model.UiOverrides
import com.gekkotron.oikos.model.effectiveKind
import com.gekkotron.oikos.source.discovery.DiscoveredEntity
import com.gekkotron.oikos.source.discovery.DiscoveryParser
import com.gekkotron.oikos.source.discovery.DiscoveryResult
import com.gekkotron.oikos.source.discovery.LightAttachment
import com.gekkotron.oikos.source.discovery.SensorAttachment
import com.gekkotron.oikos.state.CommandTracker
import com.gekkotron.oikos.state.StateStore
import com.gekkotron.oikos.state.kinds.Encoded
import com.gekkotron.oikos.state.kinds.KindMappers
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.MqttMessage
import com.gekkotron.oikos.transport.MqttTransport
import com.gekkotron.oikos.transport.PublishFailed
import com.gekkotron.oikos.transport.isValidPublishTopic
import com.gekkotron.oikos.voice.VoiceShortcut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Home Assistant MQTT Discovery + `oikos/ui/…` overrides (spec §4.3). */
@OptIn(FlowPreview::class)
class MqttDiscoverySource(
    private val transport: MqttTransport,
    scope: CoroutineScope,
    private val prefix: String = DEFAULT_PREFIX,
    private val diagnostics: DiagnosticSink = DiagnosticSink.None,
    commandTimeout: Duration = 5.seconds,
    now: () -> Instant = Instant::now,
    private val flowsSink: FlowsSink? = null,
) : EntitySource {
    private val store = StateStore(now, diagnostics)
    private val tracker = CommandTracker(store, scope, commandTimeout)

    private val lock = Any()
    private val discovered = linkedMapOf<String, List<DiscoveredEntity>>() // config topic -> entities
    private val overrides = mutableMapOf<String, UiOverride>()
    private val voiceShortcuts = mutableMapOf<String, VoiceShortcut>()
    private val hubGroupSpecs = mutableMapOf<String, HubGroup>() // group id -> spec
    private var areasDocument = AreasDocument()

    // What the broker is known to hold (its retained messages, or a publish it accepted). A failed write
    // rolls back to this, never to an earlier write that may itself still fail.
    private val confirmedOverrides = mutableMapOf<String, UiOverride>()
    private var confirmedAreas = AreasDocument()

    // Entity cache (spec 2026-09-25): the last payload of every handled topic, the topics received live,
    // and restored topics not yet received live (pruned when the source settles).
    private val payloads = mutableMapOf<String, String>()
    private val liveTopics = mutableSetOf<String>()
    private val restoredOnly = mutableSetOf<String>()
    private val _journalVersion = MutableStateFlow(0L)
    @Volatile private var settled = false
    private val _hasSettled = MutableStateFlow(false)

    /** True once the broker's replay went quiet and restored ghosts were pruned: [journal] is then complete. */
    val hasSettled: StateFlow<Boolean> = _hasSettled.asStateFlow()

    // Live messages (any), and topics received live for the first time (the broker's retained replay, not
    // steady updates): the first shows the replay started, the second when it went quiet.
    private val liveMessages = MutableStateFlow(0L)
    private val newLiveTopics = MutableStateFlow(0L)

    /** Increments whenever [journal] may have changed. */
    val journalVersion: StateFlow<Long> = _journalVersion.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, Entity>>(emptyMap())
    override val entities: StateFlow<Map<String, Entity>> = _entities.asStateFlow()
    private val _devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    override val devices: StateFlow<Map<String, Device>> = _devices.asStateFlow()
    private val _areas = MutableStateFlow<List<Area>>(emptyList())
    override val areas: StateFlow<List<Area>> = _areas.asStateFlow()
    override val commandFailures: SharedFlow<CommandFailure> = tracker.failures
    override val link: StateFlow<LinkState> = transport.link
    private val _shortcuts = MutableStateFlow<Map<String, VoiceShortcut>>(emptyMap())
    override val shortcuts: StateFlow<Map<String, VoiceShortcut>> = _shortcuts.asStateFlow()
    private val _hubGroups = MutableStateFlow<Map<String, HubGroup>>(emptyMap())
    override val hubGroups: StateFlow<Map<String, HubGroup>> = _hubGroups.asStateFlow()

    // The hub publishes HA-discovery under its own `oikos/` prefix (alarm panel, groups, virtual
    // entities, …), beside whatever prefix a legacy controller like Jeedom publishes under; the app
    // reads both. `oikos/#` subsumes `oikos/ui/#` and `oikos/voice/shortcut/#` so they drop out.
    private val prefixes: Set<String> = linkedSetOf(prefix, HUB_PREFIX)
    private val baseFilters = prefixes.map { "$it/#" }.toSet()
    private val stateTopics = MutableStateFlow<Set<String>>(emptySet())

    private class Snapshot(val entities: Map<String, Entity>, val devices: Map<String, Device>, val areas: List<Area>)

    init {
        if (flowsSink != null) {
            flowsSink.clear()
            scope.launch {
                link.map { it is LinkState.Connected }.distinctUntilChanged().filter { it }.collect { flowsSink.markLinkUp() }
            }
        }
        // Undispatched so the collector is subscribed before any subscription can replay retained messages.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { transport.messages().collect { handle(it) } }
        scope.launch {
            transport.setSubscriptions(baseFilters)
            var applied = emptySet<String>()
            stateTopics.debounce(SUBSCRIPTION_DEBOUNCE).collect { topics ->
                if (topics != applied) {
                    applied = topics
                    transport.setSubscriptions(baseFilters + topics)
                    store.retainPayloads(topics)
                }
            }
        }
        scope.launch {
            // Settle once the broker's retained replay has started and then gone quiet, on a link that stayed
            // up. Without live traffic nothing is settled: a stalled link must not prune the cached house.
            while (true) {
                link.first { it is LinkState.Connected }
                val before = liveMessages.value
                combine(link, liveMessages) { l, n -> l !is LinkState.Connected || n != before }.first { it }
                if (link.value is LinkState.Connected && replayWentQuiet()) break
            }
            settle()
        }
    }

    override fun states(): Flow<Map<String, EntityState>> = store.states

    override suspend fun send(uniqueId: String, command: Command): CommandResult {
        val entity = _entities.value[uniqueId] ?: return CommandResult.Rejected("unknown entity $uniqueId")
        val bindings = entity.bindings as? MqttBindings ?: return CommandResult.Rejected("not an MQTT entity")
        val state = store.states.value[uniqueId]
        if (state != null && !state.available) return CommandResult.Rejected("unavailable")
        val mapper = KindMappers.forEntity(entity)
        val previous = state?.value ?: StateValue.Unknown
        val publish = when (val encoded = mapper.encode(bindings, command, previous)) {
            is Encoded.NotSupported -> return CommandResult.Rejected(encoded.reason)
            is Encoded.Publish -> encoded
        }
        val confirmBy = if (bindings.bool("optimistic", false)) emptySet() else mapper.feedbackTopics(bindings, command)
        val hasFeedback = confirmBy.isNotEmpty()
        val predicted = mapper.predict(command, previous)
        tracker.track(uniqueId, command, predicted, hasFeedback, confirmBy)
        try {
            for (m in publish.messages) transport.publish(m.topic, m.payload.encodeToByteArray(), m.qos, m.retain)
        } catch (e: PublishFailed) {
            tracker.abort(uniqueId)
            return CommandResult.Rejected(e.message ?: "publish failed")
        }
        if (!hasFeedback) store.confirmPending(uniqueId, predicted?.let { mapper.settle(command, it) })
        return CommandResult.Accepted
    }

    /**
     * Applies locally, then publishes retained. A default override removes the retained message (an
     * empty payload) instead of leaving `{}` behind. If the broker does not take it, the local change is
     * rolled back, unless something newer replaced it meanwhile, and [PublishFailed] is rethrown:
     * otherwise the phone would show an edit that the retained value undoes at the next reconnect.
     *
     * A timed-out publish can still land later (see [com.gekkotron.oikos.transport.DirectMqttTransport.publish]):
     * the rollback above may end up undone once the broker actually applies it.
     */
    override suspend fun setOverride(uniqueId: String, override: UiOverride) {
        require(uniqueId.isNotEmpty() && uniqueId != AREAS_ID && isValidPublishTopic(UI_PREFIX + uniqueId)) {
            "unique_id '$uniqueId' cannot be used in an override topic"
        }
        val cleared = override == UiOverride()
        synchronized(lock) { overrides.putOrRemove(uniqueId, override.takeUnless { cleared }) }
        publishEntities()
        val payload = if (cleared) ByteArray(0) else UiOverrides.encode(override).encodeToByteArray()
        try {
            transport.publish(UI_PREFIX + uniqueId, payload, qos = 1, retain = true)
        } catch (e: PublishFailed) {
            synchronized(lock) {
                if (overrides[uniqueId] == override.takeUnless { cleared }) overrides.putOrRemove(uniqueId, confirmedOverrides[uniqueId])
            }
            publishEntities()
            throw e
        }
        synchronized(lock) { confirmedOverrides.putOrRemove(uniqueId, override.takeUnless { cleared }) }
    }

    override suspend fun setAreas(areas: List<Area>) {
        val document = AreasDocument(areas)
        synchronized(lock) { areasDocument = document }
        publishEntities()
        val json = OikosJson.encodeToString(AreasDocument.serializer(), document)
        try {
            transport.publish(AREAS_TOPIC, json.encodeToByteArray(), qos = 1, retain = true)
        } catch (e: PublishFailed) {
            synchronized(lock) { if (areasDocument == document) areasDocument = confirmedAreas }
            publishEntities()
            throw e
        }
        synchronized(lock) { confirmedAreas = document }
    }

    override suspend fun restore(messages: Map<String, String>) {
        if (settled) return // the broker already replayed everything; a late cache would only add ghosts
        // Configs first so state messages find their entity, then overrides, then states.
        val ordered = messages.entries.sortedBy { (topic, _) ->
            when {
                topic.endsWith("/config") && prefixes.any { topic.startsWith("$it/") } -> 0
                topic.startsWith(UI_PREFIX) -> 1
                topic.startsWith(VOICE_PREFIX) -> 2
                topic.startsWith(HUB_GROUP_PREFIX) && topic.endsWith(HUB_GROUP_DEFINITION_SUFFIX) -> 3
                else -> 4
            }
        }
        for ((topic, payload) in ordered) handle(MqttMessage(topic, payload.encodeToByteArray(), retained = true), restored = true)
    }

    /** What the cache should hold now: the topics that currently build [entities], [areas] and [states]. */
    fun journal(): Map<String, String> = synchronized(lock) {
        val keep = buildSet {
            addAll(discovered.keys)
            overrides.keys.forEach { add(UI_PREFIX + it) }
            voiceShortcuts.keys.forEach { add(VOICE_PREFIX + it) }
            hubGroupSpecs.keys.forEach { add(HUB_GROUP_PREFIX + it + HUB_GROUP_DEFINITION_SUFFIX) }
            if (areasDocument.areas.isNotEmpty()) add(AREAS_TOPIC)
            addAll(store.subscriptionTopics())
        }
        payloads.filterKeys { it in keep }.mapNotNull { (topic, payload) -> redact(topic, payload)?.let { topic to it } }.toMap()
    }

    /** An alarm panel's `code` is its PIN: it never goes to the cache (entity cache spec §1.5). */
    private fun redact(topic: String, payload: String): String? {
        if (prefixes.none { topic.startsWith("$it/alarm_control_panel/") }) return payload
        val config = try {
            OikosJson.parseToJsonElement(payload) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return null
        return if ("code" in config) JsonObject(config - "code").toString() else payload
    }

    /** True once no new topic arrived for [SETTLE_QUIET] (or [SETTLE_MAX] passed); false if the link dropped. */
    private suspend fun replayWentQuiet(): Boolean {
        val maxRounds = SETTLE_MAX.inWholeMilliseconds / SETTLE_QUIET.inWholeMilliseconds
        var rounds = 0L
        do {
            val seen = newLiveTopics.value
            delay(SETTLE_QUIET)
            rounds++
            if (link.value !is LinkState.Connected) return false
        } while (newLiveTopics.value != seen && rounds < maxRounds)
        return true
    }

    /** The broker has replayed what it holds: restored configs and overrides it did not re-send are gone. */
    private fun settle() {
        synchronized(lock) {
            settled = true
            for (topic in restoredOnly) {
                payloads.remove(topic)
                when {
                    topic == AREAS_TOPIC -> {
                        areasDocument = AreasDocument()
                        confirmedAreas = areasDocument
                    }
                    topic.startsWith(UI_PREFIX) -> topic.removePrefix(UI_PREFIX).let {
                        overrides.remove(it)
                        confirmedOverrides.remove(it)
                    }
                    topic.startsWith(VOICE_PREFIX) -> voiceShortcuts.remove(topic.removePrefix(VOICE_PREFIX))
                    topic.startsWith(HUB_GROUP_PREFIX) && topic.endsWith(HUB_GROUP_DEFINITION_SUFFIX) ->
                        hubGroupSpecs.remove(hubGroupIdOf(topic))
                    else -> discovered.remove(topic)
                }
            }
            _shortcuts.value = voiceShortcuts.toMap()
            _hubGroups.value = hubGroupSpecs.toMap()
            restoredOnly.clear()
        }
        publishEntities()
        store.settle()
        _journalVersion.value++
        _hasSettled.value = true
    }

    /**
     * The whole message is applied under [lock], so a restored message can never interleave with a live one
     * (or with [settle]) between its check and its effect. [publishEntities] and the store re-enter or nest
     * inside it in the usual source -> store order.
     */
    private fun handle(message: MqttMessage, restored: Boolean = false): Unit = synchronized(lock) {
        val topic = message.topic
        try {
            val payload = message.payloadString()
            val flowId = flowIdOf(topic)
            if (flowId != null && restored) return // flows are never cached; the broker's retained replay is the truth
            if (restored) {
                if (settled || topic in liveTopics) return
                restoredOnly += topic
            } else {
                liveMessages.value++
                if (liveTopics.add(topic)) newLiveTopics.value++
                restoredOnly -= topic
            }
            if (payload.isEmpty()) payloads.remove(topic) else payloads[topic] = payload
            when {
                flowId != null -> flowsSink?.onRetained(flowId, payload.ifEmpty { null })
                topic == AREAS_TOPIC -> onAreas(payload)
                topic.startsWith(UI_PREFIX) -> onOverride(topic.removePrefix(UI_PREFIX), payload)
                topic.startsWith(VOICE_PREFIX) -> onShortcut(topic.removePrefix(VOICE_PREFIX), payload)
                topic.startsWith(HUB_GROUP_PREFIX) && topic.endsWith(HUB_GROUP_DEFINITION_SUFFIX) ->
                    onHubGroupDefinition(hubGroupIdOf(topic), payload)
                else -> when (val r = parseDiscovery(topic, payload)) {
                    DiscoveryResult.NotDiscovery -> store.onMessage(topic, payload, restored)
                    DiscoveryResult.Removed -> replaceDiscovered(topic, null)
                    // Like HA: an invalid update is logged and the previously discovered entities are kept.
                    is DiscoveryResult.Skipped -> report("$topic: ${r.reason}")
                    is DiscoveryResult.Entities -> {
                        r.skipped.forEach { report("$topic: $it") }
                        replaceDiscovered(topic, r.items)
                    }
                }
            }
            _journalVersion.value++
        } catch (e: Exception) {
            report("unexpected error handling $topic: $e")
        }
    }

    private fun onAreas(payload: String) {
        val document = if (payload.isEmpty()) {
            AreasDocument()
        } else {
            runCatching { OikosJson.decodeFromString(AreasDocument.serializer(), payload) }.getOrNull()
                ?: return report("$AREAS_TOPIC: invalid areas document")
        }
        synchronized(lock) {
            areasDocument = document
            confirmedAreas = document
        }
        publishEntities()
    }

    private fun onHubGroupDefinition(id: String, payload: String) {
        synchronized(lock) {
            if (payload.isBlank()) {
                hubGroupSpecs.remove(id)
            } else {
                val spec = HubGroup.parse(id, payload)
                if (spec != null) hubGroupSpecs[id] = spec else {
                    hubGroupSpecs.remove(id)
                    report("$HUB_GROUP_PREFIX$id$HUB_GROUP_DEFINITION_SUFFIX: invalid group spec")
                }
            }
            _hubGroups.value = hubGroupSpecs.toMap()
        }
    }

    private fun onShortcut(id: String, payload: String) {
        synchronized(lock) {
            if (payload.isEmpty()) {
                voiceShortcuts.remove(id)
            } else {
                val shortcut = VoiceShortcutDocument.decode(payload)?.toShortcut(id = id)
                if (shortcut != null) voiceShortcuts[id] = shortcut else report("$VOICE_PREFIX$id: invalid shortcut")
            }
            _shortcuts.value = voiceShortcuts.toMap()
        }
    }

    override suspend fun saveShortcut(shortcut: VoiceShortcut) {
        val topic = VOICE_PREFIX + shortcut.id
        require(isValidPublishTopic(topic)) { "shortcut id '${shortcut.id}' is not a valid topic segment" }
        val payload = VoiceShortcutDocument.encode(
            VoiceShortcutDocument(
                phrase = shortcut.phrase,
                lang = shortcut.lang.name.lowercase(),
                action = shortcut.intent.action.name,
                entityIds = shortcut.intent.entityIds,
                value = shortcut.intent.value,
            ),
        )
        transport.publish(topic, payload.encodeToByteArray(), qos = 1, retain = true)
    }

    override suspend fun deleteShortcut(id: String) {
        val topic = VOICE_PREFIX + id
        require(isValidPublishTopic(topic)) { "shortcut id '$id' is not a valid topic segment" }
        transport.publish(topic, ByteArray(0), qos = 1, retain = true)
    }

    private fun onOverride(uniqueId: String, payload: String) {
        val override = if (payload.isEmpty()) null else UiOverrides.decode(payload) ?: return report("$UI_PREFIX$uniqueId: invalid override")
        synchronized(lock) {
            overrides.putOrRemove(uniqueId, override)
            confirmedOverrides.putOrRemove(uniqueId, override)
        }
        publishEntities()
    }

    private fun parseDiscovery(topic: String, payload: String): DiscoveryResult {
        for (p in prefixes) {
            val r = DiscoveryParser.parse(p, topic, payload)
            if (r !is DiscoveryResult.NotDiscovery) return r
        }
        return DiscoveryResult.NotDiscovery
    }

    private fun replaceDiscovered(topic: String, items: List<DiscoveredEntity>?) {
        synchronized(lock) {
            if (items == null) discovered.remove(topic) else discovered[topic] = items
        }
        publishEntities()
    }

    /** Rebuilds and publishes everything under [lock], so a stale snapshot can never be written last. */
    private fun publishEntities(): Unit = synchronized(lock) {
        val snapshot = run {
            val all = discovered.values.flatten()
            val declared = areasDocument.areas
            val raws = all.map { d ->
                val ui = overrides[d.entity.uniqueId] ?: UiOverride()
                Triple(d, ui, resolveArea(ui.area ?: d.device?.suggestedArea ?: d.device?.pathArea ?: UNASSIGNED_AREA, declared))
            }
            // Undeclared rooms that differ only in case ("salon" from a path, "Salon" from suggested_area)
            // are one room, under the first spelling in sort order.
            val declaredIds = declared.mapTo(mutableSetOf()) { it.id }
            val canonical = raws.map { it.third }.filter { it !in declaredIds && it != UNASSIGNED_AREA }
                .groupBy { it.lowercase(Locale.ROOT) }.mapValues { (_, spellings) -> spellings.min() }
            val entities = raws.associate { (d, ui, raw) ->
                val area = canonical[raw.lowercase(Locale.ROOT)]?.takeIf { raw !in declaredIds } ?: raw
                val named = d.entity.copy(name = ui.name ?: d.entity.name, area = area, ui = ui)
                d.entity.uniqueId to named.copy(kind = effectiveKind(d.entity.protocolKind, ui))
            }
            // Recomputed from scratch every time, so it stays correct regardless of the order the cover
            // and its "Moving" sensor arrive in, and clears when either is removed.
            // SensorAttachment wins a conflict: it is applied last.
            val partOf = LightAttachment.compute(entities.values) + SensorAttachment.compute(entities.values)
            val attached = entities.mapValues { (id, e) -> partOf[id]?.let { e.copy(partOf = it) } ?: e }
            Snapshot(attached, all.mapNotNull { it.device }.associateBy { it.id }, computeAreas(declared, attached.values))
        }
        // Lock order is always source -> store; the store never calls back into the source.
        store.setEntities(snapshot.entities.values)
        _entities.value = snapshot.entities
        _devices.value = snapshot.devices
        _areas.value = snapshot.areas
        stateTopics.value = store.subscriptionTopics()
    }

    private fun <V : Any> MutableMap<String, V>.putOrRemove(key: String, value: V?) {
        if (value == null) remove(key) else this[key] = value
    }

    private fun resolveArea(raw: String, declared: List<Area>): String =
        declared.firstOrNull { it.id == raw || it.name.equals(raw, ignoreCase = true) }?.id ?: raw

    /** "julien" → "Julien", "salle_de_bain" → "Salle de bain"; the id stays raw. */
    private fun implicitName(raw: String): String =
        raw.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

    private fun computeAreas(declared: List<Area>, entities: Collection<Entity>): List<Area> {
        val used = entities.mapTo(mutableSetOf()) { it.area }
        val declaredIds = declared.mapTo(mutableSetOf()) { it.id }
        val ordered = declared.sortedWith(compareBy<Area>({ it.order ?: Int.MAX_VALUE }, { it.name }))
        val implicit = (used - declaredIds - UNASSIGNED_AREA).sorted().map { Area(it, implicitName(it)) }
        val unassigned = if (UNASSIGNED_AREA in used && UNASSIGNED_AREA !in declaredIds) listOf(Area(UNASSIGNED_AREA, UNASSIGNED_AREA)) else emptyList()
        return ordered + implicit + unassigned
    }

    private fun report(message: String) = diagnostics.report(Diagnostic("discovery", message))

    /** The flow id of an `oikos/automation/<id>` topic (exactly one non-empty segment after the prefix), else null. */
    private fun flowIdOf(topic: String): String? =
        topic.takeIf { it.startsWith(FLOW_PREFIX) }?.removePrefix(FLOW_PREFIX)?.takeIf { it.isNotEmpty() && '/' !in it }

    private fun hubGroupIdOf(topic: String): String =
        topic.removePrefix(HUB_GROUP_PREFIX).removeSuffix(HUB_GROUP_DEFINITION_SUFFIX)

    companion object {
        const val DEFAULT_PREFIX = "homeassistant"
        const val HUB_PREFIX = "oikos"
        const val UI_PREFIX = "oikos/ui/"
        const val VOICE_PREFIX = "oikos/voice/shortcut/"
        const val FLOW_PREFIX = "$HUB_PREFIX/automation/"
        const val HUB_GROUP_PREFIX = "oikos/group/"
        const val HUB_GROUP_DEFINITION_SUFFIX = "/definition"
        const val AREAS_ID = "_areas"
        const val AREAS_TOPIC = UI_PREFIX + AREAS_ID
        val SUBSCRIPTION_DEBOUNCE: Duration = 500.milliseconds
        val SETTLE_QUIET: Duration = 2.seconds
        val SETTLE_MAX: Duration = 10.seconds
    }
}
