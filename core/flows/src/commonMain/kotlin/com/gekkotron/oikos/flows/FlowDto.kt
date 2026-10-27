package com.gekkotron.oikos.flows

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The slice of an Oikos-OS flow (automation) the UI reads; everything else is ignored. */
@Serializable
data class FlowDto(
    val id: String,
    val name: String,
    val area: String? = null,
    val enabled: Boolean = true,
)

val flowsJson = Json { ignoreUnknownKeys = true; isLenient = false }
