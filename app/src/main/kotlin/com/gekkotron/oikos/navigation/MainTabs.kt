package com.gekkotron.oikos.navigation

import androidx.compose.foundation.layout.size
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.gekkotron.oikos.R
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon

/** Energy, Security and Automations sit on top of Home and replace each other, so back always returns to Home (security spec §5). */
private val SIDE_TABS: Set<NavKey> = setOf(EnergyKey, SecurityKey, AutomationsKey)

internal fun selectTab(stack: MutableList<NavKey>, key: NavKey) {
    if (key == HomeKey) {
        stack.removeAll { it in SIDE_TABS }
        return
    }
    if (stack.lastOrNull() == key) return
    stack.removeAll { it in SIDE_TABS }
    stack.add(key)
}

/**
 * A new root (onboarding finished, "set up again") replaces the whole stack. The same root keeps it, so a
 * stack restored after rotation or a theme change stays on the Energy tab instead of jumping back to Home.
 */
internal fun resetToRoot(stack: MutableList<NavKey>, root: NavKey) {
    if (stack.firstOrNull() == root) return
    stack.add(root)
    stack.removeAll { it != root }
}

internal fun showsTabBar(top: NavKey?): Boolean = top == HomeKey || (top != null && top in SIDE_TABS)

@Composable
internal fun MainTabBar(
    selected: NavKey?,
    onSelect: (NavKey) -> Unit,
    status: TabStatus = TabStatus(),
    /** False under the demo: it has no hub whose flows could be toggled. */
    showAutomations: Boolean = true,
) {
    val colors = OikosTheme.colors
    val itemColors = NavigationBarItemDefaults.colors(
        // The icon sits on the indicator pill; the label sits below it, on the bar.
        selectedIconColor = colors.chipSelectedContent,
        selectedTextColor = colors.content,
        unselectedIconColor = colors.contentMuted,
        unselectedTextColor = colors.contentMuted,
        indicatorColor = colors.chipSelected,
    )
    // When unselected and active, the glyph fills with its state colour; selected keeps the pill contrast.
    val bolt = if (!status.solarActive || selected == EnergyKey) null else colors.solar
    val shield = if (!status.alarmArmed || selected == SecurityKey) null else colors.warning
    NavigationBar(containerColor = colors.backgroundTop.copy(alpha = 0.92f)) {
        NavigationBarItem(
            selected = selected == HomeKey,
            onClick = { onSelect(HomeKey) },
            icon = { StaticIcon(OikosGlyph.HOUSE, modifier = Modifier.size(24.dp)) },
            label = { Text(stringResource(R.string.nav_home), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = itemColors,
        )
        NavigationBarItem(
            selected = selected == EnergyKey,
            onClick = { onSelect(EnergyKey) },
            icon = { StaticIcon(OikosGlyph.BOLT, accent = bolt, modifier = Modifier.size(24.dp)) },
            label = { Text(stringResource(R.string.nav_energy), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = itemColors,
        )
        NavigationBarItem(
            selected = selected == SecurityKey,
            onClick = { onSelect(SecurityKey) },
            icon = { StaticIcon(OikosGlyph.SHIELD, accent = shield, modifier = Modifier.size(24.dp)) },
            label = { Text(stringResource(R.string.nav_security), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = itemColors,
        )
        if (showAutomations) {
            NavigationBarItem(
                selected = selected == AutomationsKey,
                onClick = { onSelect(AutomationsKey) },
                icon = { StaticIcon(OikosGlyph.AUTOMATION, modifier = Modifier.size(24.dp)) },
                label = { Text(stringResource(R.string.nav_automations), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = itemColors,
            )
        }
    }
}
