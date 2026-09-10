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
import net.deamjava.fabri_auth.luckperms.LuckPermsHook
import net.deamjava.fabri_auth.auth.SessionManager
import net.deamjava.fabri_auth.limbo.FakeJoinManager
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

		}

		ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
			val player = handler.player
			val uuid = player.uuid
			AuthStateManager.onPlayerLeave(uuid)
			LoginCommand.clearPendingMigration(uuid)
		}

		var tickCounter = 0
		ServerTickEvents.END_SERVER_TICK.register { server ->
			FakeJoinManager.tickAll()

			tickCounter++
			if (tickCounter >= 6000) {
				tickCounter = 0
				SessionManager.pruneExpired()
			}
		}


		ServerLifecycleEvents.SERVER_STOPPING.register { server ->
			AuthStateManager.save()
			println("[FabriAuth] Data saved on server stop.")
		}

		println("[FabriAuth] Ready.")
	}
}