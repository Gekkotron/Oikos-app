package com.gekkotron.oikos.feature.widget.ui

import android.content.ComponentName
import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.feature.widget.TileAction
import com.gekkotron.oikos.feature.widget.model.LinkStatus
import com.gekkotron.oikos.feature.widget.model.TileIcon
import com.gekkotron.oikos.feature.widget.model.TileSnapshot
import com.gekkotron.oikos.feature.widget.model.TileStatus
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetContentTest {
    private val now = 10_000_000L
    private val none: Action = actionStartActivity(ComponentName("test", "test.Noop"))
    private val noop = object : WidgetActions {
        override fun tap(action: TileAction): Action = none
        override fun refresh(): Action = none
        override fun reconfigure(): Action = none
        override fun openApp(): Action = none
        override fun openSettings(): Action = none
    }

    private fun tile(id: String, name: String, kind: Kind, value: TileValue, f: (TileSnapshot) -> TileSnapshot = { it }) =
        f(TileSnapshot(id, name, kind, TileIcon.SWITCH, value, updatedAt = now))

    private fun state(vararg tiles: TileSnapshot?, link: LinkStatus = LinkStatus.FRESH, configured: Boolean = true) =
        WidgetUiState(tiles.toList(), null, link, lastFresh = now - 60_000, now = now, configured = configured)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun small_size_shows_first_tile_only() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(110.dp, 48.dp))
        provideComposable {
            WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)), tile("b", "Lamp", Kind.LIGHT, TileValue.Light(false, null))), noop)
        }
        onNode(hasText("Kettle")).assertExists()
        onNode(hasText("Lamp")).assertDoesNotExist()
    }

    @Test
    fun single_tile_shows_value_and_name_without_footer() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(180.dp, 60.dp))
        provideComposable { WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true))), noop) }
        onNode(hasText("Kettle")).assertExists()
        onNode(hasText("On")).assertExists()
        onNode(hasText("Updated")).assertDoesNotExist()
    }

    @Test
    fun cover_buttons_show_on_a_wide_single_tile() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 60.dp))
        provideComposable { WidgetContent(state(tile("b", "Blind", Kind.COVER, TileValue.Cover(CoverState.OPEN, 60))), noop) }
        onNode(hasText("Stop")).assertExists()
        onNode(hasText("Close")).assertExists()
    }

    @Test
    fun large_size_shows_all_tiles_and_footer() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable {
            WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)), tile("b", "Blind", Kind.COVER, TileValue.Cover(CoverState.OPEN, 60))), noop)
        }
        onNode(hasText("Kettle")).assertExists()
        onNode(hasText("Open 60 %")).assertExists()
        onNode(hasText("Updated")).assertExists()
    }

    @Test
    fun missing_tile_shows_dash_and_removed_shows_removed() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable {
            WidgetContent(state(null, tile("b", "Old", Kind.SWITCH, TileValue.OnOff(true)) { it.copy(status = TileStatus.REMOVED) }), noop)
        }
        onNode(hasText("Removed")).assertExists()
        onNode(hasText("Old")).assertExists()
    }

    @Test
    fun not_configured_shows_set_up() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        provideComposable { WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)), link = LinkStatus.NOT_CONFIGURED), noop) }
        onNode(hasText("Set up Oikos")).assertExists()
        onNode(hasText("Kettle")).assertDoesNotExist()
    }

    @Test
    fun empty_config_shows_hint() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 110.dp))
        provideComposable { WidgetContent(state(configured = false), noop) }
        onNode(hasText("Long-press to choose devices")).assertExists()
    }

    @Test
    fun failed_tile_shows_marker() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable { WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)) { it.copy(status = TileStatus.FAILED) }), noop) }
        onNode(hasText("!")).assertExists()
    }

    @Test
    fun pending_tile_shows_ellipsis() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable { WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)) { it.copy(status = TileStatus.PENDING, pendingSince = now) }), noop) }
        onNode(hasText("…")).assertExists()
    }

    @Test
    fun offline_footer() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable { WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)), link = LinkStatus.OFFLINE), noop) }
        onNode(hasText("Offline")).assertExists()
    }

    @Test
    fun auth_failure_footer() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, 250.dp))
        provideComposable { WidgetContent(state(tile("a", "Kettle", Kind.SWITCH, TileValue.OnOff(true)), link = LinkStatus.AUTH_FAILED), noop) }
        onNode(hasText("Sign-in failed")).assertExists()
    }
}

@RunWith(AndroidJUnit4::class)
class TileIsOnTest {
    private val now = 10_000_000L
    private fun tile(kind: Kind, value: TileValue, f: (TileSnapshot) -> TileSnapshot = { it }) =
        f(TileSnapshot("a", "A", kind, TileIcon.SWITCH, value, updatedAt = now))

    @Test
    fun live_on_values_take_the_accent() {
        assert(tile(Kind.LIGHT, TileValue.Light(true, 40)).isOn(now))
        assert(tile(Kind.SWITCH, TileValue.OnOff(true)).isOn(now))
        assert(tile(Kind.COVER, TileValue.Cover(CoverState.OPEN, 100)).isOn(now))
        // A shutter stopped midway is open, even one that reports no position.
        assert(tile(Kind.COVER, TileValue.Cover(CoverState.STOPPED, null)).isOn(now))
        assert(tile(Kind.ALARM_PANEL, TileValue.Alarm(com.gekkotron.oikos.model.AlarmState.ARMED_AWAY)).isOn(now))
    }

    @Test
    fun off_stale_unavailable_and_sensors_stay_neutral() {
        assert(!tile(Kind.LIGHT, TileValue.Light(false, null)).isOn(now))
        assert(!tile(Kind.COVER, TileValue.Cover(CoverState.CLOSED, 0)).isOn(now))
        assert(!tile(Kind.ALARM_PANEL, TileValue.Alarm(com.gekkotron.oikos.model.AlarmState.DISARMED)).isOn(now))
        assert(!tile(Kind.BINARY_SENSOR, TileValue.OnOff(true)).isOn(now))
        assert(!tile(Kind.SWITCH, TileValue.OnOff(true)) { it.copy(updatedAt = now - TileSnapshot.STALE_MS - 1) }.isOn(now))
        assert(!tile(Kind.SWITCH, TileValue.OnOff(true)) { it.copy(available = false) }.isOn(now))
    }
}
