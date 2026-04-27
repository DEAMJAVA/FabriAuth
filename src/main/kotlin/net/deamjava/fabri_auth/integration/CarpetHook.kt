package net.deamjava.fabri_auth.integration

import net.deamjava.fabri_auth.config.ConfigLoader
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.level.ServerPlayer

object CarpetHook {

    private var available = false
    private var fakePlayerClass: Class<*>? = null

    fun tryInit() {
        if (!ConfigLoader.config.carpetIntegration) return
        if (!FabricLoader.getInstance().isModLoaded("carpet")) return
        try {
            fakePlayerClass = Class.forName("carpet.patches.EntityPlayerMPFake")
            available = true
            println("[FabriAuth] Carpet integration enabled.")
        } catch (e: ClassNotFoundException) {
            println("[FabriAuth] Carpet loaded but EntityPlayerMPFake not found: ${e.message}")
        }
    }

    fun isFakePlayer(player: ServerPlayer): Boolean {
        if (!available) return false
        return fakePlayerClass?.isInstance(player) == true
    }

    // Keep username overload for the login mixin (pre-join, no ServerPlayer yet)
    fun isFakePlayer(username: String): Boolean {
        if (!available) return false
        // During handleHello we have no ServerPlayer instance yet,
        // so fall back to checking the live player list via the server —
        // but that's not available here. This path is only used in the
        // ServerLoginPacketListenerImpl mixin where we only have a username.
        // At that stage Carpet fake players are already online (they never
        // go through the login pipeline), so this will never actually be
        // called for a fake player. Return false safely.
        return false
    }
}