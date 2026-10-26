package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.history.HistoryRefs
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Entity
import com.gekkotron.oikos.model.EntityState
import com.gekkotron.oikos.model.HubGroup
import com.gekkotron.oikos.model.Kind
import com.gekkotron.oikos.model.Placement
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.transport.LinkState
import java.util.Locale

/** Translates an entity label published by the source ("Local temperature"); null keeps it as is. */
fun interface LabelText {
    operator fun invoke(label: String): String?
}

/** Entities + states + areas → everything the Home screen draws (spec §5.2). */
object HomeUiBuilder {
    const val HERO_WEATHER = "hero_weather"
    private val WIDE_CLASSES = setOf("power", "energy")
    private val SHEET_KINDS = setOf(Kind.COVER, Kind.CLIMATE, Kind.NUMBER, Kind.SELECT, Kind.ALARM_PANEL, Kind.VACUUM, Kind.WATER_HEATER)
    private val COMPANION_KINDS = setOf(Kind.NUMBER, Kind.SELECT)

    fun build(
        entities: Map<String, Entity>,
        states: Map<String, EntityState>,
        areas: List<Area>,
        selected: RoomFilter,
        link: LinkState,
        locale: Locale,
        labels: LabelText = LabelText { null },
        /** Non-blank: search every room, hidden devices included, instead of [selected]. */
        query: String = "",
        historyEnabled: Boolean = false,
        /** Hub-owned groups keyed by group id (hub cover groups spec §2.3). */
        hubGroups: Map<String, HubGroup> = emptyMap(),
    ): HomeUiState {
        val names = entities.mapValues { (_, e) -> displayName(e, labels) }
        val areaIndex = areas.withIndex().associate { (i, a) -> a.id to i }
        val areaNames = areas.associate { it.id to it.name }
        val standalone = entities.values.filter { it.partOf == null }
        val children = childrenByParent(entities)
        fun companionsOf(e: Entity): List<CardUi> = companions(e, children, entities, states, areaNames, locale, labels, historyEnabled)
        // What Energy or Security already shows stays off Home unless pinned; search still finds it.
        val visible = standalone.filter { Placement.onHome(it.kind, it.deviceClass, it.ui) }
        val hidden = standalone.filter { it.ui.hidden }
        val hero = visible.filter { it.ui.cardStyle == HERO_WEATHER }.minByOrNull { it.uniqueId }
        val grid = visible.filter { it.uniqueId != hero?.uniqueId }
        val groupable = RoomGroups.groupableIds(grid)

        val occupied = entities.values.mapTo(mutableSetOf()) { it.area }
        val used = grid.mapTo(mutableSetOf()) { it.area }
        val groupFavorites: Map<String, Set<String>> = areas.filter { it.groupFavorites.isNotEmpty() }
            .associate { it.id to it.groupFavorites }
        val hasFavorites = grid.any { it.ui.favorite } || grid.any { favoriteGrouped(it, groupFavorites) }
        val chips = buildList {
            if (hasFavorites) add(ChipUi(RoomFilter.Favorites, null))
            add(ChipUi(RoomFilter.All, null))
            for (area in areas) {
                if (area.id in used) add(ChipUi(RoomFilter.Area(area.id), area.name.takeUnless { area.id == UNASSIGNED_AREA }))
            }
            if (hidden.isNotEmpty()) add(ChipUi(RoomFilter.Hidden, null))
        }
        val fallback = if (hasFavorites) RoomFilter.Favorites else RoomFilter.All
        val effective = selected.takeIf { s -> chips.any { it.filter == s } } ?: fallback

        val shown = when {
            query.isNotBlank() -> standalone.filter { SearchMatch.matches(query, names.getValue(it.uniqueId), it.deviceName, areaNames[it.area]) }
            effective == RoomFilter.Hidden -> hidden
            else -> grid.filter { matches(it, effective, groupFavorites) }
        }
        val sorted = shown
            .sortedWith(
                compareBy<Entity>(
                    { it.ui.order ?: Int.MAX_VALUE },
                    { areaIndex[it.area] ?: Int.MAX_VALUE },
                    { names.getValue(it.uniqueId).lowercase(locale) },
                    { it.uniqueId },
                ),
            )
            .map { card(it, names.getValue(it.uniqueId), states[it.uniqueId], areaNames, locale, companionsOf(it), historyEnabled, it.uniqueId in groupable) }
        // Room groups spec §2.1: groups show in All, a room, and Favourites (a favorited group counts). Hidden and search stay flat.
        val folds = query.isBlank() && effective != RoomFilter.Hidden
        val cards = if (folds) {
            // Hub cover groups spec §2.3: swap hub group tiles for wide cards + drop their members
            // first; the client-side fold then runs over what remains, so a member cannot be
            // double-grouped.
            val hubFolded = RoomGroups.foldHubGroups(sorted, hubGroups)
            // Within each area block, lift group cards above individual devices. Stable sort keeps
            // the alphabetical intra-bucket order from the per-entity sort above; the explicit ui.order
            // pin still wins since it's the first key.
            RoomGroups.fold(hubFolded, areas, groupFavorites).sortedWith(
                compareBy(
                    { it.ui.order ?: Int.MAX_VALUE },
                    { areaIndex[it.areaId] ?: Int.MAX_VALUE },
                    { !it.isGroup },
                ),
            )
        } else {
            sorted
        }

        return HomeUiState(
            loading = entities.isEmpty(),
            link = link,
            hero = hero?.let { card(it, names.getValue(it.uniqueId), states[it.uniqueId], areaNames, locale, companionsOf(it), historyEnabled) },
            chips = chips,
            selected = effective,
            cards = cards,
            rooms = areas.filter { it.id != UNASSIGNED_AREA && it.id in occupied },
            hiddenCount = hidden.size,
            query = query,
        )
    }

