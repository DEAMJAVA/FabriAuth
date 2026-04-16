// src/main/kotlin/net/deamjava/fabri_auth/auth/AuthStateManager.kt
package net.deamjava.fabri_auth.auth

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.deamjava.fabri_auth.config.ConfigLoader
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PlayerAuthData(
    val uuid: UUID,
    val username: String,
    /** BCrypt hash or "RAW:<pw>" in debug mode */
    val passwordHash: String?,
    val lastIp: String?,
    val registeredAt: Long = System.currentTimeMillis()
)

enum class AuthState {
    /** Player joined, session check pending */
    PENDING,
    /** Waiting for /login or /register */
    UNAUTHENTICATED,
    /** Fully authenticated */
    AUTHENTICATED
}

object AuthStateManager {

    private val GSON = GsonBuilder().setPrettyPrinting().create()

    /** In-memory runtime auth states per UUID */
    private val runtimeStates = ConcurrentHashMap<UUID, AuthState>()

    /** Persisted player records (UUID -> PlayerAuthData) */
    private val playerData = ConcurrentHashMap<UUID, PlayerAuthData>()

    private val dataFile: File by lazy {
        FabricLoader.getInstance()
            .gameDir
            .resolve("fabri-auth-data.json")
            .toFile()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun load() {
        if (!dataFile.exists()) return
        try {
            val type = object : TypeToken<Map<String, PlayerAuthData>>() {}.type
            val raw: Map<String, PlayerAuthData> =
                GSON.fromJson(dataFile.readText(), type) ?: emptyMap()
            raw.forEach { (k, v) -> playerData[UUID.fromString(k)] = v }
        } catch (e: Exception) {
            println("[FabriAuth] Failed to load player data: ${e.message}")
        }
    }

    fun save() {
        try {
            dataFile.parentFile?.mkdirs()
            val serializable = playerData.mapKeys { it.key.toString() }
            dataFile.writeText(GSON.toJson(serializable))
        } catch (e: Exception) {
            println("[FabriAuth] Failed to save player data: ${e.message}")
        }
    }

    // ── Runtime state ──────────────────────────────────────────────────────

    fun getState(uuid: UUID): AuthState =
        runtimeStates.getOrDefault(uuid, AuthState.PENDING)

    fun setState(uuid: UUID, state: AuthState) {
        runtimeStates[uuid] = state
    }

    fun isAuthenticated(uuid: UUID): Boolean =
        runtimeStates[uuid] == AuthState.AUTHENTICATED

    fun onPlayerLeave(uuid: UUID) {
        runtimeStates.remove(uuid)
    }

    // ── Registration / password ────────────────────────────────────────────

    fun isRegistered(uuid: UUID): Boolean =
        playerData[uuid]?.passwordHash != null

    fun register(uuid: UUID, username: String, password: String, ip: String?): Boolean {
        if (!PasswordManager.isValidPassword(password)) return false
        val hash = PasswordManager.hash(password)
        playerData[uuid] = PlayerAuthData(
            uuid = uuid,
            username = username,
            passwordHash = hash,
            lastIp = ip
        )
        save()
        return true
    }

    fun checkPassword(uuid: UUID, password: String): Boolean {
        val storedHash = playerData[uuid]?.passwordHash ?: return false
        // Also handle global password if enabled
        if (ConfigLoader.config.globalPasswordEnabled) {
            val globalHash = ConfigLoader.config.globalPasswordHash
            if (globalHash.isNotBlank() && PasswordManager.verify(password, globalHash)) {
                return true
            }
        }
        return PasswordManager.verify(password, storedHash)
    }

    fun updateLastIp(uuid: UUID, ip: String) {
        playerData[uuid]?.let {
            playerData[uuid] = it.copy(lastIp = ip)
        }
    }

    fun getLastIp(uuid: UUID): String? = playerData[uuid]?.lastIp

    // ── Convenience ────────────────────────────────────────────────────────

    /** Called when a player authenticates (login or session restore). */
    fun markAuthenticated(uuid: UUID, ip: String?) {
        setState(uuid, AuthState.AUTHENTICATED)
        if (ip != null) updateLastIp(uuid, ip)
        save()
    }
}