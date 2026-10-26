package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.Capabilities
import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.model.UiOverride
import com.gekkotron.oikos.transport.LinkState

sealed interface RoomFilter {
    val key: String

    data object All : RoomFilter {
        override val key = "all"
    }

    data object Favorites : RoomFilter {
        override val key = "favorites"
    }

    /** Hidden entities only, so they can be found and unhidden (Settings takes this over later). */
    data object Hidden : RoomFilter {
        override val key = "hidden"
    }

    data class Area(val id: String) : RoomFilter {
        override val key get() = AREA_PREFIX + id
    }

    companion object {
        private const val AREA_PREFIX = "area:"

        fun fromKey(key: String): RoomFilter = when {
            key == Favorites.key -> Favorites
            key == Hidden.key -> Hidden
            key.startsWith(AREA_PREFIX) -> Area(key.removePrefix(AREA_PREFIX))
            else -> All
        }
    }
}

/** A null [label] is localized by the UI: All, Favourites or Unassigned. */
data class ChipUi(val filter: RoomFilter, val label: String?)

enum class TapAction { TOGGLE, PRESS, OPEN_SHEET, OPEN_HISTORY, NONE }

/** What a card shows, before localisation. */
sealed interface CardValue {
    data object None : CardValue
    data class OnOff(val on: Boolean) : CardValue
    data class Brightness(val percent: Int) : CardValue
    data class Measurement(val text: String, val unit: String?) : CardValue
    data class Text(val value: String) : CardValue
    data class Cover(val state: CoverState, val position: Int?) : CardValue
    data class Climate(val current: String?, val target: String?, val mode: String?) : CardValue
    data class Alarm(val state: AlarmState) : CardValue
    data class Vacuum(val state: String?, val battery: Int?) : CardValue
    data class Contact(val deviceClass: String?, val on: Boolean) : CardValue

    /** Room groups spec §3.1. [positions] has one 0..100 entry per member; a member without position counts 100 when open, 0 when closed. */
    data class CoverGroup(val open: Int, val total: Int, val moving: CoverState?, val positions: List<Int>) : CardValue

    /** Room groups spec §3.2. */
    data class LightGroup(val onCount: Int, val total: Int) : CardValue
}

data class CardUi(
    val id: String,
    val name: String,
    val kind: Kind,
    /** The kind as discovered; differs from [kind] when a switch is shown as a light. */
    val protocolKind: Kind = kind,
    val deviceClass: String?,
    /** Null means Unassigned (localized by the UI). */
    val areaName: String?,
    val value: CardValue,
    val state: StateValue,
    val capabilities: Capabilities,
    val unit: String?,
    val on: Boolean,
    val wide: Boolean,
    val available: Boolean,
    val pending: Boolean,
    /** The value comes from the entity cache and no live data has confirmed it yet. */
    val stale: Boolean = false,
    val tap: TapAction,
    val hasSheet: Boolean,
    /** The entity is recorded by the history service and history is on: a sensor card opens it, the sheet links to it. */
    val hasHistory: Boolean = false,
    /** Effective area id; [UNASSIGNED_AREA] when the entity has no room. */
    val areaId: String,
    /** The override as it is now, for the override sheet. */
    val ui: UiOverride,
    val hidden: Boolean,
    /** A light's folded device controls (speed, palette…), shown in its sheet instead of as cards. */
    val companions: List<CardUi> = emptyList(),
    /** A room group's member cards in sort order (room groups spec §3); empty for a single card. */
    val members: List<CardUi> = emptyList(),
    /** A cover or light whose room has another one: its Customize shows the "Group with…" switch. */
    val groupable: Boolean = false,
    /** Water heater on-card Force switch (spec §5): null = no toggle, true = currently in `performance`. */
    val forceToggle: Boolean? = null,
    /** Water heater card extras (tank glow, activity chip, inlet); null for every other kind. */
    val heater: HeaterView? = null,
    /** Hub-owned group card: commands go to [id] directly, the hub fans out (hub cover groups spec §2.4). */
    val isHubGroup: Boolean = false,
) {
    val isGroup: Boolean get() = members.isNotEmpty()
}

data class HomeUiState(
    val loading: Boolean = true,
    val link: LinkState = LinkState.Connecting,
    val hero: CardUi? = null,
    val chips: List<ChipUi> = listOf(ChipUi(RoomFilter.All, null)),
    val selected: RoomFilter = RoomFilter.All,
    val cards: List<CardUi> = emptyList(),
    /** Rooms the override sheet can pick from: every area except Unassigned. */
    val rooms: List<Area> = emptyList(),
    val hiddenCount: Int = 0,
    /** The Home search text; blank when not searching. */
    val query: String = "",
) {
    fun card(id: String): CardUi? {
        fun search(cards: List<CardUi>): CardUi? = cards.firstNotNullOfOrNull { c ->
            c.takeIf { it.id == id } ?: search(c.companions) ?: search(c.members)
        }
        return search(cards + listOfNotNull(hero))
    }
}

sealed interface HomeEvent {
    data class NotConfirmed(val id: String, val command: Command) : HomeEvent
    data class Rejected(val id: String, val reason: String) : HomeEvent
    data object SaveFailed : HomeEvent
    data object WaitingForConnection : HomeEvent

    /** Device sheet spec §5.3: a Door opener or Siren wants the user to confirm the tap. */
    data class AskBeforeAction(val id: String, val labelResId: Int, val command: Command) : HomeEvent

    /** Members of one group send that were not confirmed, merged (room groups spec §5). */
    data class GroupNotConfirmed(
        val groupId: String,
        val kind: Kind,
        val failedIds: List<String>,
        val failedNames: List<String>,
        val command: Command,
    ) : HomeEvent
}