    /** One card for [id] wherever it lives (Home, Energy or Security), so another tab can customise it; null if unknown or attached to another card. */
    fun cardFor(
        entities: Map<String, Entity>,
        states: Map<String, EntityState>,
        areas: List<Area>,
        id: String,
        locale: Locale,
        labels: LabelText = LabelText { null },
        historyEnabled: Boolean = false,
    ): CardUi? {
        val e = entities[id]?.takeIf { it.partOf == null } ?: return null
        val areaNames = areas.associate { it.id to it.name }
        return card(e, displayName(e, labels), states[id], areaNames, locale, companions(e, childrenByParent(entities), entities, states, areaNames, locale, labels, historyEnabled), historyEnabled)
    }

    private fun childrenByParent(entities: Map<String, Entity>): Map<String, List<Entity>> =
        entities.values.filter { it.partOf != null && it.kind in COMPANION_KINDS }.groupBy { it.partOf!! }

    private fun companions(
        e: Entity,
        children: Map<String, List<Entity>>,
        entities: Map<String, Entity>,
        states: Map<String, EntityState>,
        areaNames: Map<String, String>,
        locale: Locale,
        labels: LabelText,
        historyEnabled: Boolean,
    ): List<CardUi> {
        val list: List<Entity> = when (e.kind) {
            Kind.LIGHT -> children[e.uniqueId].orEmpty()
            Kind.WATER_HEATER -> {
                val deviceId = e.deviceId
                if (deviceId == null) emptyList()
                else entities.values.filter {
                    it.kind == Kind.NUMBER && it.deviceId == deviceId && !it.ui.hidden && it.uniqueId != e.uniqueId
                }.sortedBy { it.uniqueId }
            }
            else -> emptyList()
        }
        return list
            .map { c -> card(c, companionName(c, labels), states[c.uniqueId], areaNames, locale, emptyList(), historyEnabled) }
            .let { cards -> if (e.kind == Kind.LIGHT) cards.sortedWith(compareBy({ it.name.lowercase(locale) }, { it.id })) else cards }
    }

    private fun matches(entity: Entity, filter: RoomFilter, groupFavorites: Map<String, Set<String>>) = when (filter) {
        RoomFilter.All -> true
        RoomFilter.Favorites -> entity.ui.favorite || favoriteGrouped(entity, groupFavorites)
        is RoomFilter.Area -> entity.area == filter.id
        RoomFilter.Hidden -> false
    }

