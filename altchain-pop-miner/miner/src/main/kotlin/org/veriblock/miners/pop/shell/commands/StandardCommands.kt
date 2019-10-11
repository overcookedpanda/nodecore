// VeriBlock Blockchain Project
// Copyright 2017-2018 VeriBlock, Inc
// Copyright 2018-2019 Xenios SEZC
// All rights reserved.
// https://www.veriblock.org
// Distributed under the MIT software license, see the accompanying
// file LICENSE or http://www.opensource.org/licenses/mit-license.php.

package org.veriblock.miners.pop.shell.commands

import org.veriblock.shell.CommandParameter
import org.veriblock.shell.CommandParameterType
import org.veriblock.shell.Shell
import org.veriblock.shell.command
import org.veriblock.shell.core.failure
import org.veriblock.shell.core.success

fun Shell.standardCommands() {

    command(
        name = "Clear Screen",
        form = "clear",
        description = "Clears the terminal screen"
    ) {
        clear()
        success()
    }

    command(
        name = "Quit",
        form = "quit|exit",
        description = "Quit the application"
    ) {
        quit()
        success()
    }

    command(
        name = "Help",
        form = "help",
        description = "Returns this help message",
        parameters = listOf(
            CommandParameter("command", CommandParameterType.STRING, false)
        )
    ) {
        val command: String? = getParameter("command")

        val result = if (command == null) {
            printInfo("Commands:")
            for ((alias, definition) in Shell.getCommands()) {
                printInfo("    $alias ${definition.parameters.format()}")
            }
            success()
        } else {
            val def = Shell.getCommands()[command]
            if (def != null) {
                printInfo("Command: ${def.name}")
                printInfo("${def.form} ${def.parameters.format()}")
                printInfo(def.description)
                success()
            } else {
                failure {
                    addMessage(
                        "V004",
                        "Unknown protocol command",
                        String.format("The command '%s' is not supported", command),
                        true
                    )
                }
            }
        }

        result
    }
}

private fun List<CommandParameter>.format(): String {
    val stringBuilder = StringBuilder()
    if (isNotEmpty()) {
        for (param in this) {
            if (param.required) {
                stringBuilder.append(String.format("<%s> ", param.name))
            } else {
                stringBuilder.append(String.format("[%s] ", param.name))
            }
        }
    }
    return stringBuilder.toString()
}