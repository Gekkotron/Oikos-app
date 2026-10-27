package com.gekkotron.oikos.feature.automations.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gekkotron.oikos.designsystem.OikosBackground
import com.gekkotron.oikos.designsystem.OikosTheme
import com.gekkotron.oikos.designsystem.ThemeChoice
import com.gekkotron.oikos.feature.automations.R
import com.gekkotron.oikos.feature.automations.presentation.ALL_KEY
import com.gekkotron.oikos.feature.automations.presentation.AreaLabel
import com.gekkotron.oikos.feature.automations.presentation.AutomationChip
import com.gekkotron.oikos.feature.automations.presentation.AutomationRow
import com.gekkotron.oikos.feature.automations.presentation.AutomationsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h914dp-xxhdpi")
class AutomationsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val selected = mutableListOf<String>()
    private val toggled = mutableListOf<Pair<String, Boolean>>()
    private var toastShown = 0

    private val chips = listOf(AutomationChip.All, AutomationChip.Room("living", "Living room"), AutomationChip.Unassigned)

    private val rows = listOf(
        AutomationRow("a", "Evening lights", AreaLabel.Named("Living room"), enabled = true, toggleEnabled = true),
        AutomationRow("b", "Close shutters", AreaLabel.Unassigned, enabled = false, toggleEnabled = true),
        AutomationRow("c", "Vacation mode", null, enabled = false, toggleEnabled = true),
    )

    private fun state(
        isLoading: Boolean = false,
        isOffline: Boolean = false,
        rows: List<AutomationRow> = this.rows,
        toast: Int? = null,
    ) = AutomationsUiState(isLoading, isOffline, chips, ALL_KEY, rows, toast)

    private fun show(state: AutomationsUiState, dark: Boolean = true) = compose.setContent {
        OikosTheme(if (dark) ThemeChoice.NIGHT_GLASS else ThemeChoice.WARM_MINIMAL) {
            OikosBackground {
                AutomationsScreen(
                    state,
                    onChipSelected = { selected += it },
                    onToggle = { id, on -> toggled += id to on },
                    onToastShown = { toastShown++ },
                )
            }
        }
    }

    private fun text(res: Int) = compose.activity.getString(res)

    @Test
    fun `placeholders while loading and no empty copy`() {
        show(state(isLoading = true, rows = emptyList()))
        // The placeholder block is one merged node announced as "loading", so its tiles only show in the unmerged tree.
        assertTrue(compose.onAllNodesWithTag("automations-placeholder", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithContentDescription(text(R.string.automations_loading)).assertExists()
        compose.onNodeWithText(text(R.string.automations_empty_title)).assertDoesNotExist()
    }

    @Test
    fun `empty copy when settled with no rows`() {
        show(state(rows = emptyList()))
        compose.onNodeWithText(text(R.string.automations_empty_title)).assertExists()
        compose.onNodeWithText(text(R.string.automations_empty_body)).assertExists()
        assertTrue(compose.onAllNodesWithTag("automations-placeholder").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `offline with no rows shows the banner but no empty copy`() {
        show(state(isOffline = true, rows = emptyList()))
        compose.onNodeWithText(text(R.string.automations_offline_banner)).assertExists()
        compose.onNodeWithText(text(R.string.automations_empty_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.automations_empty_body)).assertDoesNotExist()
    }

    @Test
    fun `renders title, chips and rows with area labels`() {
        show(state())
        compose.onNodeWithText(text(R.string.automations_title)).assertExists()
        compose.onNodeWithText(text(R.string.automations_chip_all)).assertExists()
        compose.onNodeWithText("Evening lights").assertExists()
        compose.onNodeWithText("Close shutters").assertExists()
        compose.onNodeWithText("Vacation mode").assertExists()
        // "Living room" shows as both a chip and the first row's area; Unassigned as a chip and the second row's area.
        assertEquals(2, compose.onAllNodes(androidx.compose.ui.test.hasText("Living room")).fetchSemanticsNodes().size)
        assertEquals(2, compose.onAllNodes(androidx.compose.ui.test.hasText(text(R.string.automations_chip_unassigned))).fetchSemanticsNodes().size)
        compose.onNodeWithText(text(R.string.automations_empty_title)).assertDoesNotExist()
    }

    @Test
    fun `selecting a chip reports its key`() {
        show(state())
        compose.onNodeWithText(text(R.string.automations_chip_all)).performClick()
        compose.onAllNodes(androidx.compose.ui.test.hasText("Living room") and androidx.compose.ui.test.hasClickAction())[0].performClick()
        assertEquals(listOf(ALL_KEY, "living"), selected)
    }

    @Test
    fun `offline banner shows and switches are disabled`() {
        show(state(isOffline = true, rows = rows.map { it.copy(toggleEnabled = false) }))
        compose.onNodeWithText(text(R.string.automations_offline_banner)).assertExists()
        for (i in rows.indices) compose.onAllNodes(isToggleable())[i].assertIsNotEnabled()
    }

    @Test
    fun `online has no banner and enabled switches`() {
        show(state())
        compose.onNodeWithText(text(R.string.automations_offline_banner)).assertDoesNotExist()
        compose.onAllNodes(isToggleable())[0].assertIsEnabled()
    }

    @Test
    fun `row switch is named by the flow name`() {
        show(state())
        compose.onNode(isToggleable() and androidx.compose.ui.test.hasText("Evening lights")).assertExists()
    }

    @Test
    fun `clicking a switch requests the opposite state`() {
        show(state())
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onAllNodes(isToggleable())[1].performClick()
        assertEquals(listOf("a" to false, "b" to true), toggled)
    }

    @Test
    fun `toast is shown once then acknowledged`() {
        show(state(toast = R.string.automations_toggle_failed))
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.automations_toggle_failed)).assertExists()
        assertEquals(1, toastShown)
    }

    @Test
    @Config(qualifiers = "en-w411dp-h914dp-xxhdpi")
    fun `screenshot loaded light`() {
        show(state(), dark = false)
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(300)
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/screenshots/automations-loaded-light.png").also { it.parentFile!!.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val seen = HashSet<Int>()
        for (x in 0 until bitmap.width step 7) for (y in 0 until bitmap.height step 7) seen += bitmap.getPixel(x, y)
        assertTrue(seen.size > 1, "blank render")
    }
}
