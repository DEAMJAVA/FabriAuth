package net.deamjava.fabri_auth

import net.deamjava.fabri_auth.auth.AuthState
import net.deamjava.fabri_auth.auth.AuthStateManager
import net.deamjava.fabri_auth.auth.JoinMode
import net.deamjava.fabri_auth.auth.PremiumManager
import net.deamjava.fabri_auth.command.LoginCommand
import net.deamjava.fabri_auth.config.ConfigLoader
import net.deamjava.fabri_auth.integration.CarpetHook
import net.deamjava.fabri_auth.integration.FloodgateHook
import net.deamjava.fabri_auth.integration.VanishHook
import net.deamjava.fabri_auth.limbo.LimboManager
import net.deamjava.fabri_auth.luckperms.LuckPermsHook
import net.deamjava.fabri_auth.auth.SessionManager
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.Component

object FabriAuth : ModInitializer {

	const val MOD_ID = "fabri-auth"

	override fun onInitialize() {
		println("[FabriAuth] Initializing...")

		ConfigLoader.load()
		AuthStateManager.load()

		LuckPermsHook.tryInit()
		FloodgateHook.tryInit()
		CarpetHook.tryInit()
		VanishHook.tryInit()

		CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
			LoginCommand.register(dispatcher)
		}

		ServerLifecycleEvents.SERVER_STARTED.register { server ->
			LimboManager.load(server)
		}

		ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
			val player = handler.player
			val uuid = player.uuid
			val ip = (player.connection.remoteAddress as? java.net.InetSocketAddress)
				?.address?.hostAddress
			val username = player.name.string
			val server = player.level().server
			val usesAuthentication = server.usesAuthentication()
			val cfg = ConfigLoader.config

			if (AuthStateManager.isAuthenticated(uuid)) {
				if (AuthStateManager.getJoinMode(uuid) == JoinMode.UNSET) {
					AuthStateManager.setJoinMode(uuid, username, JoinMode.PREMIUM)
				}
				if (cfg.sessionEnabled && ip != null) SessionManager.createSession(uuid, ip)
				LuckPermsHook.invalidateContexts(player)
				return@register
			}

			if (FloodgateHook.isBedrockPlayer(uuid)) {
				AuthStateManager.markAuthenticated(uuid, ip)
				return@register
			}

			if (CarpetHook.isFakePlayer(username)) {
				AuthStateManager.markAuthenticated(uuid, ip)
				return@register
			}

			if (usesAuthentication) {
				if (AuthStateManager.getJoinMode(uuid) == JoinMode.UNSET) {
					AuthStateManager.setJoinMode(uuid, username, JoinMode.PREMIUM)
				}
				AuthStateManager.markAuthenticated(uuid, ip)
				if (cfg.sessionEnabled && ip != null) SessionManager.createSession(uuid, ip)
				LuckPermsHook.invalidateContexts(player)
				return@register
			}

			if (LimboManager.hasSavedState(uuid)) {
				AuthStateManager.setState(uuid, AuthState.UNAUTHENTICATED)
				LimboManager.sendToLimbo(player)
				VanishHook.hidePlayer(player)
				LuckPermsHook.invalidateContexts(player)
				val msg = if (AuthStateManager.isRegistered(uuid))
					cfg.messageNotLoggedIn
				else
					cfg.messageNotRegistered
				player.sendSystemMessage(Component.literal(msg))
				return@register
			}

