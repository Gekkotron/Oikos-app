package com.gekkotron.oikos.flows

/** The only write the app makes to the hub's flows: flip one on or off. */
interface FlowsClient {
    suspend fun setEnabled(id: String, enabled: Boolean): Result<Unit>
}
