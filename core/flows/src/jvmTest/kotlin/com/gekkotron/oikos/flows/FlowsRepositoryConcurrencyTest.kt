package com.gekkotron.oikos.flows

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class FlowsRepositoryConcurrencyTest {
    private object OkClient : FlowsClient {
        override suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> {
            yield()
            return Result.success(Unit)
        }
    }

    @Test
    fun `published snapshot always equals final state after concurrent writers`() = runBlocking {
        repeat(50) {
            val repo = FlowsRepository(OkClient, this)
            val ids = (0 until 8).map { "f$it" }
            ids.forEach { repo.onRetained(it, """{"id":"$it","name":"$it"}""") }
            val jobs = (0 until 4).map { t ->
                async(Dispatchers.Default) {
                    repeat(300) { i ->
                        val id = ids[(i + t) % ids.size]
                        if (t % 2 == 0) {
                            repo.onRetained(id, """{"id":"$id","name":"$id","enabled":${i % 2 == 0}}""")
                        } else {
                            repo.setEnabled(id, i % 2 == 0)
                        }
                    }
                }
            }
            jobs.awaitAll()
            assertEquals(repo.currentStateSnapshot(), repo.flows.value)
        }
    }
}
