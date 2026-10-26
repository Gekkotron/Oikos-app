package com.gekkotron.oikos.feature.home.presentation

import com.gekkotron.oikos.model.Command

/** Which members of a group a command goes to (room groups spec §5). */
object GroupCommands {
    fun targets(group: CardUi, command: Command): List<String> {
        // Hub cover groups spec §2.4: the hub owns fanout — one command to the group entity.
        if (group.isHubGroup) return listOf(group.id)
        return group.members
            .filter { it.available }
            .filter { m ->
                when (command) {
                    is Command.SetPosition -> m.capabilities.position
                    is Command.TurnOn -> command.brightness == null || m.capabilities.brightness
                    else -> true
                }
            }
            .map { it.id }
    }

    /** Absolute, so Retry repeats the intent instead of flipping it back. */
    fun toggle(group: CardUi): Command = if (group.on) Command.TurnOff else Command.TurnOn()
}
