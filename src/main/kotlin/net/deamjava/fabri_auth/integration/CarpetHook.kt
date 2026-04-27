package net.deamjava.fabri_auth.integration

import net.deamjava.fabri_auth.config.ConfigLoader
import net.fabricmc.loader.api.FabricLoader


object CarpetHook {

    private var available = false

    fun tryInit() {
        if (!ConfigLoader.config.carpetIntegration) return
        available = FabricLoader.getInstance().isModLoaded("carpet")
        if (available) println("[FabriAuth] Carpet integration enabled.")
    }

    fun isFakePlayer(username: String): Boolean {
        if (!available) return false
        return carpetFakePlayers.contains(username)
    }

    private val carpetFakePlayers = mutableSetOf<String>()

    fun registerFakePlayer(username: String) {
        carpetFakePlayers.add(username)
    }

    fun unregisterFakePlayer(username: String) {
        carpetFakePlayers.remove(username)
    }
}