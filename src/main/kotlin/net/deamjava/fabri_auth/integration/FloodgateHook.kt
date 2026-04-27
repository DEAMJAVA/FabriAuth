package net.deamjava.fabri_auth.integration

import net.deamjava.fabri_auth.config.ConfigLoader
import net.fabricmc.loader.api.FabricLoader
import java.util.UUID

object FloodgateHook {

    private var available = false
    private var apiClass: Class<*>? = null
    private var instanceGetter: java.lang.reflect.Method? = null
    private var isFloodgatePlayerMethod: java.lang.reflect.Method? = null

    fun tryInit() {
        if (!ConfigLoader.config.floodgateIntegration) return
        if (!FabricLoader.getInstance().isModLoaded("floodgate")) return
        try {
            apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi")
            instanceGetter = apiClass!!.getMethod("getInstance")
            isFloodgatePlayerMethod = apiClass!!.getMethod("isFloodgatePlayer", UUID::class.java)
            available = true
            println("[FabriAuth] Floodgate integration enabled.")
        } catch (e: Exception) {
            println("[FabriAuth] Floodgate found but API init failed: ${e.message}")
        }
    }

    fun isBedrockPlayer(uuid: UUID): Boolean {
        if (!available) return false
        return try {
            val instance = instanceGetter!!.invoke(null)
            isFloodgatePlayerMethod!!.invoke(instance, uuid) as Boolean
        } catch (e: Exception) {
            false
        }
    }
}