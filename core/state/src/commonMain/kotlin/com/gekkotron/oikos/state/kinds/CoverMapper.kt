package com.gekkotron.oikos.state.kinds

import com.gekkotron.oikos.model.Command
import com.gekkotron.oikos.model.CoverState
import com.gekkotron.oikos.model.StateValue
import com.gekkotron.oikos.state.mqtt.MqttBindings
import com.gekkotron.oikos.state.template.asDouble
import com.gekkotron.oikos.state.template.TemplateResult
import com.gekkotron.oikos.state.template.Templates
import com.gekkotron.oikos.state.template.asPayloadString
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object CoverMapper : KindMapper {
    // Brackets escaped: Android's ICU regex engine rejects a bare ']'.
    private val READS_MOVING = Regex("""value_json(?:\.moving\b|\[\s*["']moving["']\s*\])""")

    override fun stateTopics(b: MqttBindings): Set<String> = setOfNotNull(b.str("state_topic"), b.str("position_topic"))

    override fun decode(b: MqttBindings, topic: String, payload: String, previous: StateValue): Decoded {
        var cover = previous as? StateValue.Cover ?: StateValue.Cover(CoverState.UNKNOWN)
        val parts = PartialDecode()
        if (topic == b.str("state_topic")) {
            parts.part(renderIncoming(b, "value_template", payload)) { v ->
                // Z2M/HA templates may pad rendered branches with whitespace; HA trims before matching.
                val state = movingState(b, payload) ?: stateFor(b, v.asPayloadString()?.trim()) ?: return@part false
                cover = cover.copy(state = state)
                true
            }
        }
        if (topic == b.str("position_topic")) {
            parts.part(renderIncoming(b, "position_template", payload)) { v ->
                val pct = v.asDouble()?.let { normalise(b, it) } ?: return@part false
                val derive = b.str("state_topic") == null || cover.state == CoverState.UNKNOWN
                cover = cover.copy(
                    position = pct,
                    state = if (derive) (if (pct == 0) CoverState.CLOSED else CoverState.OPEN) else cover.state,
                )
                true
            }
        }
        return parts.result(reconcile(cover))
    }

    /**
     * A known position is the finer signal: a cover at 50 % is open whatever its state says (some devices
     * keep reporting CLOSE after a partial open). Moving states are kept; without a position, the state is.
     */
    private fun reconcile(cover: StateValue.Cover): StateValue.Cover {
        val pct = cover.position ?: return cover
        if (cover.state == CoverState.OPENING || cover.state == CoverState.CLOSING) return cover
        return cover.copy(state = if (pct == 0) CoverState.CLOSED else CoverState.OPEN)
    }

    /**
     * Zigbee2MQTT's template tests "fully closed/open" before "moving", so a cover leaving an end stop
     * reads CLOSE/OPEN while its motor runs. When the template reads `moving`, a moving value wins.
     */
    private fun movingState(b: MqttBindings, payload: String): CoverState? {
        if (b.str("value_template")?.let(READS_MOVING::containsMatchIn) != true) return null
        val moving = (Templates.renderValue("{{ value_json.moving }}", payload) as? TemplateResult.Value)?.value
        return stateFor(b, moving.asPayloadString()?.trim())?.takeIf { it == CoverState.OPENING || it == CoverState.CLOSING }
    }

    private fun stateFor(b: MqttBindings, s: String?): CoverState? = when (s) {
        null -> null
        b.str("state_open", "open") -> CoverState.OPEN
        b.str("state_closed", "closed") -> CoverState.CLOSED
        b.str("state_opening", "opening") -> CoverState.OPENING
        b.str("state_closing", "closing") -> CoverState.CLOSING
        b.str("state_stopped", "stopped") -> CoverState.STOPPED
        else -> null
    }

    private fun bounds(b: MqttBindings) = (b.double("position_open") ?: 100.0) to (b.double("position_closed") ?: 0.0)

    private fun normalise(b: MqttBindings, raw: Double): Int? {
        val (open, closed) = bounds(b)
        if (open == closed) return null
        return ((raw - closed) / (open - closed) * 100).roundToInt().coerceIn(0, 100)
    }

    private fun denormalise(b: MqttBindings, pct: Int): Long {
        val (open, closed) = bounds(b)
        return (closed + pct * (open - closed) / 100.0).roundToLong()
    }

    private fun StateValue.isClosedLike() =
        this is StateValue.Cover && (state == CoverState.CLOSED || state == CoverState.CLOSING)

    override fun encode(b: MqttBindings, command: Command, previous: StateValue): Encoded = when (command) {
        Command.Open -> publish(b, "command_topic", null, b.str("payload_open", "OPEN"))
        Command.Close -> publish(b, "command_topic", null, b.str("payload_close", "CLOSE"))
        Command.Stop -> publish(b, "command_topic", null, b.str("payload_stop", "STOP"))
        Command.Toggle -> encode(b, if (previous.isClosedLike()) Command.Open else Command.Close, previous)
        is Command.SetPosition -> {
            val raw = denormalise(b, command.position)
            publish(b, "set_position_topic", "set_position_template", raw.toString(), mapOf("position" to raw))
        }
        else -> Encoded.NotSupported("cover cannot $command")
    }

    override fun settle(command: Command, predicted: StateValue): StateValue {
        val cover = predicted as? StateValue.Cover ?: return predicted
        return when {
            command is Command.SetPosition ->
                StateValue.Cover(if (command.position == 0) CoverState.CLOSED else CoverState.OPEN, command.position)
            cover.state == CoverState.OPENING -> StateValue.Cover(CoverState.OPEN, 100)
            cover.state == CoverState.CLOSING -> StateValue.Cover(CoverState.CLOSED, 0)
            else -> cover
        }
    }

    override fun predict(command: Command, previous: StateValue): StateValue? {
        val prev = previous as? StateValue.Cover ?: StateValue.Cover(CoverState.UNKNOWN)
        return when (command) {
            Command.Open -> prev.copy(state = CoverState.OPENING)
            Command.Close -> prev.copy(state = CoverState.CLOSING)
            Command.Stop -> prev.copy(state = CoverState.STOPPED)
            Command.Toggle -> prev.copy(state = if (previous.isClosedLike()) CoverState.OPENING else CoverState.CLOSING)
            is Command.SetPosition -> StateValue.Cover(
                if (command.position >= (prev.position ?: 0)) CoverState.OPENING else CoverState.CLOSING,
                command.position,
            )
            else -> null
        }
    }
}
