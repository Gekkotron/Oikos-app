package com.gekkotron.oikos.source

import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandFailure
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.Device
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.HubGroup
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.voice.VoiceShortcut
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared empty flow for sources that don't surface hub groups (hub cover groups spec §2.2). */
val emptyHubGroups: StateFlow<Map<String, HubGroup>> = MutableStateFlow(emptyMap<String, HubGroup>()).asStateFlow()

/** Where entities come from. v1: MQTT discovery; later: Home Assistant WebSocket (spec §4.3). */
interface EntitySource {
    /** Broker connection, for the status pill. */
    val link: StateFlow<LinkState>

    /** After overrides are applied (name, area id, ui). */
    val entities: StateFlow<Map<String, Entity>>
    val devices: StateFlow<Map<String, Device>>

    /** Declared areas (by order, then name), then implicit ones (by name), then Unassigned if used. */
    val areas: StateFlow<List<Area>>

    /** Optimistic commands that were not confirmed in time (snackbar "Not confirmed — Retry"). */
    val commandFailures: SharedFlow<CommandFailure>

    fun states(): Flow<Map<String, EntityState>>

    suspend fun send(uniqueId: String, command: Command): CommandResult

    suspend fun setOverride(uniqueId: String, override: UiOverride)

    suspend fun setAreas(areas: List<Area>)

    /** Voice shortcuts (tap-to-teach phrases) keyed by [VoiceShortcut.id]. */
    val shortcuts: StateFlow<Map<String, VoiceShortcut>>

    /** Hub-owned group definitions (hub cover groups spec §2.2), keyed by group id. */
    val hubGroups: StateFlow<Map<String, HubGroup>>
        get() = emptyHubGroups

    suspend fun saveShortcut(shortcut: VoiceShortcut)

    suspend fun deleteShortcut(id: String)

    /** Replays cached messages (topic → payload) as if they had arrived retained; live data wins (entity cache spec §3.1). */
    suspend fun restore(messages: Map<String, String>) {}
}
