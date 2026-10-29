package com.gekkotron.oikos.feature.widget.ui

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import com.gekkotron.oikos.feature.widget.R
import com.gekkotron.oikos.feature.widget.TileAction
import com.gekkotron.oikos.feature.widget.TileActions
import com.gekkotron.oikos.feature.widget.model.LinkStatus
import com.gekkotron.oikos.feature.widget.model.TileIcon
import com.gekkotron.oikos.feature.widget.model.TileSnapshot
import com.gekkotron.oikos.feature.widget.model.TileStatus
import com.gekkotron.oikos.feature.widget.model.TileValue
import com.gekkotron.oikos.feature.widget.model.WidgetCommand
import com.gekkotron.oikos.model.AlarmMode
import com.gekkotron.oikos.model.AlarmState
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.Kind
import java.text.DateFormat
import java.util.Date

data class WidgetUiState(
    /** One entry per configured id, in order; null = never seen. */
    val tiles: List<TileSnapshot?>,
    val armMode: AlarmMode?,
    val link: LinkStatus,
    val lastFresh: Long?,
    val now: Long,
    val configured: Boolean,
    val voiceEnabled: Boolean = false,
)

/** Turns what a tap means into a Glance [Action]; the widget supplies the Android side. */
interface WidgetActions {
    fun tap(action: TileAction): Action

    fun refresh(): Action

    fun reconfigure(): Action

    fun openApp(): Action

    fun openSettings(): Action

    /** Opens the voice sheet; defaults to the app for hosts that have none. */
    fun openVoice(): Action = openApp()
}

/** The single large tile when the widget is one row high (widgets spec §2.2). */
private val SINGLE_TILE_MAX_HEIGHT = 100.dp

/** Wide enough for two columns, or for a cover tile's Open / Stop / Close buttons. */
private val WIDE = 180.dp

/** Space around the content, shared by the layout and by [tilesThatFit]. */
private val PAD = 10.dp
private val FOOTER = 28.dp
private val TILE_HEIGHT = 58.dp
private val GAP = 6.dp

/** A grid row: the tile and the gap below it. */
private val ROW = TILE_HEIGHT + GAP

/** How many tiles the grid shows at a size; the config screen uses the same rule. */
fun tilesThatFit(width: Dp, height: Dp): Int {
    if (height < SINGLE_TILE_MAX_HEIGHT) return 1
    val columns = if (width >= WIDE) 2 else 1
    val rows = ((height - FOOTER - PAD * 2) / ROW).toInt().coerceAtLeast(1)
    return columns * rows
}

/**
 * Oikos colours for text and icons (day = Warm Minimal, night = Night Glass), explicit rather than Glance's dynamic colours so
 * the widget keeps the app's identity and an on/off accent. The surfaces are shape drawables over `res/values{,-night}/colors.xml`
 * (they also give rounded corners below API 31); keep the two in step.
 */
private fun tone(day: Long, night: Long) = ColorProvider(day = Color(day), night = Color(night))

private object Palette {
    val content = tone(0xFF1F1B16, 0xFFE8ECFF)
    val contentOn = tone(0xFFF5F1EA, 0xFFFFF3DC)
    val muted = tone(0xFF6F6A63, 0xFFA3A9C6)
    val mutedOn = tone(0xFFB9B2A6, 0xFFE0CFA8)
    val dim = tone(0xFFA09A90, 0xFF6F7694)
    val icon = tone(0xFF6F6A63, 0xFFA3A9C6)
    val iconOn = tone(0xFFFFC457, 0xFFFFC457)
    val error = tone(0xFFA23B4F, 0xFFFF9AB2)
    val onError = tone(0xFFFFFFFF, 0xFF10172E)
}

/** A tile reads "on" (accent surface) only when its value is live and says so; stale or missing never glows. */
internal fun TileSnapshot.isOn(now: Long): Boolean {
    if (!available || isStale(now) || effectiveStatus(now) == TileStatus.REMOVED) return false
    return when (val v = value) {
        is TileValue.Light -> v.on
        is TileValue.OnOff -> kind == Kind.SWITCH && v.on
        is TileValue.Cover -> v.state == CoverState.OPEN || v.state == CoverState.OPENING || v.state == CoverState.CLOSING ||
            (v.state == CoverState.STOPPED && v.position != 0)
        is TileValue.Climate -> v.mode != null && v.mode != "off"
        is TileValue.Alarm -> v.state != AlarmState.DISARMED && v.state != AlarmState.DISARMING
        else -> false
    }
}

