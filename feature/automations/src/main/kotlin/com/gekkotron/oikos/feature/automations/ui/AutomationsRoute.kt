package com.gekkotron.oikos.feature.automations.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gekkotron.oikos.feature.automations.presentation.AutomationsViewModel

@Composable
fun AutomationsRoute(
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    viewModel: AutomationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AutomationsScreen(
        state,
        onChipSelected = viewModel::onChipSelected,
        onToggle = viewModel::onToggle,
        onToastShown = viewModel::onToastShown,
        windowInsets = windowInsets,
    )
}