			if (cfg.autoPremiumLogin && usesAuthentication) {
				if (AuthStateManager.isPremiumAutoAuthCandidate(uuid, username)) {
					AuthStateManager.markAuthenticated(uuid, ip)
					LuckPermsHook.invalidateContexts(player)
					player.sendSystemMessage(Component.literal("§aWelcome back, $username!"))
					return@register
				}

				val isPremium = AuthStateManager.isPremium(uuid)
				if (isPremium) {
					AuthStateManager.markAuthenticated(uuid, ip)
					if (ip != null) SessionManager.createSession(uuid, ip)
					LuckPermsHook.invalidateContexts(player)
					VanishHook.showPlayer(player)
					player.sendSystemMessage(Component.literal("§aWelcome back, $username! (Premium)"))
					return@register
				}

				AuthStateManager.setState(uuid, AuthState.UNAUTHENTICATED)
				LimboManager.sendToLimbo(player)
				VanishHook.hidePlayer(player)
				LuckPermsHook.invalidateContexts(player)

				java.util.concurrent.CompletableFuture.supplyAsync {
					PremiumManager.fetchMojangUuid(username)
				}.thenAcceptAsync({ mojangResult ->
					if (!player.isAlive) return@thenAcceptAsync

					if (mojangResult != null) {
						if (AuthStateManager.getJoinMode(uuid) == JoinMode.UNSET) {
							AuthStateManager.setJoinMode(mojangResult, username, JoinMode.PREMIUM)
						}
						AuthStateManager.setPremiumMode(uuid, username, enable = true, mojangUuid = mojangResult)
						AuthStateManager.markAuthenticated(uuid, ip)
						LimboManager.returnFromLimbo(player)
						VanishHook.showPlayer(player)
						LuckPermsHook.invalidateContexts(player)
						player.sendSystemMessage(Component.literal("§aWelcome, $username! (Premium account verified)"))
					} else {
						if (AuthStateManager.getJoinMode(uuid) == JoinMode.UNSET) {
							AuthStateManager.setJoinMode(uuid, username, JoinMode.OFFLINE)
						}
						player.sendSystemMessage(
							Component.literal(
								if (AuthStateManager.isRegistered(uuid)) cfg.messageNotLoggedIn
								else cfg.messageNotRegistered
							)
						)
					}
				}, server)
				return@register
			}

			if (!cfg.autoPremiumLogin && usesAuthentication && AuthStateManager.isPremium(uuid)) {
				AuthStateManager.markAuthenticated(uuid, ip)
				if (ip != null) SessionManager.createSession(uuid, ip)
				LuckPermsHook.invalidateContexts(player)
				player.sendSystemMessage(Component.literal("§aWelcome back, $username! (Premium)"))
				return@register
			}

			if (ip != null && SessionManager.hasValidSession(uuid, ip)) {
				AuthStateManager.markAuthenticated(uuid, ip)
				LuckPermsHook.invalidateContexts(player)
				player.sendSystemMessage(Component.literal(cfg.messageSessionRestored))
				return@register
			}

			if (AuthStateManager.getJoinMode(uuid) == JoinMode.UNSET) {
				AuthStateManager.setJoinMode(uuid, username, JoinMode.OFFLINE)
			}
			AuthStateManager.setState(uuid, AuthState.UNAUTHENTICATED)
			LimboManager.sendToLimbo(player)
			VanishHook.hidePlayer(player)
			LuckPermsHook.invalidateContexts(player)

			val msg = if (AuthStateManager.isRegistered(uuid))
				cfg.messageNotLoggedIn
			else
				cfg.messageNotRegistered
			player.sendSystemMessage(Component.literal(msg))
		}

		ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
			val player = handler.player
			val uuid = player.uuid
			AuthStateManager.onPlayerLeave(uuid)
			LimboManager.onPlayerDisconnect(uuid, player.level().server)
		}

		var tickCounter = 0
		ServerTickEvents.END_SERVER_TICK.register { server ->
			LimboManager.tickPendingTeleports(server)

			tickCounter++
			if (tickCounter >= 6000) {
				tickCounter = 0
				SessionManager.pruneExpired()
			}
		}


		ServerLifecycleEvents.SERVER_STOPPING.register { server ->
			AuthStateManager.save()
			LimboManager.save(server)
			println("[FabriAuth] Data saved on server stop.")
		}

		println("[FabriAuth] Ready.")
	}
}