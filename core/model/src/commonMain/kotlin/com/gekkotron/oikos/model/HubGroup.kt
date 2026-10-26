package com.gekkotron.oikos.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Device identifier Oikos-OS stamps on every group entity it publishes. */
const val HUB_GROUP_DEVICE_ID = "oikos_group"

/** A hub-owned group as the app sees it (hub cover groups spec §1). */
data class HubGroup(
    val id: String,
    val kind: Kind,
    val name: String,
    val members: List<String>,
    val hideMembers: Boolean = true,
) {
    companion object {
        private val KINDS = Kind.entries.associateBy { it.name.lowercase() }

        /**
         * Parses the retained JSON at `oikos/group/<id>/definition`. Returns null when the payload
         * is malformed or missing a required field — the hub's schema may grow, unknown keys are
         * ignored.
         */
        fun parse(id: String, payload: String): HubGroup? {
            if (payload.isBlank()) return null
            val obj = try {
                OikosJson.parseToJsonElement(payload) as? JsonObject ?: return null
            } catch (_: SerializationException) {
                return null
            }
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return null
            val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull?.let { KINDS[it.lowercase()] } ?: return null
            val members = (obj["members"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return null
            val hide = (obj["hideMembers"] as? JsonPrimitive)?.booleanOrNull ?: true
            return HubGroup(id = id, kind = kind, name = name, members = members, hideMembers = hide)
        }
    }
}
