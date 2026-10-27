package com.gekkotron.oikos.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [flows] is sorted by name (case-insensitive) then id. [isSettled] flips once the retained
 * burst is over, so the UI can tell "no flows" from "not loaded yet". [inFlight] holds the ids
 * with a toggle request still running.
 */
data class FlowsSnapshot(
    val isSettled: Boolean,
    val flows: List<FlowDto>,
    val inFlight: Set<String> = emptySet(),
    /**
     * Version of the repository state this was built from. It takes part in equality on purpose:
     * the repository refuses stale publishes by version, and two snapshots with equal content but
     * different versions must not collapse into one stored value.
     */
    val version: Long = 0L,
)

/**
 * Flows as the hub publishes them (retained MQTT), plus an optimistic enable toggle.
 *
 * All state lives in one immutable [State] behind a [MutableStateFlow], so [onRetained]
 * (MQTT thread) and [setEnabled] (coroutine) update it atomically.
 */
class FlowsRepository(
    private val client: FlowsClient,
    private val scope: CoroutineScope,
    private val settleAfter: Duration = 2.seconds,
    private val log: (String) -> Unit = {},
) : FlowsSink {

    private class State(
        /** Server truth; rows with a toggle in flight still hold their pre-toggle value. */
        val server: Map<String, FlowDto> = emptyMap(),
        val settled: Boolean = false,
        /** id -> optimistic enabled value of the running toggle. */
        val inFlight: Map<String, Boolean> = emptyMap(),
        /** Retained echoes that arrived during a flight (null = deleted), applied at completion. */
        val echoes: Map<String, FlowDto?> = emptyMap(),
        /** Bumped by every transform so a late publish can't overwrite a newer snapshot. */
        val version: Long = 0L,
    ) {
        fun copy(
            server: Map<String, FlowDto> = this.server,
            settled: Boolean = this.settled,
            inFlight: Map<String, Boolean> = this.inFlight,
            echoes: Map<String, FlowDto?> = this.echoes,
        ) = State(server, settled, inFlight, echoes, version)

        fun withVersion(v: Long) = State(server, settled, inFlight, echoes, v)

        fun toSnapshot() = FlowsSnapshot(
            isSettled = settled,
            flows = server.values
                .map { f -> inFlight[f.id]?.let { f.copy(enabled = it) } ?: f }
                .sortedWith(compareBy<FlowDto> { it.name.lowercase() }.thenBy { it.id }),
            inFlight = inFlight.keys,
            version = version,
        )
    }

    private val state = MutableStateFlow(State())
    private val settleTimer = MutableStateFlow<Job?>(null)

    private val _flows = MutableStateFlow(state.value.toSnapshot())
    val flows: StateFlow<FlowsSnapshot> = _flows.asStateFlow()

    private fun update(transform: (State) -> State) {
        publish(state.updateAndGet { s -> transform(s).withVersion(s.version + 1) })
    }

    /** Publishes [s]'s snapshot unless a newer one is already out, so racing writers can't regress it. */
    private fun publish(s: State) {
        val snap = s.toSnapshot()
        _flows.update { cur -> if (snap.version > cur.version) snap else cur }
    }

    internal fun currentStateSnapshot() = state.value.toSnapshot()

    override fun onRetained(id: String, payload: String?) {
        val dto: FlowDto? = if (payload.isNullOrEmpty()) {
            null
        } else {
            try {
                flowsJson.decodeFromString<FlowDto>(payload).copy(id = id) // topic is authoritative
            } catch (e: Exception) {
                log("dropping malformed payload for '$id': ${e.message}")
                return
            }
        }
        update { s ->
            val settled = true // any retained message ends the burst
            if (id in s.inFlight) {
                s.copy(echoes = s.echoes + (id to dto), settled = settled)
            } else {
                s.copy(server = if (dto == null) s.server - id else s.server + (id to dto), settled = settled)
            }
        }
        cancelTimer()
    }

    override fun markLinkUp() {
        if (state.value.settled) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(settleAfter)
            update { it.copy(settled = true) }
        }
        settleTimer.getAndUpdate { job }?.cancel()
        job.start()
    }

    override fun clear() {
        cancelTimer()
        update { s -> s.copy(server = emptyMap(), settled = false, echoes = emptyMap()) }
    }

    private fun cancelTimer() {
        settleTimer.getAndUpdate { null }?.cancel()
    }

    /**
     * Shows [enabled] immediately, then asks the hub. A second call for the same id while the
     * first is running is a no-op success. See [State.echoes] for how a retained echo racing
     * the request is resolved.
     */
    suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> {
        while (true) {
            val cur = state.value
            if (id in cur.inFlight) return Result.success(Unit)
            val next = cur.copy(inFlight = cur.inFlight + (id to enabled), echoes = cur.echoes - id)
                .withVersion(cur.version + 1)
            if (state.compareAndSet(cur, next)) {
                publish(next)
                break
            }
        }

        // The request and its reconciliation run in the repository's own scope: cancelling the caller
        // (e.g. its ViewModel being cleared) only stops waiting, it must not abort the request.
        return scope.async {
            val result = try {
                client.setEnabled(id, enabled)
            } catch (e: kotlinx.coroutines.CancellationException) {
                finish(id, enabled, succeeded = false)
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            finish(id, enabled, succeeded = result.isSuccess)
            result
        }.await()
    }

    private fun finish(id: String, enabled: Boolean, succeeded: Boolean) = update { s ->
        val inFlight = s.inFlight - id
        val echoes = s.echoes - id
        val server = when {
            id in s.echoes -> s.echoes[id]?.let { s.server + (id to it) } ?: (s.server - id)
            succeeded -> s.server[id]?.let { s.server + (id to it.copy(enabled = enabled)) } ?: s.server
            else -> s.server
        }
        s.copy(server = server, inFlight = inFlight, echoes = echoes)
    }
}
