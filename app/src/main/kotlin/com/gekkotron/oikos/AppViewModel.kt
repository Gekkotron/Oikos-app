package com.gekkotron.oikos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gekkotron.oikos.connection.EntitySourceSession
import com.gekkotron.oikos.connection.routeChoice
import com.gekkotron.oikos.connection.toPreference
import com.gekkotron.oikos.data.AppPreferences
import com.gekkotron.oikos.data.PresenceConfig
import com.gekkotron.oikos.data.ProfileRepository
import com.gekkotron.oikos.data.ProfileState
import com.gekkotron.oikos.data.ThemePreference
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.push.PushRegistrar
import com.gekkotron.oikos.transport.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The stored preference as the design system's choice; exhaustive, so a new value fails the build instead of crashing. */
internal fun ThemePreference.toChoice(): ThemeChoice = when (this) {
    ThemePreference.SYSTEM -> ThemeChoice.SYSTEM
    ThemePreference.NIGHT_GLASS -> ThemeChoice.NIGHT_GLASS
    ThemePreference.WARM_MINIMAL -> ThemeChoice.WARM_MINIMAL
}

@HiltViewModel
class AppViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val sources: EntitySourceSession,
    private val preferences: AppPreferences,
    private val registrar: PushRegistrar,
    private val cleanup: SetUpAgainCleanup,
    private val demoAlerts: DemoAlertSeeder,
) : ViewModel() {
    init {
        demoAlerts.start()
    }

    val profile: StateFlow<ProfileState> = profiles.state

    /** The theme picked on this phone; null until the store has loaded (settings spec §3.2). */
    val theme: StateFlow<ThemeChoice?> = preferences.theme
        .map { it?.toChoice() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, preferences.theme.value?.toChoice())

    /** This phone's presence settings; null until the store has loaded. */
    val presence: StateFlow<PresenceConfig?> = preferences.presence

    /** The link sheet's route row; null hides it (no gateway in the profile). */
    val route: StateFlow<Route?> =
        combine(profiles.state, profiles.route, ::routeChoice).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _pendingLink = MutableStateFlow<AppLink?>(null)

    /** A notification or widget tap waiting for the navigation host to show it (push alerts spec §7.3, widgets spec §2.3). */
    val pendingLink: StateFlow<AppLink?> = _pendingLink

    fun openLink(link: AppLink) {
        _pendingLink.value = link
    }

    fun openAlert(id: String) = openLink(AppLink.Alert(id))

    fun linkOpened() {
        _pendingLink.value = null
    }

    /** The Rooms step was finished or skipped (a no-op when it was opened from Home). */
    fun finishRooms() {
        viewModelScope.launch { profiles.finishRooms() }
    }

    /** The notification step was answered or skipped: leave it, and register for push either way (push alerts spec §7.5). */
    fun finishNotifications() {
        viewModelScope.launch {
            try {
                profiles.finishNotifications()
            } finally {
                registrar.request() // a failed write must not skip the registration
            }
        }
    }

    /** Rebuilds the transport for the new route; the discovery source and the house on screen stay. */
    fun setRoute(route: Route) {
        viewModelScope.launch { profiles.setRoute(route.toPreference()) }
    }

    /**
     * Forgets the profile. The transport closes, the discovery source is dropped (the next profile may
     * be an equal one, such as the demo again, and must still start from an empty house), and the app
     * returns to onboarding.
     */
    fun setUpAgain() {
        viewModelScope.launch {
            cleanup.run()
            profiles.clear()
            sources.reset()
        }
    }
}
