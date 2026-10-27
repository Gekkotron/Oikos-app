package com.gekkotron.oikos.feature.automations.presentation

import androidx.annotation.StringRes
import com.gekkotron.oikos.model.UNASSIGNED_AREA

const val ALL_KEY = "__all__"

/** A filter chip; the screen maps [All] and [Unassigned] to localized labels. */
sealed interface AutomationChip {
    val key: String

    data object All : AutomationChip {
        override val key = ALL_KEY
    }

    data class Room(val id: String, val name: String) : AutomationChip {
        override val key get() = id
    }

    data object Unassigned : AutomationChip {
        override val key = UNASSIGNED_AREA
    }
}

sealed interface AreaLabel {
    data class Named(val name: String) : AreaLabel
    data object Unassigned : AreaLabel
}

/** [area] is set only under the All chip; [toggleEnabled] is false offline or while a request runs. */
data class AutomationRow(
    val id: String,
    val name: String,
    val area: AreaLabel?,
    val enabled: Boolean,
    val toggleEnabled: Boolean,
)

data class AutomationsUiState(
    val isLoading: Boolean,
    val isOffline: Boolean,
    val chips: List<AutomationChip>,
    val selectedChipKey: String,
    val rows: List<AutomationRow>,
    @StringRes val toast: Int?,
)
