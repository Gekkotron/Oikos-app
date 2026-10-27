package com.gekkotron.oikos.flows

/** Where the MQTT layer feeds retained flow payloads. May be called from any thread. */
interface FlowsSink {
    /** [payload] null or empty means the retained message was deleted. */
    fun onRetained(id: String, payload: String?)

    /** The MQTT link came up; starts the settle timer. */
    fun markLinkUp()

    /** Drops all flows and the settled state (a new MQTT source is attaching). */
    fun clear()
}
