package com.gekkotron.oikos.navigation

import android.widget.Toast
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.gekkotron.oikos.AppLink
import com.gekkotron.oikos.AppViewModel
import com.gekkotron.oikos.R
import com.gekkotron.oikos.data.ConnectionProfile
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.designsystem.permissions.AskForNotificationsOnLaunch
import com.gekkotron.oikos.feature.alerts.ui.AlertDetailRoute
import com.gekkotron.oikos.feature.alerts.ui.AlertsRoute
import com.gekkotron.oikos.feature.alerts.ui.RecentAlertsEffects
import com.gekkotron.oikos.feature.alerts.ui.RecentAlertsRoute
import com.gekkotron.oikos.feature.automations.ui.AutomationsRoute
import com.gekkotron.oikos.feature.energy.ui.EnergyRoute
import com.gekkotron.oikos.feature.history.HistoryTarget
import com.gekkotron.oikos.feature.history.ui.HistoryRoute
import com.gekkotron.oikos.feature.security.ui.SecurityRoute
import com.gekkotron.oikos.feature.home.rooms.RoomsRoute
import com.gekkotron.oikos.feature.home.ui.CustomizeEntitySheet
import com.gekkotron.oikos.feature.home.ui.EntityControlSheet
import com.gekkotron.oikos.feature.home.ui.HomeRoute
import com.gekkotron.oikos.feature.onboarding.ui.NotificationsRoute
import com.gekkotron.oikos.feature.onboarding.ui.OnboardingRoute
import com.gekkotron.oikos.feature.settings.licences.LicencesRoute
import com.gekkotron.oikos.feature.settings.rooms.RoomOrderRoute
import com.gekkotron.oikos.feature.settings.ui.SettingsRoute
import com.gekkotron.oikos.presence.WarnBackgroundDataOnLaunch
import kotlinx.serialization.Serializable

@Serializable
data object OnboardingKey : NavKey

@Serializable
data object HomeKey : NavKey

@Serializable
data object RoomsKey : NavKey

@Serializable
data object EnergyKey : NavKey

@Serializable
data object SecurityKey : NavKey

@Serializable
data object AutomationsKey : NavKey

@Serializable
data object SettingsKey : NavKey

@Serializable
data object RoomOrderKey : NavKey

@Serializable
data object LicencesKey : NavKey

@Serializable
data object AlertsKey : NavKey

/** Opened by the notification's `oikos://alert/<id>` link; only the sheet shows, over the app background. */
@Serializable
data class AlertDetailKey(val id: String) : NavKey

/** A history chart; [target] is a `HistoryTarget.encode()` string. */
@Serializable
data class HistoryKey(val target: String) : NavKey

@Serializable
data object NotificationsKey : NavKey

internal enum class AlertLinkAction { NONE, OPEN, DROP, WAIT }

/** Where a tapped notification may land: only on Home (Rooms and the notification step come first; onboarding has no house). */
internal fun alertLinkAction(root: NavKey, pending: String?): AlertLinkAction = linkAction(root, pending?.let(AppLink::Alert))

/** A widget link lands like a notification: only on Home (widgets spec §2.3). */
internal fun linkAction(root: NavKey, pending: AppLink?): AlertLinkAction = when {
    pending == null -> AlertLinkAction.NONE
    root == HomeKey -> AlertLinkAction.OPEN
    root == OnboardingKey -> AlertLinkAction.DROP
    else -> AlertLinkAction.WAIT
}

internal fun linkDestination(link: AppLink): NavKey = when (link) {
    is AppLink.Alert -> AlertDetailKey(link.id)
    is AppLink.History -> HistoryKey(HistoryTarget.Entity(link.entityId).encode())
    AppLink.Settings -> SettingsKey
}

/** The main tabs share the bar's bottom inset; every other screen has no bar, so it keeps the full insets. */
internal fun screenInsets(top: NavKey?, tabInsets: WindowInsets, full: WindowInsets): WindowInsets =
    if (showsTabBar(top)) tabInsets else full

/** Pushes [key] unless it is already on top (a double tap on the gear opens Settings once). */
internal fun push(stack: MutableList<NavKey>, key: NavKey) {
    if (stack.lastOrNull() != key) stack.add(key)
}

/** Pops [key] only while it is on top, and never the last entry (a double tap on a back arrow pops once). */
internal fun pop(stack: MutableList<NavKey>, key: NavKey) {
    if (stack.size > 1 && stack.lastOrNull() == key) stack.removeAt(stack.lastIndex)
}

