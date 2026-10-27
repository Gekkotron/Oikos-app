package com.gekkotron.oikos.flows

import com.gekkotron.oikos.transport.HttpPostResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.net.URLEncoder

/** The hub answered a flow toggle with a non-2xx [status]. */
class FlowsHttpError(val status: Int) : Exception("the hub answered HTTP $status")

@Serializable
private class EnabledBody(val enabled: Boolean)

/** Over the hub's `POST /api/v1/flows/<id>/enabled` route. */
class HttpFlowsClient(
    private val baseUrl: suspend () -> String?,
    private val post: suspend (url: String, json: String) -> HttpPostResult,
) : FlowsClient {
    override suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit> {
        val base = baseUrl() ?: return Result.failure(IllegalStateException("no hub url"))
        val url = "${base.trimEnd('/')}/api/v1/flows/${encode(id)}/enabled"
        val body = flowsJson.encodeToString(EnabledBody(enabled))
        return try {
            when (val r = post(url, body)) {
                is HttpPostResult.Response ->
                    if (r.status in 200..299) Result.success(Unit) else Result.failure(FlowsHttpError(r.status))
                HttpPostResult.NotAllowed -> Result.failure(IllegalStateException("the gateway refused the hub"))
                is HttpPostResult.Unavailable -> Result.failure(IllegalStateException("hub unavailable: ${r.reason}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun encode(v: String) = URLEncoder.encode(v, Charsets.UTF_8).replace("+", "%20")
}
