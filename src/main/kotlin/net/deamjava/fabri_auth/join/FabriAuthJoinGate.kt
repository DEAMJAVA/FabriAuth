package net.deamjava.fabri_auth.join

import net.deamjava.fabri_auth.auth.AuthState
import net.deamjava.fabri_auth.auth.AuthStateManager
import net.deamjava.fabri_auth.auth.JoinMode
import net.deamjava.fabri_auth.auth.SessionManager
import net.deamjava.fabri_auth.config.ConfigLoader
import net.deamjava.fabri_auth.integration.CarpetHook
import net.deamjava.fabri_auth.integration.FloodgateHook
import net.deamjava.fabri_auth.limbo.FakeJoinManager
import net.deamjava.fabri_auth.luckperms.LuckPermsHook
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.players.PlayerList
import java.net.InetSocketAddress

object FabriAuthJoinGate {

    fun decide(
        playerList: PlayerList,
        connection: Connection,
        player: ServerPlayer,
        cookie: CommonListenerCookie,
        server: MinecraftServer
    ): Boolean {
        val uuid = player.uuid
        val username = player.name.string
        val ip = (connection.remoteAddress as? InetSocketAddress)?.address?.hostAddress
        val cfg = ConfigLoader.config
        val usesAuthentication = server.usesAuthentication()

        if (AuthStateManager.isAuthenticated(uuid)) {
            if (AuthStateManager.getJoinMode(uuid) == JoinMode.UNSET) {
                AuthStateManager.setJoinMode(uuid, username, JoinMode.PREMIUM)
            }
            if (cfg.sessionEnabled && ip != null) SessionManager.createSession(uuid, ip)
            LuckPermsHook.invalidateContexts(player)
            return true
        }

        if (FloodgateHook.isBedrockPlayer(uuid)) {
            AuthStateManager.markAuthenticated(uuid, ip)
            return true
        }

        if (CarpetHook.isFakePlayer(player)) {
            AuthStateManager.markAuthenticated(uuid, ip)
            return true
        }

        if (usesAuthentication) {
            if (AuthStateManager.getJoinMode(uuid) == JoinMode.UNSET) {
                AuthStateManager.setJoinMode(uuid, username, JoinMode.PREMIUM)
            }
            AuthStateManager.markAuthenticated(uuid, ip)
            if (cfg.sessionEnabled && ip != null) SessionManager.createSession(uuid, ip)
            LuckPermsHook.invalidateContexts(player)
            return true
        }

        if (ip != null && SessionManager.hasValidSession(uuid, ip)) {
            AuthStateManager.markAuthenticated(uuid, ip)
            LuckPermsHook.invalidateContexts(player)
            return true
        }

        if (AuthStateManager.getJoinMode(uuid) == JoinMode.UNSET) {
            AuthStateManager.setJoinMode(uuid, username, JoinMode.OFFLINE)
        }
        AuthStateManager.setState(uuid, AuthState.UNAUTHENTICATED)
        FakeJoinManager.beginFakeSession(playerList, connection, player, cookie, server)
        LuckPermsHook.invalidateContexts(player)

        val msg = if (AuthStateManager.isRegistered(uuid)) cfg.messageNotLoggedIn else cfg.messageNotRegistered
        player.sendSystemMessage(Component.literal(msg))
        return false
    }
}