package com.gekkotron.oikos.feature.automations.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gekkotron.oikos.designsystem.OikosDimens
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.components.ChipItem
import com.gekkotron.oikos.designsystem.components.OikosCard
import com.gekkotron.oikos.designsystem.components.RoomChips
import com.gekkotron.oikos.feature.automations.R
import com.gekkotron.oikos.feature.automations.presentation.AreaLabel
import com.gekkotron.oikos.feature.automations.presentation.AutomationChip
import com.gekkotron.oikos.feature.automations.presentation.AutomationRow
import com.gekkotron.oikos.feature.automations.presentation.AutomationsUiState
import kotlinx.coroutines.launch

private const val PLACEHOLDER_ROWS = 4

@Composable
fun AutomationsScreen(
    state: AutomationsUiState,
    onChipSelected: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onToastShown: () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
) {
    val layoutDirection = LocalLayoutDirection.current
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    // One-shot: acknowledged right away, and shown from the screen's scope so the toast clearing does not cancel it.
    LaunchedEffect(state.toast) {
        state.toast?.let { res ->
            scope.launch { snackbar.showSnackbar(resources.getString(res)) }
            onToastShown()
        }
    }
    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        contentColor = OikosTheme.colors.content,
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = windowInsets,
    ) { padding ->
        val start = padding.calculateStartPadding(layoutDirection) + 16.dp
        val end = padding.calculateEndPadding(layoutDirection) + 16.dp
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding() + 8.dp)) {
            Text(
                stringResource(R.string.automations_title),
                style = OikosTheme.type.display,
                modifier = Modifier.padding(start = start, end = end, top = 8.dp).semantics { heading() },
            )
            if (state.isOffline) {
                Text(
                    stringResource(R.string.automations_offline_banner),
                    style = OikosTheme.type.label,
                    color = OikosTheme.colors.contentMuted,
                    modifier = Modifier.padding(start = start, end = end, top = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            AutomationChips(state, onChipSelected, Modifier.padding(horizontal = 16.dp))
            val listPadding = PaddingValues(start = start, end = end, top = 12.dp, bottom = padding.calculateBottomPadding() + 24.dp)
            when {
                state.isLoading && state.rows.isEmpty() -> LazyColumn(
                    contentPadding = listPadding,
                    verticalArrangement = Arrangement.spacedBy(OikosDimens.gridGap),
                ) { items(PLACEHOLDER_ROWS) { PlaceholderRow() } }
                state.rows.isEmpty() -> EmptyState(Modifier.fillMaxSize())
                else -> LazyColumn(
                    contentPadding = listPadding,
                    verticalArrangement = Arrangement.spacedBy(OikosDimens.gridGap),
                ) { items(state.rows, key = { it.id }) { row -> AutomationTile(row, onToggle) } }
            }
        }
    }
}

@Composable
private fun AutomationChips(state: AutomationsUiState, onSelect: (String) -> Unit, modifier: Modifier) {
    val all = stringResource(R.string.automations_chip_all)
    val unassigned = stringResource(R.string.automations_chip_unassigned)
    val items = state.chips.map { chip ->
        ChipItem(
            chip.key,
            when (chip) {
                AutomationChip.All -> all
                is AutomationChip.Room -> chip.name
                AutomationChip.Unassigned -> unassigned
            },
        )
    }
    RoomChips(items, state.selectedChipKey, onSelect, modifier)
}

@Composable
private fun AutomationTile(row: AutomationRow, onToggle: (String, Boolean) -> Unit) {
    val area = when (val label = row.area) {
        is AreaLabel.Named -> label.name
        AreaLabel.Unassigned -> stringResource(R.string.automations_chip_unassigned)
        null -> null
    }
    OikosCard(Modifier.fillMaxWidth(), minHeight = 0.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.name, style = OikosTheme.type.cardName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (area != null) {
                    Text(area, style = OikosTheme.type.label, color = OikosTheme.colors.contentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Switch(
                checked = row.enabled,
                onCheckedChange = { onToggle(row.id, it) },
                enabled = row.toggleEnabled,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** Tile-shaped muted block with a gentle pulse; stands in for a row until the first snapshot lands. */
@Composable
private fun PlaceholderRow() {
    val pulse by rememberInfiniteTransition(label = "automations-placeholder").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    OikosCard(Modifier.fillMaxWidth().testTag("automations-placeholder").alpha(pulse), minHeight = 0.dp) {
        Box(Modifier.height(36.dp))
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(modifier.padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.automations_empty_title), style = OikosTheme.type.cardName, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.automations_empty_body),
            style = OikosTheme.type.body,
            color = OikosTheme.colors.contentMuted,
            textAlign = TextAlign.Center,
        )
    }
}
