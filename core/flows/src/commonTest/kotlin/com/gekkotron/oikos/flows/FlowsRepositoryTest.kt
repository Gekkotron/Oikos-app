package com.gekkotron.oikos.flows

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FlowsRepositoryTest {

    private class FakeClient : FlowsClient {
        val calls = mutableListOf<Pair<String, Boolean>>()
        var gate: CompletableDeferred<Result<Unit>>? = null
        var result: Result<Unit> = Result.success(Unit)

        override suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> {
            calls += id to enabled
            return gate?.await() ?: result
        }
    }

    private fun payload(id: String, name: String = id, enabled: Boolean = true) =
        """{"id":"$id","name":"$name","enabled":$enabled}"""

    private fun TestScope.repo(client: FlowsClient = FakeClient()) =
        FlowsRepository(client, backgroundScope)

    @Test
    fun `retained message adds a flow`() = runTest {
        val r = repo()
        r.onRetained("a", payload("a", "Morning"))
        assertEquals(listOf(FlowDto("a", "Morning")), r.flows.value.flows)
    }

    @Test
    fun `flows are sorted by name case-insensitively then id`() = runTest {
        val r = repo()
        r.onRetained("z", payload("z", "alpha"))
        r.onRetained("b", payload("b", "Beta"))
        r.onRetained("a", payload("a", "Beta"))
        assertEquals(listOf("z", "a", "b"), r.flows.value.flows.map { it.id })
    }

    @Test
    fun `topic id wins over payload id`() = runTest {
        val r = repo()
        r.onRetained("topic", payload("other", "X"))
        assertEquals(listOf("topic"), r.flows.value.flows.map { it.id })
    }

    @Test
    fun `empty payload deletes the flow`() = runTest {
        val r = repo()
        r.onRetained("a", payload("a"))
        r.onRetained("a", "")
        assertTrue(r.flows.value.flows.isEmpty())
        r.onRetained("b", payload("b"))
        r.onRetained("b", null)
        assertTrue(r.flows.value.flows.isEmpty())
    }

    @Test
    fun `malformed payload is dropped and does not affect state`() = runTest {
        val r = repo()
        r.onRetained("a", payload("a"))
        val before = r.flows.value
        r.onRetained("a", "{not json")
        r.onRetained("b", """{"id":"b"}""")
        assertEquals(before, r.flows.value)
    }

    @Test
    fun `setEnabled optimistically updates then confirms on success`() = runTest {
        val client = FakeClient().apply { gate = CompletableDeferred() }
        val r = repo(client)
        r.onRetained("a", payload("a", enabled = true))
        val call = async { r.setEnabled("a", false) }
        runCurrent()
        assertFalse(r.flows.value.flows.single().enabled)
        assertEquals(setOf("a"), r.flows.value.inFlight)
        client.gate!!.complete(Result.success(Unit))
        assertTrue(call.await().isSuccess)
        assertFalse(r.flows.value.flows.single().enabled)
        assertTrue(r.flows.value.inFlight.isEmpty())
    }

    @Test
    fun `setEnabled reverts on client failure`() = runTest {
        val client = FakeClient().apply { result = Result.failure(RuntimeException("boom")) }
        val r = repo(client)
        r.onRetained("a", payload("a", enabled = true))
        assertTrue(r.setEnabled("a", false).isFailure)
        assertTrue(r.flows.value.flows.single().enabled)
        assertTrue(r.flows.value.inFlight.isEmpty())
    }

    @Test
    fun `in-flight toggle ignores further setEnabled on the same id`() = runTest {
        val client = FakeClient().apply { gate = CompletableDeferred() }
        val r = repo(client)
        r.onRetained("a", payload("a", enabled = true))
        val first = async { r.setEnabled("a", false) }
        runCurrent()
        assertTrue(r.setEnabled("a", true).isSuccess)
        assertEquals(listOf("a" to false), client.calls)
        assertFalse(r.flows.value.flows.single().enabled)
        client.gate!!.complete(Result.success(Unit))
        first.await()
    }

    @Test
    fun `retained echo during in-flight toggle wins once request completes`() = runTest {
        val client = FakeClient().apply { gate = CompletableDeferred() }
        val r = repo(client)
        r.onRetained("a", payload("a", enabled = true))
        r.onRetained("b", payload("b", enabled = true))
        val call = async { r.setEnabled("a", false) }
        runCurrent()
        // Echo with a different server truth does not overwrite the optimistic value yet.
        r.onRetained("a", payload("a", enabled = true))
        assertFalse(r.flows.value.flows.first { it.id == "a" }.enabled)
        client.gate!!.complete(Result.success(Unit))
        call.await()
        assertTrue(r.flows.value.flows.first { it.id == "a" }.enabled)

        // Failure with an echo: echo still wins over the pre-toggle value.
        val client2 = FakeClient().apply { gate = CompletableDeferred() }
        val r2 = repo(client2)
        r2.onRetained("a", payload("a", enabled = true))
        val call2 = async { r2.setEnabled("a", false) }
        runCurrent()
        r2.onRetained("a", payload("a", name = "Renamed", enabled = false))
        client2.gate!!.complete(Result.failure(RuntimeException("x")))
        call2.await()
        assertEquals(FlowDto("a", "Renamed", null, false), r2.flows.value.flows.single())

        // A delete during the flight removes the row at completion, not before.
        val client3 = FakeClient().apply { gate = CompletableDeferred() }
        val r3 = repo(client3)
        r3.onRetained("a", payload("a"))
        val call3 = async { r3.setEnabled("a", false) }
        runCurrent()
        r3.onRetained("a", "")
        assertEquals(1, r3.flows.value.flows.size)
        client3.gate!!.complete(Result.success(Unit))
        call3.await()
        assertTrue(r3.flows.value.flows.isEmpty())
    }

    @Test
    fun `isSettled becomes true after settleAfter when no messages arrive`() = runTest {
        val r = repo()
        assertFalse(r.flows.value.isSettled)
        r.markLinkUp()
        advanceTimeBy(1_999)
        runCurrent()
        assertFalse(r.flows.value.isSettled)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(r.flows.value.isSettled)
    }

    @Test
    fun `isSettled becomes true immediately on first retained message`() = runTest {
        val r = repo()
        r.markLinkUp()
        r.onRetained("a", payload("a"))
        assertTrue(r.flows.value.isSettled)
    }

    @Test
    fun `clear drops flows and settled state and cancels the timer`() = runTest {
        val r = repo()
        r.onRetained("a", payload("a"))
        r.clear()
        assertEquals(FlowsSnapshot(false, emptyList()), r.flows.value)
        r.markLinkUp()
        r.clear()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(r.flows.value.isSettled)
    }
}
