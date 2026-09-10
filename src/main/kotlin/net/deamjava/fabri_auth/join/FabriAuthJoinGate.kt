package net.deamjava.fabri_auth.join

import net.deamjava.fabri_auth.auth.AuthState
import net.deamjava.fabri_auth.auth.AuthStateManager
import net.deamjava.fabri_auth.auth.JoinMode
import net.deamjava.fabri_auth.auth.PremiumManager
import net.deamjava.fabri_auth.auth.SessionManager
import net.deamjava.fabri_auth.config.Config
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
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Runs at the exact point vanilla would otherwise finish placing the player
 * in the world (PlayerList#placeNewPlayer, HEAD). This replaces the old
 * ServerPlayConnectionEvents.JOIN handler in FabriAuth.kt — same decision
 * tree, just running BEFORE the real join instead of after it.
 *
 * @return true  -> let the real placeNewPlayer body run now.
 *         false -> a fake limbo session has been started (or the connection
 *                  was rejected); the real join must not run yet.
 */
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

        if (cfg.autoPremiumLogin) {
            if (AuthStateManager.isPremiumAutoAuthCandidate(uuid, username)) {
                AuthStateManager.markAuthenticated(uuid, ip)
                LuckPermsHook.invalidateContexts(player)
                return true
            }

            if (AuthStateManager.isPremium(uuid)) {
                AuthStateManager.markAuthenticated(uuid, ip)
                if (ip != null) SessionManager.createSession(uuid, ip)
                LuckPermsHook.invalidateContexts(player)
                return true
            }

            beginFakeSessionAndVerifyPremium(playerList, connection, player, cookie, server, uuid, username, ip, cfg)
            return false
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

    private fun beginFakeSessionAndVerifyPremium(
        playerList: PlayerList,
        connection: Connection,
        player: ServerPlayer,
        cookie: CommonListenerCookie,
        server: MinecraftServer,
        uuid: UUID,
        username: String,
        ip: String?,
        cfg: Config
    ) {
        AuthStateManager.setState(uuid, AuthState.UNAUTHENTICATED)
        FakeJoinManager.beginFakeSession(playerList, connection, player, cookie, server)

        val msg = if (AuthStateManager.isRegistered(uuid)) cfg.messageNotLoggedIn else cfg.messageNotRegistered
        player.sendSystemMessage(Component.literal(msg))

        CompletableFuture.supplyAsync {
            PremiumManager.fetchMojangUuid(username)
        }.thenAcceptAsync({ mojangResult ->
            // Player may have already logged in manually, or disconnected,
            // while this lookup was in flight.
            if (!FakeJoinManager.isFakeSession(uuid)) return@thenAcceptAsync

            if (mojangResult != null) {
                if (AuthStateManager.getJoinMode(uuid) == JoinMode.UNSET) {
                    AuthStateManager.setJoinMode(mojangResult, username, JoinMode.PREMIUM)
                }
                AuthStateManager.setPremiumMode(uuid, username, enable = true, mojangUuid = mojangResult)
                AuthStateManager.markAuthenticated(uuid, ip)
                LuckPermsHook.invalidateContexts(player)
                FakeJoinManager.promote(uuid)
                player.sendSystemMessage(Component.literal("§aWelcome, $username! (Premium account verified)"))
            } else {
                if (AuthStateManager.getJoinMode(uuid) == JoinMode.UNSET) {
                    AuthStateManager.setJoinMode(uuid, username, JoinMode.OFFLINE)
                }
                // Stays in fake limbo; player proceeds via normal /login or /register.
            }
        }, server)
    }
}