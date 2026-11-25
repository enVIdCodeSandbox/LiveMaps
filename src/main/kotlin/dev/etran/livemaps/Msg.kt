package dev.etran.livemaps

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.CommandSender

object Msg {
    private val mm = MiniMessage.miniMessage()
    private const val PREFIX = "<dark_gray>[<aqua>LiveMaps</aqua>]</dark_gray> "

    fun esc(s: String): String = mm.escapeTags(s)
    fun parse(s: String): Component = mm.deserialize(s)

    fun info(to: CommandSender, s: String) = to.sendMessage(parse("$PREFIX<gray>$s"))
    fun ok(to: CommandSender, s: String) = to.sendMessage(parse("$PREFIX<green>$s"))
    fun warn(to: CommandSender, s: String) = to.sendMessage(parse("$PREFIX<yellow>$s"))
    fun err(to: CommandSender, s: String) = to.sendMessage(parse("$PREFIX<red>$s"))
}
