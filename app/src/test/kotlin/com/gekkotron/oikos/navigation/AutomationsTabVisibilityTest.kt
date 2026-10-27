package com.gekkotron.oikos.navigation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.designsystem.icons.OikosGlyph
import com.gekkotron.oikos.designsystem.icons.StaticIcon
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Automations tab spec: a fourth tab after Security, hidden under the demo (no hub to toggle flows on). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutomationsTabVisibilityTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the Automations tab shows when the profile allows it`() {
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { MainTabBar(selected = HomeKey, onSelect = {}, showAutomations = true) } }
        compose.onNodeWithText("Automations").assertIsDisplayed()
    }

    @Test
    fun `the Automations tab is hidden under the demo`() {
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { MainTabBar(selected = HomeKey, onSelect = {}, showAutomations = false) } }
        compose.onNodeWithText("Security").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTextCount("Automations"))
    }

    @Test
    fun `tapping Automations selects it`() {
        val picked = mutableListOf<NavKey>()
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { MainTabBar(selected = HomeKey, onSelect = { picked += it }) } }
        compose.onNodeWithText("Automations").performClick()
        assertEquals(listOf<NavKey>(AutomationsKey), picked)
    }

    @Test
    fun `tab bar order is Home, Energy, Security, Automations`() {
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { MainTabBar(selected = HomeKey, onSelect = {}) } }
        val lefts = listOf("Home", "Energy", "Security", "Automations")
            .map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.left }
        assertEquals(lefts.sorted(), lefts)
        assertEquals(4, lefts.toSet().size)
    }

    @Test
    fun `Automations is a main tab that replaces the other side tabs`() {
        assertTrue(showsTabBar(AutomationsKey))
        val stack = mutableListOf<NavKey>(HomeKey)
        selectTab(stack, AutomationsKey)
        assertEquals(listOf<NavKey>(HomeKey, AutomationsKey), stack)
        selectTab(stack, EnergyKey)
        assertEquals(listOf<NavKey>(HomeKey, EnergyKey), stack)
        selectTab(stack, HomeKey)
        assertFalse(AutomationsKey in stack)
    }

    /** Renders the new glyph beside HOUSE, BOLT and SHIELD to build/screenshots for a look. */
    @Test
    fun `the automation glyph renders beside the other tab glyphs`() {
        compose.setContent {
            OikosTheme(ThemeChoice.NIGHT_GLASS) {
                OikosBackground {
                    Row {
                        listOf(OikosGlyph.HOUSE, OikosGlyph.BOLT, OikosGlyph.SHIELD, OikosGlyph.AUTOMATION).forEach {
                            StaticIcon(it, modifier = Modifier.size(64.dp))
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(300)
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/screenshots/automation-glyph.png").also { it.parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The longest label ("Automatisations") must fit its slot at 360dp in French; screenshot to build/screenshots. */
    @Test
    @Config(qualifiers = "fr-w360dp-h640dp-xxhdpi")
    fun `the French tab bar fits four labels at 360dp`() {
        compose.setContent { OikosTheme(ThemeChoice.NIGHT_GLASS) { OikosBackground { MainTabBar(selected = HomeKey, onSelect = {}, showAutomations = true) } } }
        val label = compose.onNodeWithText("Automatisations", useUnmergedTree = true).fetchSemanticsNode()
        val other = compose.onNodeWithText("Sécurité", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(other.boundsInRoot.height, label.boundsInRoot.height, 0.5f)
        assertTrue(label.boundsInRoot.right <= 360f * compose.activity.resources.displayMetrics.density)
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(300)
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/screenshots/tab-bar-fr-360.png").also { it.parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(text: String) =
        onAllNodesWithText(text).fetchSemanticsNodes().size
}
