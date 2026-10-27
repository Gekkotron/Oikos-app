package com.gekkotron.oikos.feature.automations.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.feature.automations.R
import com.gekkotron.oikos.flows.FlowDto
import com.gekkotron.oikos.flows.FlowsRepository
import com.gekkotron.oikos.flows.FlowsSnapshot
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.source.EntitySource
import com.gekkotron.oikos.transport.LinkState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AutomationsViewModel internal constructor(
    private val repo: FlowsRepository,
    areas: Flow<List<Area>>,
    link: Flow<LinkState>,
    dispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(repo: FlowsRepository, source: EntitySource) : this(repo, source.areas, source.link, Dispatchers.Default)

    private val selected = MutableStateFlow(ALL_KEY)
    private val toast = MutableStateFlow<Int?>(null)

    val state: StateFlow<AutomationsUiState> =
        combine(repo.flows, areas, link, selected, toast) { snap, areaList, linkState, sel, toastRes ->
            build(snap, areaList, linkState !is LinkState.Connected, sel, toastRes)
        }
            .flowOn(dispatcher)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                AutomationsUiState(true, false, listOf(AutomationChip.All), ALL_KEY, emptyList(), null),
            )

    fun onChipSelected(key: String) = selected.update { key }

    fun onToastShown() = toast.update { null }

    fun onToggle(id: String, enabled: Boolean) {
        if (state.value.isOffline || id in repo.flows.value.inFlight) return
        viewModelScope.launch {
            repo.setEnabled(id, enabled).onFailure { toast.value = R.string.automations_toggle_failed }
        }
    }

    private fun build(
        snap: FlowsSnapshot,
        areaList: List<Area>,
        offline: Boolean,
        sel: String,
        toastRes: Int?,
    ): AutomationsUiState {
        val rooms = areaList.filter { it.id != UNASSIGNED_AREA }
        val byId = rooms.withIndex().associate { (i, a) -> a.id to (i to a) }
        // A flow is unassigned when its area is null, blank, or not one of the source's rooms.
        fun roomOf(area: String?) = area?.takeIf { it.isNotBlank() }?.let { byId[it] }
        val hasUnassigned = snap.flows.any { roomOf(it.area) == null }
        val chips = buildList {
            add(AutomationChip.All)
            rooms.forEach { add(AutomationChip.Room(it.id, it.name)) }
            if (hasUnassigned) add(AutomationChip.Unassigned)
        }
        val selectedKey = if (chips.any { it.key == sel }) sel else ALL_KEY

        fun row(f: FlowDto, label: AreaLabel?) = AutomationRow(
            id = f.id,
            name = f.name,
            area = label,
            enabled = f.enabled,
            toggleEnabled = !offline && f.id !in snap.inFlight,
        )

        val rows = when (selectedKey) {
            ALL_KEY -> snap.flows
                .sortedWith(
                    compareBy<FlowDto> { roomOf(it.area)?.first ?: Int.MAX_VALUE }
                        .thenBy { it.name.lowercase() }
                        .thenBy { it.id },
                )
                .map { f -> row(f, roomOf(f.area)?.let { AreaLabel.Named(it.second.name) } ?: AreaLabel.Unassigned) }
            UNASSIGNED_AREA -> snap.flows.filter { roomOf(it.area) == null }.map { row(it, null) }
            else -> snap.flows.filter { roomOf(it.area)?.second?.id == selectedKey }.map { row(it, null) }
        }
        return AutomationsUiState(
            isLoading = !offline && !snap.isSettled,
            isOffline = offline,
            chips = chips,
            selectedChipKey = selectedKey,
            rows = rows,
            toast = toastRes,
        )
    }
}
