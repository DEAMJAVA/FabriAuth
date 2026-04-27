package net.deamjava.fabri_auth.luckperms

import net.deamjava.fabri_auth.auth.AuthStateManager
import net.deamjava.fabri_auth.config.ConfigLoader
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.context.ContextCalculator
import net.luckperms.api.context.ContextConsumer
import net.luckperms.api.context.ContextSet
import net.luckperms.api.context.ImmutableContextSet
import net.minecraft.server.level.ServerPlayer

object LuckPermsHook {

    private var luckPerms: LuckPerms? = null
    private var available = false

    fun tryInit() {
        if (!ConfigLoader.config.luckpermsIntegration) return
        try {
            luckPerms = LuckPermsProvider.get()
            registerContextCalculator()
            available = true
            println("[FabriAuth] LuckPerms integration enabled.")
        } catch (e: IllegalStateException) {
            println("[FabriAuth] LuckPerms not found, integration disabled.")
        } catch (e: NoClassDefFoundError) {
            println("[FabriAuth] LuckPerms API not on classpath, integration disabled.")
        }
    }

    private fun registerContextCalculator() {
        val lp = luckPerms ?: return
        lp.contextManager.registerCalculator(object : ContextCalculator<ServerPlayer> {
            override fun calculate(target: ServerPlayer, consumer: ContextConsumer) {
                val authed = AuthStateManager.isAuthenticated(target.uuid)
                consumer.accept("authed", if (authed) "true" else "false")
            }

            override fun estimatePotentialContexts(): ContextSet {
                return ImmutableContextSet.builder()
                    .add("authed", "true")
                    .add("authed", "false")
                    .build()
            }
        })
    }

    fun invalidateContexts(player: ServerPlayer) {
        if (!available) return
        try {
            luckPerms?.contextManager?.signalContextUpdate(player)
        } catch (e: Exception) {
        }
    }
}