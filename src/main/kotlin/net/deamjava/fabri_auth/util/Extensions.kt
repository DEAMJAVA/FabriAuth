package net.deamjava.fabri_auth.util

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

fun ServerPlayer.sendMessage(text: String) {
    this.sendSystemMessage(Component.literal(text))
}

fun String.colorize(): Component = Component.literal(this)