@Composable
fun WidgetContent(state: WidgetUiState, actions: WidgetActions) {
    val context = LocalContext.current
    val size = LocalSize.current
    val single = size.height < SINGLE_TILE_MAX_HEIGHT
    Column(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_bg)).padding(if (single) 6.dp else PAD)) {
        when {
            state.link == LinkStatus.NOT_CONFIGURED -> Message(R.drawable.ic_widget_home, context.getString(R.string.widget_set_up), actions.openApp())
            !state.configured -> Message(R.drawable.ic_widget_pick, context.getString(R.string.widget_empty), actions.reconfigure())
            single -> Tile(state.tiles.firstOrNull(), state, actions, large = true, modifier = GlanceModifier.fillMaxSize())
            else -> {
                Grid(state, actions, columns = if (size.width >= WIDE) 2 else 1, count = tilesThatFit(size.width, size.height))
                Footer(state, actions)
            }
        }
    }
}

@Composable
private fun Grid(state: WidgetUiState, actions: WidgetActions, columns: Int, count: Int) {
    Column(GlanceModifier.fillMaxWidth()) {
        state.tiles.take(count).chunked(columns).forEach { row ->
            Row(GlanceModifier.fillMaxWidth().height(ROW), verticalAlignment = Alignment.Top) {
                row.forEachIndexed { i, tile ->
                    if (i > 0) Spacer(GlanceModifier.width(GAP))
                    Tile(tile, state, actions, large = false, modifier = GlanceModifier.defaultWeight().height(TILE_HEIGHT))
                }
                // Keep a short last row the width of a full tile, not stretched across the grid.
                repeat(columns - row.size) {
                    Spacer(GlanceModifier.width(GAP))
                    Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun Tile(tile: TileSnapshot?, state: WidgetUiState, actions: WidgetActions, large: Boolean, modifier: GlanceModifier) {
    val context = LocalContext.current
    val status = tile?.effectiveStatus(state.now)
    val dim = tile == null || tile.isStale(state.now) || !tile.available
    val on = tile?.isOn(state.now) == true
    val text = when {
        tile == null -> "—"
        status == TileStatus.REMOVED -> context.getString(R.string.widget_removed)
        status == TileStatus.PENDING -> "…"
        else -> TileText.of(context, tile.value, context.resources.configuration.locales[0])
    }
    val tap = tile?.let { actions.tap(TileActions.forTap(it, state.armMode)) } ?: actions.openApp()
    val valueColor = when {
        on -> Palette.contentOn
        dim -> Palette.dim
        else -> Palette.content
    }
    val nameColor = if (on) Palette.mutedOn else if (dim) Palette.dim else Palette.muted
    val iconColor = if (on) Palette.iconOn else if (dim) Palette.dim else Palette.icon
    val surface = if (on) R.drawable.widget_tile_on else R.drawable.widget_tile
    val chip = if (on) R.drawable.widget_chip_on else R.drawable.widget_chip
    val name = tile?.name ?: "—"
    val wideCover = large && tile != null && tile.kind == Kind.COVER && status != TileStatus.REMOVED && LocalSize.current.width >= WIDE

    Row(
        modifier.background(ImageProvider(surface)).padding(horizontal = if (large) 14.dp else 10.dp).clickable(tap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(iconRes(tile?.icon), chip, iconColor, chipSize = if (large) 44.dp else 34.dp, iconSize = if (large) 24.dp else 18.dp)
        Spacer(GlanceModifier.width(if (large) 12.dp else 10.dp))
        Column(GlanceModifier.defaultWeight()) {
            if (large) {
                // One tile on its own: the value leads.
                Text(text, maxLines = 1, style = TextStyle(color = valueColor, fontWeight = FontWeight.Bold, fontSize = 20.sp))
                Text(name, maxLines = 1, style = TextStyle(color = nameColor, fontSize = 12.sp))
            } else {
                Text(name, maxLines = 1, style = TextStyle(color = nameColor, fontSize = 11.sp))
                Text(text, maxLines = 1, style = TextStyle(color = valueColor, fontWeight = FontWeight.Bold, fontSize = 15.sp))
            }
        }
        if (status == TileStatus.FAILED) {
            Spacer(GlanceModifier.width(6.dp))
            Box(GlanceModifier.size(18.dp).background(ImageProvider(R.drawable.widget_badge)), contentAlignment = Alignment.Center) {
                Text("!", style = TextStyle(color = Palette.onError, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center))
            }
        }
        if (wideCover && tile != null) {
            Spacer(GlanceModifier.width(8.dp))
            CoverButtons(tile, actions)
        }
    }
}

@Composable
private fun IconChip(@DrawableRes icon: Int, @DrawableRes chip: Int, tint: ColorProvider, chipSize: Dp, iconSize: Dp) {
    Box(GlanceModifier.size(chipSize).background(ImageProvider(chip)), contentAlignment = Alignment.Center) {
        Image(ImageProvider(icon), contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = GlanceModifier.size(iconSize))
    }
}

@Composable
private fun CoverButtons(tile: TileSnapshot, actions: WidgetActions) {
    val context = LocalContext.current
    val buttons = listOf(
        WidgetCommand.Open to R.string.widget_cover_open,
        WidgetCommand.Stop to R.string.widget_cover_stop,
        WidgetCommand.Close to R.string.widget_cover_close,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        buttons.forEachIndexed { i, (command, label) ->
            if (i > 0) Spacer(GlanceModifier.width(4.dp))
            Box(
                GlanceModifier.background(ImageProvider(R.drawable.widget_pill)).padding(horizontal = 10.dp, vertical = 6.dp)
                    .clickable(actions.tap(TileActions.coverButton(tile, command))),
                contentAlignment = Alignment.Center,
            ) {
                Text(context.getString(label), maxLines = 1, style = TextStyle(color = Palette.content, fontWeight = FontWeight.Medium, fontSize = 12.sp))
            }
        }
    }
}

@Composable
private fun Footer(state: WidgetUiState, actions: WidgetActions) {
    val context = LocalContext.current
    val time = state.lastFresh?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
    val label: String
    val onClick: Action
    var problem = false
    when (state.link) {
        LinkStatus.OFFLINE -> {
            label = time?.let { context.getString(R.string.widget_offline, it) } ?: context.getString(R.string.widget_offline_never)
            onClick = actions.refresh()
            problem = true
        }
        LinkStatus.AUTH_FAILED -> {
            label = context.getString(R.string.widget_sign_in_failed)
            onClick = actions.openSettings()
            problem = true
        }
        else -> {
            label = time?.let { context.getString(R.string.widget_updated, it) }.orEmpty()
            onClick = actions.refresh()
        }
    }
    val tone = if (problem) Palette.error else Palette.dim
    // The whole strip is the touch target (full width, padded top and bottom), not only the 14dp glyph.
    Row(GlanceModifier.fillMaxWidth().height(FOOTER).padding(horizontal = 6.dp).clickable(onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(label, maxLines = 1, style = TextStyle(color = tone, fontSize = 10.sp), modifier = GlanceModifier.defaultWeight())
        if (state.voiceEnabled) {
            Image(
                ImageProvider(R.drawable.ic_widget_mic),
                contentDescription = context.getString(R.string.widget_voice),
                colorFilter = ColorFilter.tint(Palette.icon),
                modifier = GlanceModifier.size(20.dp).clickable(actions.openVoice()),
            )
            Spacer(GlanceModifier.width(14.dp))
        }
        Image(
            ImageProvider(R.drawable.ic_widget_refresh),
            contentDescription = context.getString(R.string.widget_refresh),
            colorFilter = ColorFilter.tint(tone),
            modifier = GlanceModifier.size(16.dp),
        )
    }
}

@Composable
private fun Message(@DrawableRes icon: Int, text: String, onClick: Action) {
    Box(GlanceModifier.fillMaxSize().clickable(onClick), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconChip(icon, R.drawable.widget_chip, Palette.icon, chipSize = 40.dp, iconSize = 22.dp)
            Spacer(GlanceModifier.height(8.dp))
            Text(text, maxLines = 2, style = TextStyle(color = Palette.content, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
        }
    }
}

private fun iconRes(icon: TileIcon?): Int = when (icon) {
    TileIcon.LIGHT -> R.drawable.ic_widget_light
    TileIcon.SWITCH -> R.drawable.ic_widget_switch
    TileIcon.COVER -> R.drawable.ic_widget_cover
    TileIcon.CLIMATE -> R.drawable.ic_widget_climate
    TileIcon.TEMPERATURE -> R.drawable.ic_widget_temperature
    TileIcon.ALARM -> R.drawable.ic_widget_alarm
    TileIcon.BUTTON -> R.drawable.ic_widget_button
    TileIcon.SENSOR, null -> R.drawable.ic_widget_sensor
}
