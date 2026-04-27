// src/main/kotlin/net/deamjava/fabri_auth/integration/VanishHook.kt
package net.deamjava.fabri_auth.integration

import net.deamjava.fabri_auth.config.ConfigLoader
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import java.util.UUID

object VanishHook {

    private var available = false
    private val hiddenPlayers = mutableSetOf<UUID>()

    fun tryInit() {
        if (!ConfigLoader.config.vanishIntegration) return
        available = true
        println("[FabriAuth] Vanish integration enabled (built-in).")
    }

    fun hidePlayer(target: ServerPlayer) {
        if (!available) return
        hiddenPlayers.add(target.uuid)
        val server = target.level().server
        val removePacket = ClientboundPlayerInfoRemovePacket(listOf(target.uuid))
        server.playerList.players.forEach { viewer ->
            if (viewer.uuid != target.uuid) {
                viewer.connection.send(removePacket)
            }
        }
    }

    fun showPlayer(target: ServerPlayer) {
        if (!available) return
        hiddenPlayers.remove(target.uuid)
        val server = target.level().server
        // Send ADD_PLAYER info update to all online players
        val addPacket = ClientboundPlayerInfoUpdatePacket(
            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
            target
        )
        server.playerList.players.forEach { viewer ->
            if (viewer.uuid != target.uuid) {
                viewer.connection.send(addPacket)
            }
        }
    }

    fun isHidden(uuid: UUID): Boolean = uuid in hiddenPlayers
}