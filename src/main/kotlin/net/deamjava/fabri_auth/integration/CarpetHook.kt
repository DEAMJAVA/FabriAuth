// src/main/kotlin/net/deamjava/fabri_auth/integration/CarpetHook.kt
package net.deamjava.fabri_auth.integration

import net.deamjava.fabri_auth.config.ConfigLoader
import net.fabricmc.loader.api.FabricLoader

/**
 * Carpet integration: Carpet bots / fake players should bypass auth entirely.
 * We detect them by checking if the username starts with the Carpet bot prefix.
 */
object CarpetHook {

    private var available = false

    fun tryInit() {
        if (!ConfigLoader.config.carpetIntegration) return
        available = FabricLoader.getInstance().isModLoaded("carpet")
        if (available) println("[FabriAuth] Carpet integration enabled.")
    }

    /**
     * Returns true if the given username appears to be a Carpet fake player.
     * Carpet spawns fake players; by convention their names often include a
     * trailing/leading bot indicator, but we just flag all Carpet-spawned UUIDs.
     * A more robust approach would hook CarpetServer.addPlayer.
     */
    fun isFakePlayer(username: String): Boolean {
        if (!available) return false
        // Carpet fake players have a name that matches the /player <name> spawn arg.
        // We rely on the username being flagged externally via the mixin/event.
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