/** The saved profile picks the root: Ready → Home; none or locked → onboarding (spec §2). */
@Composable
fun OikosNavHost(viewModel: AppViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val route by viewModel.route.collectAsStateWithLifecycle()
    val pendingLink by viewModel.pendingLink.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val root: NavKey = when (val p = profile) {
        ProfileState.Loading -> return // a few milliseconds at launch; the background is already drawn
        // A direct profile that was just set up first gets the Rooms step (spec §5.2 step 5), on the live source, then the notification step.
        is ProfileState.Ready -> when {
            p.roomsPending -> RoomsKey
            p.notificationsPending -> NotificationsKey
            else -> HomeKey
        }
        ProfileState.None, ProfileState.Locked -> OnboardingKey
    }
    // One back stack for the app's lifetime. A new root (onboarding finished, or "set up again")
    // replaces the entries inside it, so NavDisplay pops the old ones and clears their ViewModel
    // stores. Disposing the whole NavDisplay instead (key(root)) never pops anything, and the stale
    // ViewModels come back the next time the same root is shown.
    val backStack = rememberNavBackStack(root)
    LaunchedEffect(root) { resetToRoot(backStack, root) }
    // Push alerts need the permission; the onboarding step only runs once, so a launch on Home still asks while it is missing.
    if (root == HomeKey && (profile as? ProfileState.Ready)?.profile is ConnectionProfile.Direct) AskForNotificationsOnLaunch()
    // Presence away events wait for Wi-Fi while background data is blocked; say so at every launch until it is fixed.
    val presence by viewModel.presence.collectAsStateWithLifecycle()
    if (root == HomeKey) WarnBackgroundDataOnLaunch(presence)
    // Declared after resetToRoot's effect, so a cold start has its Home stack before the sheet is pushed on it.
    LaunchedEffect(pendingLink, root) {
        val link = pendingLink ?: return@LaunchedEffect
        when (linkAction(root, link)) {
            AlertLinkAction.OPEN -> {
                push(backStack, linkDestination(link))
                viewModel.linkOpened()
            }
            AlertLinkAction.DROP -> viewModel.linkOpened()
            AlertLinkAction.WAIT, AlertLinkAction.NONE -> Unit
        }
    }
    val openHistory: (HistoryTarget) -> Unit = { push(backStack, HistoryKey(it.encode())) }
    val deviceSheet: @Composable (String, () -> Unit) -> Unit = { id, dismiss ->
        EntityControlSheet(
            id, dismiss,
            onSaveFailed = { Toast.makeText(context, R.string.device_save_failed, Toast.LENGTH_SHORT).show() },
            onOpenHistory = { openHistory(HistoryTarget.Entity(it)) },
        )
    }
    val top = backStack.lastOrNull()
    val tabs = showsTabBar(top)
    // The demo has no hub: hide the tab, and leave it if the demo is switched on while it is open.
    val showAutomations = (profile as? ProfileState.Ready)?.profile != ConnectionProfile.Demo
    LaunchedEffect(showAutomations, top) { if (!showAutomations && top == AutomationsKey) selectTab(backStack, HomeKey) }
    // The bar owns the bottom inset on the main tabs; their own Scaffolds then only pad top and sides.
    val tabInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (tabs) {
                val tabsVm: MainTabsViewModel = hiltViewModel()
                val status by tabsVm.status.collectAsStateWithLifecycle()
                MainTabBar(selected = top, onSelect = { selectTab(backStack, it) }, status = status, showAutomations = showAutomations)
            }
        },
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<OnboardingKey> { OnboardingRoute() }
                entry<RoomsKey> {
                    RoomsRoute(onDone = {
                        // First run: finishing flips the root to Home, which replaces this entry. From Home: pop back.
                        viewModel.finishRooms()
                        pop(backStack, RoomsKey)
                    })
                }
                entry<NotificationsKey> { NotificationsRoute(onDone = viewModel::finishNotifications) }
                entry<HomeKey> {
                    HomeRoute(
                        onOpenSettings = { push(backStack, SettingsKey) },
                        route = route,
                        onRouteChange = viewModel::setRoute,
                        windowInsets = tabInsets,
                        onOpenHistory = { openHistory(HistoryTarget.Entity(it)) },
                    )
                }
                entry<SettingsKey> {
                    SettingsRoute(
                        route = route,
                        onRouteChange = viewModel::setRoute,
                        onSetUpAgain = viewModel::setUpAgain,
                        onOpenRooms = { push(backStack, RoomOrderKey) },
                        onOpenLicences = { push(backStack, LicencesKey) },
                        onBack = { pop(backStack, SettingsKey) },
                    )
                }
                entry<RoomOrderKey> {
                    RoomOrderRoute(onAssignRooms = { push(backStack, RoomsKey) }, onBack = { pop(backStack, RoomOrderKey) })
                }
                entry<LicencesKey> {
                    val context = LocalContext.current
                    LicencesRoute(
                        loadJson = { context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() } },
                        onBack = { pop(backStack, LicencesKey) },
                    )
                }
                entry<EnergyKey> {
                    EnergyRoute(
                        onOpenHome = { selectTab(backStack, HomeKey) },
                        windowInsets = tabInsets,
                        onOpenHistory = { id -> openHistory(id?.let(HistoryTarget::Entity) ?: HistoryTarget.House) },
                    )
                }
                entry<AutomationsKey> { AutomationsRoute(windowInsets = tabInsets) }
                entry<AlertsKey> { AlertsRoute(onBack = { pop(backStack, AlertsKey) }, windowInsets = screenInsets(AlertsKey, tabInsets, ScaffoldDefaults.contentWindowInsets), deviceSheet = deviceSheet) }
                entry<AlertDetailKey> { key -> AlertDetailRoute(key.id, onDismiss = { pop(backStack, key) }, deviceSheet = deviceSheet) }
                entry<HistoryKey> { key ->
                    val target = HistoryTarget.decode(key.target)
                    if (target == null) LaunchedEffect(key) { pop(backStack, key) }
                    else HistoryRoute(target, onBack = { pop(backStack, key) }, windowInsets = screenInsets(key, tabInsets, ScaffoldDefaults.contentWindowInsets))
                }
                entry<SecurityKey> {
                    SecurityRoute(
                        windowInsets = tabInsets,
                        customizeSheet = { id, onDismiss, onSaveFailed -> CustomizeEntitySheet(id, onDismiss, onSaveFailed) },
                        alertsSection = { RecentAlertsRoute(onOpenAll = { push(backStack, AlertsKey) }, deviceSheet = deviceSheet) },
                        alertsEffects = { snackbar -> RecentAlertsEffects(snackbar) },
                        onOpenHistory = { openHistory(HistoryTarget.Entity(it)) },
                    )
                }
            },
        )
    }
}
