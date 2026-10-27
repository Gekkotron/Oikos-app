package com.gekkotron.oikos.feature.automations.presentation

import com.gekkotron.oikos.feature.automations.R
import com.gekkotron.oikos.flows.FlowsClient
import com.gekkotron.oikos.flows.FlowsRepository
import com.gekkotron.oikos.model.Area
import com.gekkotron.oikos.model.UNASSIGNED_AREA
import com.gekkotron.oikos.transport.LinkState
import com.gekkotron.oikos.transport.TransportMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeFlowsClient : FlowsClient {
    val calls = mutableListOf<Pair<String, Boolean>>()
    var gate: CompletableDeferred<Result<Unit>>? = null
    var result: Result<Unit> = Result.success(Unit)

    override suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> {
        calls += id to enabled
        return gate?.await() ?: result
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val client = FakeFlowsClient()
    private val areas = MutableStateFlow(listOf(Area("living", "Living room"), Area("kitchen", "Kitchen")))
    private val link = MutableStateFlow<LinkState>(LinkState.Connected(TransportMode.DEMO))

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(): Pair<AutomationsViewModel, FlowsRepository> {
        val repo = FlowsRepository(client, backgroundScope)
        val vm = AutomationsViewModel(repo, areas, link, dispatcher)
        backgroundScope.launch { vm.state.collect {} }
        return vm to repo
    }

    private fun FlowsRepository.flow(id: String, name: String, area: String? = null, enabled: Boolean = true) {
        val a = area?.let { ""","area":"$it"""" } ?: ""
        onRetained(id, """{"id":"$id","name":"$name","enabled":$enabled$a}""")
    }

    @Test
    fun `loading state before settle`() = runTest(dispatcher) {
        val (vm, repo) = vm()
        repo.markLinkUp()
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test
    fun `empty state after settle with no flows`() = runTest(dispatcher) {
        val (vm, repo) = vm()
        repo.markLinkUp()
        testScheduler.advanceTimeBy(2_001)
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.rows.isEmpty())
        assertEquals(listOf<AutomationChip>(AutomationChip.All), vm.state.value.chips.take(1))
    }

    @Test
    fun `rows ordered by area position when All chip selected`() = runTest(dispatcher) {
        val (vm, repo) = vm()
        repo.flow("a", "Zeta", "living")
        repo.flow("b", "alpha", "kitchen")
        repo.flow("c", "Beta", "living")
        repo.flow("d", "Orphan")
        val s = vm.state.value
        assertEquals(listOf("c", "a", "b", "d"), s.rows.map { it.id })
        assertEquals(AreaLabel.Named("Living room"), s.rows[0].area)
        assertEquals(AreaLabel.Unassigned, s.rows[3].area)
    }

    @Test
    fun `rows for a selected area hide the area label`() = runTest(dispatcher) {
        val (vm, repo) = vm()
        repo.flow("a", "A", "living")
        repo.flow("b", "B", "kitchen")
        vm.onChipSelected("living")
        assertEquals(listOf("a"), vm.state.value.rows.map { it.id })
        assertNull(vm.state.value.rows.single().area)
    }

    @Test
    fun `flow with unknown area id falls into Unassigned bucket`() = runTest(dispatcher) {
        val (vm, repo) = vm()
        repo.flow("a", "A", "garage")
        assertTrue(AutomationChip.Unassigned in vm.state.value.chips)
        vm.onChipSelected(UNASSIGNED_AREA)
        assertEquals(listOf("a"), vm.state.value.rows.map { it.id })
        assertNull(vm.state.value.rows.single().area)
    }

    @Test
    fun `flow with area=null falls into Unassigned bucket`() = runTest(dispatcher) {
        val (vm, repo) = vm()
        repo.flow("a", "A")
        repo.flow("b", "B", "kitchen")
        repo.flow("c", "C", "living")
        assertEquals(
            listOf(AutomationChip.All, AutomationChip.Room("living", "Living room"), AutomationChip.Room("kitchen", "Kitchen"), AutomationChip.Unassigned),
            vm.state.value.chips,
        )
    }

    @Test
    fun `a room without flows gets no chip`() = runTest(dispatcher) {
        val (vm, repo) = vm()
        repo.flow("a", "A", "living")
        assertEquals(listOf("__all__", "living"), vm.state.value.chips.map { it.key })
    }

    @Test
    fun `no Unassigned chip when every flow has a room`() = runTest(dispatcher) {
        val (vm, repo) = vm()
        repo.flow("a", "A", "living")
        assertFalse(AutomationChip.Unassigned in vm.state.value.chips)
    }

    @Test
    fun `chip list excludes the source's own Unassigned area`() = runTest(dispatcher) {
        areas.value = areas.value + Area(UNASSIGNED_AREA, "Unassigned")
        val (vm, repo) = vm()
        repo.flow("a", "A", "living")
        repo.flow("b", "B", "kitchen")
        assertEquals(listOf("__all__", "living", "kitchen"), vm.state.value.chips.map { it.key })
    }

    @Test
    fun `vanished selected chip falls back to All`() = runTest(dispatcher) {
        val (vm, repo) = vm()
        repo.flow("a", "A", "living")
        repo.flow("b", "B", "kitchen")
        vm.onChipSelected("kitchen")
        assertEquals("kitchen", vm.state.value.selectedChipKey)
        repo.onRetained("b", null)
        assertEquals(ALL_KEY, vm.state.value.selectedChipKey)
        assertEquals(listOf("a"), vm.state.value.rows.map { it.id })
    }

    @Test
    fun `offline state disables toggles and surfaces the offline banner`() = runTest(dispatcher) {
        val (vm, repo) = vm()
        repo.flow("a", "A", "living")
        link.value = LinkState.Reconnecting(1)
        val s = vm.state.value
        assertTrue(s.isOffline)
        assertFalse(s.rows.single().toggleEnabled)
        vm.onToggle("a", false)
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun `offline never shows loading`() = runTest(dispatcher) {
        link.value = LinkState.Reconnecting(1)
        val (vm, _) = vm()
        assertTrue(vm.state.value.isOffline)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `toggle flips optimistically and clears when the repo succeeds`() = runTest(dispatcher) {
        val (vm, repo) = vm()
        repo.flow("a", "A", "living")
        client.gate = CompletableDeferred()
        vm.onToggle("a", false)
        assertFalse(vm.state.value.rows.single().enabled)
        assertFalse(vm.state.value.rows.single().toggleEnabled)
        client.gate!!.complete(Result.success(Unit))
        assertFalse(vm.state.value.rows.single().enabled)
        assertTrue(vm.state.value.rows.single().toggleEnabled)
        assertNull(vm.state.value.toast)
    }

    @Test
    fun `toggle reverts and sets toast when the repo fails`() = runTest(dispatcher) {
        val (vm, repo) = vm()
        repo.flow("a", "A", "living")
        client.result = Result.failure(RuntimeException("boom"))
        vm.onToggle("a", false)
        assertTrue(vm.state.value.rows.single().enabled)
        assertEquals(R.string.automations_toggle_failed, vm.state.value.toast)
        vm.onToastShown()
        assertNull(vm.state.value.toast)
    }

    @Test
    fun `rapid toggle presses on the same row are ignored while in-flight`() = runTest(dispatcher) {
        val (vm, repo) = vm()
        repo.flow("a", "A", "living")
        client.gate = CompletableDeferred()
        vm.onToggle("a", false)
        vm.onToggle("a", true)
        assertEquals(listOf("a" to false), client.calls)
        client.gate!!.complete(Result.success(Unit))
    }

    @Test
    fun `retained echo during in-flight toggle resolves to server truth`() = runTest(dispatcher) {
        val (vm, repo) = vm()
        repo.flow("a", "A", "living")
        client.gate = CompletableDeferred()
        vm.onToggle("a", false)
        repo.flow("a", "A", "living", enabled = true)
        client.gate!!.complete(Result.success(Unit))
        assertTrue(vm.state.value.rows.single().enabled)
    }
}
