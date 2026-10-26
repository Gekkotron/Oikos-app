package com.gekkotron.oikos.feature.home.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.history.HistoryClient
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CommandResult
import com.gekkotron.oikos.model.LightOnPreset
import com.gekkotron.oikos.model.SwitchType
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.source.EntitySource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.gekkotron.oikos.model.Kind
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import java.util.Locale
import javax.inject.Inject
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val source: EntitySource,
    private val savedState: SavedStateHandle,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val labels: LabelText = LabelText { null },
    private val history: HistoryClient = HistoryClient.None,
    preferences: AppPreferences,
) : ViewModel() {
    /** Whether the opt-in voice assistant is on; the mic button shows only then. */
    val voiceEnabled: StateFlow<Boolean> = preferences.voiceEnabled
        .map { it ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // conflate() collapses a burst of state messages to one rebuild per collection turn instead of one
    // per message, and flowOn moves the combine (and HomeUiBuilder.build) off Main; the final value is
    // never dropped.
    val state: StateFlow<HomeUiState> = combine(
        source.entities,
        source.states(),
        source.areas,
        combine(savedState.getStateFlow(KEY_FILTER, RoomFilter.Favorites.key), savedState.getStateFlow(KEY_QUERY, ""), ::Pair),
        combine(source.link, history.available, source.hubGroups, ::Triple),
    ) { entities, states, areas, (key, query), linkHistoryHub ->
        val (link, historyOn, hubGroups) = linkHistoryHub
        val ui = HomeUiBuilder.build(entities, states, areas, RoomFilter.fromKey(key), link, Locale.getDefault(), labels, query, historyOn, hubGroups)
        // The selected chip may no longer exist (its last card was unhidden, its area emptied…); build()
        // already falls back to All for this state, but the saved key must follow, or a stale "hidden"
        // key comes back to life and jumps the screen back there as soon as something is hidden again.
        if (ui.selected.key != key) savedState[KEY_FILTER] = ui.selected.key
        ui
    }.conflate().flowOn(defaultDispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    /** Member id → the group send it last took part in; a member's own send removes it. */
    private val groupSends = mutableMapOf<String, GroupSend>()
    /** Keyed by group and command: failures of two different sends never share a snackbar or a Retry. */
    private val failedInGroup = mutableMapOf<Pair<String, Command>, MutableList<String>>()

    private data class GroupSend(val groupId: String, val kind: Kind, val command: Command, val names: Map<String, String>)

    init {
        viewModelScope.launch {
            source.commandFailures.collect { failure ->
                val send = groupSends[failure.uniqueId]?.takeIf { it.command == failure.command }
                if (send == null) return@collect _events.send(HomeEvent.NotConfirmed(failure.uniqueId, failure.command))
                groupSends.remove(failure.uniqueId)
                val key = send.groupId to send.command
                val bucket = failedInGroup.getOrPut(key) { mutableListOf() }
                bucket += failure.uniqueId
                // The first failure opens a 1 s window; the rest of the same send joins it.
                if (bucket.size == 1) {
                    launch {
                        delay(GROUP_FAILURE_WINDOW_MS)
                        val ids = failedInGroup.remove(key).orEmpty()
                        _events.send(HomeEvent.GroupNotConfirmed(send.groupId, send.kind, ids, ids.map { send.names[it] ?: it }, send.command))
                    }
                }
            }
        }
    }

    private val editor = OverrideEditor(source)

    /** Saves the override sheet through [OverrideEditor.customize]; a failure is a [HomeEvent.SaveFailed]. */
    fun customize(id: String, newRooms: List<Area>, override: UiOverride) {
        viewModelScope.launch { if (!editor.customize(id, newRooms, override)) _events.send(HomeEvent.SaveFailed) }
    }

    /** Room groups spec §6: rename, pin to Favorites, then take [removed] members out. A failed write is a [HomeEvent.SaveFailed]. */
    fun customizeGroup(groupId: String, name: String, favorite: Boolean, removed: List<String>) {
        viewModelScope.launch {
            val group = state.value.card(groupId)?.takeIf { it.isGroup } ?: return@launch
            val kindKey = RoomGroups.kindKey(group.kind)
            val renamed = name.trim() == group.name.trim() || editor.setGroupName(group.areaId, kindKey, name)
            val favorited = favorite == group.ui.favorite || editor.setGroupFavorite(group.areaId, kindKey, favorite)
            if (!renamed || !favorited || !editor.ungroup(removed)) _events.send(HomeEvent.SaveFailed)
        }
    }

    /** Home search; blank ends it. */
    fun search(query: String) {
        savedState[KEY_QUERY] = query
    }

    fun select(filter: RoomFilter) {
        savedState[KEY_FILTER] = filter.key
    }

    fun tap(card: CardUi) {
        when (card.tap) {
            TapAction.TOGGLE -> when {
                card.isGroup -> sendGroup(card.id, GroupCommands.toggle(card))
                card.kind == Kind.SWITCH -> dispatchSwitchTap(card)
                // Absolute, so "Retry" after a late confirmation repeats the intent instead of flipping it back.
                else -> send(card.id, if (card.on) Command.TurnOff else turnOnFor(card))
            }
            TapAction.PRESS -> send(card.id, Command.Press)
            TapAction.OPEN_SHEET, TapAction.OPEN_HISTORY, TapAction.NONE -> Unit
        }
    }

    /** A `Kind.SWITCH` card's tap goes through [cardTap], so Door opener / Siren / Chime speak their own command. */
    private fun dispatchSwitchTap(card: CardUi) {
        val type = SwitchType.of(card.protocolKind, card.ui)
        when (val tap = cardTap(type, card.on, card.ui.securityAsk)) {
            is CardTap.Send -> send(card.id, tap.command)
            is CardTap.Confirm -> viewModelScope.launch {
                _events.send(HomeEvent.AskBeforeAction(card.id, tap.labelResId, tap.command))
            }
            CardTap.None -> Unit
        }
    }

    /** A light with a saved on-state preset turns on at that colour/brightness; otherwise plain on.
     *  A customised [UiOverride.lightOnBrightness] replaces the preset's baked brightness. */
    private fun turnOnFor(card: CardUi): Command.TurnOn {
        if (card.kind != Kind.LIGHT) return Command.TurnOn()
        val preset = LightOnPreset.of(card.ui.lightOnPreset) ?: return Command.TurnOn()
        val color = preset.color.takeIf { card.capabilities.colorModes.isNotEmpty() }
        val brightness = (card.ui.lightOnBrightness ?: preset.brightness).takeIf { card.capabilities.brightness }
        return Command.TurnOn(brightness = brightness, color = color)
    }

    fun send(id: String, command: Command) {
        viewModelScope.launch {
            groupSends.remove(id)
            // A cached value may be outdated: never act on it (entity cache spec §3.6).
            if (state.value.card(id)?.stale == true) return@launch _events.send(HomeEvent.WaitingForConnection)
            val result = source.send(id, command)
            if (result is CommandResult.Rejected) _events.send(HomeEvent.Rejected(id, result.reason))
        }
    }

    /** Room groups spec §5: one command to every capable, available member, in parallel. [only] narrows it (Retry). */
    fun sendGroup(groupId: String, command: Command, only: List<String>? = null) {
        viewModelScope.launch {
            val group = state.value.card(groupId)?.takeIf { it.isGroup } ?: return@launch
            if (group.members.any { it.stale }) return@launch _events.send(HomeEvent.WaitingForConnection)
            val ids = GroupCommands.targets(group, command).filter { only == null || it in only }
            // Hub cover groups spec §2.4: a hub group fails under its own id, so the snackbar reads the
            // group's name instead of a bare "group_xyz".
            val names = if (group.isHubGroup) mapOf(group.id to group.name) else group.members.associate { it.id to it.name }
            ids.forEach { groupSends[it] = GroupSend(groupId, group.kind, command, names) }
            val results = ids.map { id -> async { source.send(id, command) } }.awaitAll()
            results.firstOrNull { it is CommandResult.Rejected }?.let {
                _events.send(HomeEvent.Rejected(groupId, (it as CommandResult.Rejected).reason))
            }
        }
    }

    private companion object {
        const val GROUP_FAILURE_WINDOW_MS = 1_000L
        const val KEY_FILTER = "home.filter"
        const val KEY_QUERY = "home.query"
    }
}