    /** An entity whose room has its (cover/light) group pinned to Favorites: lifts the whole group into the chip. */
    private fun favoriteGrouped(entity: Entity, groupFavorites: Map<String, Set<String>>): Boolean {
        if (entity.kind !in RoomGroups.GROUP_KINDS || entity.ui.ungrouped) return false
        return RoomGroups.kindKey(entity.kind) in (groupFavorites[entity.area] ?: return false)
    }

    /** A name the user typed wins; otherwise a known label is translated after the device name. */
    private fun displayName(entity: Entity, labels: LabelText): String {
        if (entity.ui.name != null) return entity.name
        val device = entity.deviceName ?: return entity.name
        val label = entity.label?.let(labels::invoke) ?: return entity.name
        return "$device $label"
    }

    /** Inside a light's sheet the device name is redundant: "Effect speed", not "Leds Effect speed". */
    private fun companionName(entity: Entity, labels: LabelText): String {
        if (entity.ui.name != null) return entity.name
        val label = entity.label ?: return entity.name
        return labels(label) ?: label
    }

    private fun hasLightSheet(entity: Entity, companions: List<CardUi>): Boolean {
        val caps = entity.capabilities
        return caps.brightness || caps.effects.isNotEmpty() || caps.hasColor || companions.isNotEmpty()
    }

    private fun card(
        entity: Entity,
        name: String,
        state: EntityState?,
        areaNames: Map<String, String>,
        locale: Locale,
        companions: List<CardUi>,
        historyEnabled: Boolean,
        groupable: Boolean = false,
    ): CardUi {
        val hasHistory = historyEnabled && HistoryRefs.of(entity) != null
        val value = state?.value ?: StateValue.Unknown
        val heater = if (entity.kind == Kind.WATER_HEATER) HeaterView.of(state) else null
        return CardUi(
            id = entity.uniqueId,
            name = name,
            kind = entity.kind,
            protocolKind = entity.protocolKind,
            deviceClass = entity.deviceClass,
            areaName = if (entity.area == UNASSIGNED_AREA) null else areaNames[entity.area] ?: entity.area,
            value = CardFormatter.format(entity, value, locale),
            state = value,
            capabilities = entity.capabilities,
            unit = entity.unit,
            on = heater?.heating ?: isOn(entity.kind, value),
            wide = entity.kind == Kind.SENSOR && entity.deviceClass in WIDE_CLASSES,
            available = state?.available ?: true,
            pending = state?.pending ?: false,
            stale = state?.stale ?: false,
            tap = tapFor(entity.kind, hasHistory),
            hasSheet = entity.kind in SHEET_KINDS || (entity.kind == Kind.LIGHT && hasLightSheet(entity, companions)),
            hasHistory = hasHistory,
            areaId = entity.area,
            ui = entity.ui,
            hidden = entity.ui.hidden,
            companions = companions,
            groupable = groupable,
            forceToggle = forceToggleFor(entity, value),
            heater = heater,
        )
    }

    private fun tapFor(kind: Kind, hasHistory: Boolean) = when (kind) {
        Kind.LIGHT, Kind.SWITCH -> TapAction.TOGGLE
        Kind.BUTTON -> TapAction.PRESS
        Kind.COVER, Kind.CLIMATE, Kind.NUMBER, Kind.SELECT, Kind.ALARM_PANEL, Kind.VACUUM, Kind.WATER_HEATER -> TapAction.OPEN_SHEET
        Kind.SENSOR, Kind.BINARY_SENSOR -> if (hasHistory) TapAction.OPEN_HISTORY else TapAction.NONE
    }

    private fun forceToggleFor(entity: Entity, value: StateValue): Boolean? {
        if (entity.kind != Kind.WATER_HEATER) return null
        val modes = entity.capabilities.hvacModes
        if ("performance" !in modes || "eco" !in modes) return null
        return (value as? StateValue.Climate)?.mode == "performance"
    }

    private fun isOn(kind: Kind, v: StateValue) = when (v) {
        is StateValue.Light -> v.on
        is StateValue.OnOff -> kind == Kind.SWITCH && v.on
        is StateValue.Cover -> v.state == CoverState.OPEN || v.state == CoverState.OPENING ||
            (v.state == CoverState.STOPPED && (v.position ?: 0) > 0)
        is StateValue.Climate -> v.mode != null && v.mode != "off"
        else -> false
    }
}
