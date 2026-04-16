// src/main/kotlin/net/deamjava/fabri_auth/FabriAuth.kt
package net.deamjava.fabri_auth

import net.deamjava.fabri_auth.auth.AuthStateManager
import net.deamjava.fabri_auth.command.LoginCommand
import net.deamjava.fabri_auth.config.ConfigLoader
import net.deamjava.fabri_auth.integration.CarpetHook
import net.deamjava.fabri_auth.integration.FloodgateHook
import net.deamjava.fabri_auth.integration.VanishHook
import net.deamjava.fabri_auth.luckperms.LuckPermsHook
import net.deamjava.fabri_auth.session.SessionManager
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents

object FabriAuth : ModInitializer {

	const val MOD_ID = "fabri-auth"

	override fun onInitialize() {
		println("[FabriAuth] Initializing...")

		// Load config first
		ConfigLoader.load()

		// Load persisted player data
		AuthStateManager.load()

		// Init integrations
		LuckPermsHook.tryInit()
		FloodgateHook.tryInit()
		CarpetHook.tryInit()
		VanishHook.tryInit()

		// Register commands
		CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
			LoginCommand.register(dispatcher)
		}

		// Player join: set initial state, check session, hide if needed
		ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
			val player = handler.player
			val uuid = player.uuid
			val ip = (player.connection.remoteAddress
					as? java.net.InetSocketAddress)?.address?.hostAddress

			// Floodgate / Bedrock players skip auth
			if (FloodgateHook.isBedrockPlayer(uuid)) {
				AuthStateManager.markAuthenticated(uuid, ip)
				return@register
			}

			// Carpet fake players skip auth
			if (CarpetHook.isFakePlayer(player.name.string)) {
				AuthStateManager.markAuthenticated(uuid, ip)
				return@register
			}

			// Check for valid session
			if (ip != null && SessionManager.hasValidSession(uuid, ip)) {
				AuthStateManager.markAuthenticated(uuid, ip)
				LuckPermsHook.invalidateContexts(player)
				player.sendSystemMessage(
					net.minecraft.network.chat.Component.literal(
						ConfigLoader.config.messageSessionRestored
					)
				)
				return@register
			}

			// Needs authentication
			AuthStateManager.setState(uuid, net.deamjava.fabri_auth.auth.AuthState.UNAUTHENTICATED)
			VanishHook.hidePlayer(player)
			LuckPermsHook.invalidateContexts(player)

			// Send prompt
			val msg = if (AuthStateManager.isRegistered(uuid))
				ConfigLoader.config.messageNotLoggedIn
			else
				ConfigLoader.config.messageNotRegistered
			player.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg))
		}

		// Player leave: clean up state
		ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
			val uuid = handler.player.uuid
			AuthStateManager.onPlayerLeave(uuid)
		}

		// Periodic session pruning (every 5 minutes = 6000 ticks)
		var tickCounter = 0
		ServerTickEvents.END_SERVER_TICK.register { _ ->
			tickCounter++
			if (tickCounter >= 6000) {
				tickCounter = 0
				SessionManager.pruneExpired()
			}
		}

		// Save on server stop
		ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
			AuthStateManager.save()
			println("[FabriAuth] Data saved on server stop.")
		}

		println("[FabriAuth] Ready.")
	}
}