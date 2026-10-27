package com.gekkotron.oikos.designsystem.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser

/** One stroke of a glyph in SVG path syntax on the 24-unit grid; a surface is filled as well as outlined. */
private class Part(val d: String, val surface: Boolean)

private fun line(d: String) = Part(d, surface = false)
private fun area(d: String) = Part(d, surface = true)

/** Static duotone glyphs (spec §2.5). The accent, when given, fills the surfaces (a lit bulb, a live plug). */
enum class OikosGlyph(private val parts: List<Part>) {
    BULB(listOf(area("M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z"), line("M9.5 18.5h5M10.5 21h3"))),
    PLUG(listOf(area("M7 9h10v3a5 5 0 0 1-10 0z"), line("M9.5 4v5M14.5 4v5M12 17v4"))),
    SNOWFLAKE(listOf(line("M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9M9.5 4.5 12 7l2.5-2.5M9.5 19.5 12 17l2.5 2.5"))),
    THERMOMETER(listOf(area("M10 14.5V5a2 2 0 0 1 4 0v9.5a4 4 0 1 1-4 0z"), line("M12 9v7"))),
    DROP(listOf(area("M12 3.5c3.5 4.2 6 7.6 6 10.5a6 6 0 0 1-12 0c0-2.9 2.5-6.3 6-10.5z"))),
    BOLT(listOf(area("M13 3 5.5 13.5H12L11 21l7.5-10.5H12z"))),
    TREND(listOf(line("M4 19h16M5 15l4-4 3 3 6-6M15 8h3v3"))),
    WALKER(listOf(area("M14 5.5a1.75 1.75 0 1 1-3.5 0 1.75 1.75 0 0 1 3.5 0z"), line("M12 9l-2.5 4.5 3 2.5V21M12 9l3 3.5 2.5.5M9.5 13.5 7 21M10.5 10.5 7.5 12"))),
    FLAME(listOf(area("M12 3c3 3.5 6 6.4 6 10.5a6 6 0 0 1-12 0c0-2.4 1.2-4.3 3-5.8.2 1.8 1 3 2.3 3.6C10.6 8.5 10.9 5.6 12 3z"))),
    SHIELD(listOf(area("M12 3 19 6v5.5c0 4.4-3 8-7 9.5-4-1.5-7-5.1-7-9.5V6z"))),
    SLIDER(listOf(line("M4 8h16M4 16h16"), area("M10 8a2 2 0 1 1-4 0 2 2 0 0 1 4 0zM18 16a2 2 0 1 1-4 0 2 2 0 0 1 4 0z"))),
    LIST(listOf(line("M9 7h11M9 12h11M9 17h11M5 7h1M5 12h1M5 17h1"))),
    PLAY(listOf(area("M8 5.5v13l10.5-6.5z"))),
    SUN(listOf(area("M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0z"), line("M12 2.5V5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"))),
    HOUSE(listOf(area("M4 11 12 4l8 7v9H4z"), line("M10 20v-5h4v5"))),
    PYLON(listOf(line("M12 3 7 21M12 3l5 18M5 7h14M8.5 12h7M9 7l6 5M15 7l-6 5M7.6 18h8.8"))),
    BATTERY(listOf(area("M5 7h12a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z"), line("M20.5 10.5v3M8 12h6"))),
    SEARCH(listOf(area("M15.5 10.5a5 5 0 1 1-10 0 5 5 0 0 1 10 0z"), line("M14.3 14.3 20 20"))),
    MIC(listOf(area("M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z"), line("M6 11a6 6 0 0 0 12 0M12 17v4M9 21h6"))),
    CLOSE(listOf(line("M6 6l12 12M18 6 6 18"))),
    DOT(listOf(area("M17 12a5 5 0 1 1-10 0 5 5 0 0 1 10 0z"))),
    WATER_HEATER(listOf(area("M8 4h8a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z"), line("M10 4V3M14 4V3M12 9c1.5 1.8 2 2.6 2 3.5a2 2 0 0 1-4 0c0-.9.5-1.7 2-3.5zM9 20v1M15 20v1"))),
    HEAT_PUMP(listOf(area("M4 6h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z"), line("M13 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0zM9 9v6M6 12h6M15.5 9H18M15.5 12H18M15.5 15H18M6 18v2M18 18v2"))),
    OVEN(listOf(area("M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z"), line("M4 9h16M7.5 6.5h.5M11.5 6.5h.5M15.5 6.5h.5M7 12h10v5H7z"))),
    FRIDGE(listOf(area("M7 3h10a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z"), line("M6 10h12M9 6v2M9 13v3"))),
    WASHER(listOf(area("M5 3h14a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z"), line("M4 8h16M7 5.5h3M16 5.5h1M16.5 14.5a4.5 4.5 0 1 1-9 0 4.5 4.5 0 0 1 9 0zM9.5 15c.9-.8 1.6-.8 2.5 0s1.6.8 2.5 0"))),
    DRYER(listOf(area("M5 3h14a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z"), line("M4 8h16M7 5.5h.5M10 5.5h.5M17 5.5a.75.75 0 1 1-1.5 0 .75.75 0 0 1 1.5 0zM16.5 14.5a4.5 4.5 0 1 1-9 0 4.5 4.5 0 0 1 9 0zM10.5 12.5c.6.6.6 1.2 0 1.8s-.6 1.2 0 1.8M13.5 12.5c.6.6.6 1.2 0 1.8s-.6 1.2 0 1.8"))),
    DISHWASHER(listOf(area("M5 3h14a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z"), line("M4 7h16M8 5h.5M11 5h.5M9 10h6M7 14h10M7 18h10M10 14v4M14 14v4"))),
    GEAR(listOf(area("M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"), line("M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8M17 12a5 5 0 1 1-10 0 5 5 0 0 1 10 0z"))),
    VACUUM(listOf(area("M20 12a8 8 0 1 1-16 0 8 8 0 0 1 16 0z"), line("M7.2 8.2a6.5 6.5 0 0 1 9.6 0M13.5 13a1.5 1.5 0 1 1-3 0 1.5 1.5 0 0 1 3 0z"))),
    DRAG_HANDLE(listOf(line("M5 9h14M5 15h14"))),
    FAN(listOf(area("M12,12q0,-2.275 1.6125,-3.8875T17.5,6.5q2.275,0 3.8875,1.6125T23,12L12,12ZM6.5,17.5q-2.275,0 -3.8875,-1.6125T1,12h11q0,2.275 -1.6125,3.8875T6.5,17.5ZM12,12q-2.275,0 -3.8875,-1.6125T6.5,6.5q0,-2.275 1.6125,-3.8875T12,1v11ZM12,23v-11q2.275,0 3.8875,1.6125T17.5,17.5q0,2.275 -1.6125,3.8875T12,23Z"))),
    STAR(listOf(area("M12 2L14.3 8.8 21.5 8.9 15.8 13.2 17.9 20.1 12 16 6.1 20.1 8.2 13.2 2.5 8.9 9.7 8.8z"))),
    AUTOMATION(listOf(line("M4 20 14 10"), area("M14 10l2.5 2.5-9.8 9.8L4.2 19.8z"), line("M17.5 3v4M15.5 5h4M20 9.5v3M18.5 11h3"))),
    ;

    /** Parsed once per glyph. */
    internal val paths: List<Pair<Path, Boolean>> by lazy { parts.map { PathParser().parsePathString(it.d).toPath() to it.surface } }
}

@Composable
fun StaticIcon(glyph: OikosGlyph, accent: Color? = null, modifier: Modifier = Modifier) = IconCanvas(modifier) { neutral ->
    glyph.paths.forEach { (path, isSurface) ->
        if (isSurface) surface(path, accent ?: neutral) else outline(path, neutral)
    }
}
