package net.deamjava.fabri_auth.auth

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.deamjava.fabri_auth.config.ConfigLoader
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class JoinMode {
    UNSET,
    OFFLINE,
    PREMIUM
}

data class PlayerAuthData(
    val uuid: UUID,
    val username: String,
    val passwordHash: String?,
    val lastIp: String?,
    val registeredAt: Long = System.currentTimeMillis(),
    val premiumMode: Boolean = false,
    val mojangUuid: String? = null,
    val joinMode: JoinMode = JoinMode.UNSET
)

enum class AuthState {
    PENDING,
    UNAUTHENTICATED,
    AUTHENTICATED
}

object AuthStateManager {

    private val GSON = GsonBuilder().setPrettyPrinting().create()
    private val runtimeStates = ConcurrentHashMap<UUID, AuthState>()
    private val playerData = ConcurrentHashMap<UUID, PlayerAuthData>()

    private val dataFile: File by lazy {
        FabricLoader.getInstance()
            .gameDir
            .resolve("fabri-auth-data.json")
            .toFile()
    }


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


    fun getJoinMode(uuid: UUID): JoinMode =
        playerData[uuid]?.joinMode ?: JoinMode.UNSET

    fun getJoinModeByUsername(username: String): JoinMode =
        playerData.values
            .firstOrNull { it.username.equals(username, ignoreCase = true) }
            ?.joinMode ?: JoinMode.UNSET

    fun setJoinMode(uuid: UUID, username: String, mode: JoinMode) {
        val existing = playerData[uuid]
        if (existing != null) {
            playerData[uuid] = existing.copy(
                joinMode = mode,
                premiumMode = mode == JoinMode.PREMIUM,
                mojangUuid = if (mode == JoinMode.OFFLINE) null else existing.mojangUuid
            )
        } else {
            playerData[uuid] = PlayerAuthData(
                uuid = uuid,
                username = username,
                passwordHash = null,
                lastIp = null,
                joinMode = mode,
                premiumMode = mode == JoinMode.PREMIUM
            )
        }
        save()
    }

    fun setJoinModeByUsername(username: String, mode: JoinMode): Boolean {
        val entry = playerData.entries
            .firstOrNull { it.value.username.equals(username, ignoreCase = true) }
            ?: return false
        playerData[entry.key] = entry.value.copy(
            joinMode = mode,
            premiumMode = mode == JoinMode.PREMIUM,
            mojangUuid = if (mode == JoinMode.OFFLINE) null else entry.value.mojangUuid
        )
        save()
        return true
    }

    fun resolveIdentity(username: String, isMojangVerified: Boolean): IdentityDecision {
        val mode = getJoinModeByUsername(username)
        return when (mode) {
            JoinMode.UNSET -> IdentityDecision.ALLOW_AND_RECORD
            JoinMode.OFFLINE -> {
                IdentityDecision.FORCE_OFFLINE
            }
            JoinMode.PREMIUM -> {
                if (!isMojangVerified) IdentityDecision.KICK_NEEDS_PREMIUM
                else IdentityDecision.ALLOW_PREMIUM
            }
        }
    }


    fun isRegistered(uuid: UUID): Boolean =
        playerData[uuid]?.passwordHash != null

    fun register(uuid: UUID, username: String, password: String, ip: String?): Boolean {
        if (!PasswordManager.isValidPassword(password)) return false
        val hash = PasswordManager.hash(password)
        val existing = playerData[uuid]
        playerData[uuid] = (existing ?: PlayerAuthData(
            uuid = uuid,
            username = username,
            passwordHash = null,
            lastIp = ip
        )).copy(passwordHash = hash, lastIp = ip)
        save()
        return true
    }

    fun unregister(uuid: UUID): Boolean {
        if (!playerData.containsKey(uuid)) return false
        playerData.remove(uuid)
        save()
        return true
    }

    fun changePassword(uuid: UUID, newPassword: String): Boolean {
        val existing = playerData[uuid] ?: return false
        if (!PasswordManager.isValidPassword(newPassword)) return false
        val hash = PasswordManager.hash(newPassword)
        playerData[uuid] = existing.copy(passwordHash = hash)
        save()
        return true
    }

    fun checkPassword(uuid: UUID, password: String): Boolean {
        val storedHash = playerData[uuid]?.passwordHash ?: return false
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


    fun isPremium(uuid: UUID): Boolean =
        playerData[uuid]?.premiumMode == true

    fun setPremiumMode(uuid: UUID, username: String, enable: Boolean, mojangUuid: UUID? = null) {
        val targetUuid = if (enable && mojangUuid != null) mojangUuid else uuid

        // Explicitly remove ALL possible old keys first
        val offlineUuid = PremiumManager.offlineUuid(username)
        playerData.remove(offlineUuid)
        playerData.remove(uuid)
        if (mojangUuid != null) playerData.remove(mojangUuid)
        // Also sweep by username to catch any orphan entries
        playerData.entries.removeIf { it.value.username.equals(username, ignoreCase = true) }

        playerData[targetUuid] = PlayerAuthData(
            uuid = targetUuid,
            username = username,
            passwordHash = null, // carry over if needed — see below
            lastIp = null,
            premiumMode = enable,
            mojangUuid = if (enable) mojangUuid?.toString() else null,
            joinMode = if (enable) JoinMode.PREMIUM else JoinMode.OFFLINE
        )
        save()
    }

    fun getMojangUuid(uuid: UUID): UUID? =
        playerData[uuid]?.mojangUuid?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }

    fun isPremiumAutoAuthCandidate(uuid: UUID, username: String): Boolean {
        val data = playerData[uuid] ?: return false
        if (!data.premiumMode) return false
        val storedMojangUuid = getMojangUuid(uuid) ?: return false
        val offlineUuid = PremiumManager.offlineUuid(username)
        return uuid == storedMojangUuid || uuid == offlineUuid
    }

    fun isPremiumMojangUuid(profileId: UUID): Boolean {
        return playerData.values.any { data ->
            if (!data.premiumMode) return@any false
            val stored = data.mojangUuid ?: return@any false
            runCatching { UUID.fromString(stored) }.getOrNull() == profileId
        }
    }

    fun findPremiumMojangUuidByUsername(username: String): UUID? {
        return playerData.values.firstNotNullOfOrNull { data ->
            if (!data.premiumMode) return@firstNotNullOfOrNull null
            if (!data.username.equals(username, ignoreCase = true)) return@firstNotNullOfOrNull null
            data.mojangUuid?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        }
    }

    fun isPremiumUsername(username: String): Boolean {
        return playerData.values.any { data ->
            data.premiumMode && data.username.equals(username, ignoreCase = true)
        }
    }

    fun hasProfileConflict(username: String, mojangUuid: UUID): Boolean {
        return playerData.any { (storedUuid, data) ->
            if (!data.username.equals(username, ignoreCase = true)) return@any false
            if (storedUuid == mojangUuid) return@any false
            val storedMojangUuid = data.mojangUuid?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            storedMojangUuid != mojangUuid
        }
    }

    fun promoteToPremiumIdentity(username: String, mojangUuid: UUID) {
        val offlineUuid = PremiumManager.offlineUuid(username)
        val existing = playerData.remove(mojangUuid)
            ?: playerData.remove(offlineUuid)
            ?: playerData.entries.firstOrNull { it.value.username.equals(username, ignoreCase = true) }?.let { entry ->
                playerData.remove(entry.key)
            }

        val updated = if (existing != null) {
            existing.copy(
                uuid = mojangUuid,
                username = username,
                premiumMode = true,
                mojangUuid = mojangUuid.toString(),
                joinMode = JoinMode.PREMIUM
            )
        } else {
            PlayerAuthData(
                uuid = mojangUuid,
                username = username,
                passwordHash = null,
                lastIp = null,
                premiumMode = true,
                mojangUuid = mojangUuid.toString(),
                joinMode = JoinMode.PREMIUM
            )
        }

        playerData[mojangUuid] = updated
        save()
    }


    fun markAuthenticated(uuid: UUID, ip: String?) {
        setState(uuid, AuthState.AUTHENTICATED)
        if (ip != null) updateLastIp(uuid, ip)
        save()
    }
}

enum class IdentityDecision {
    ALLOW_AND_RECORD,
    FORCE_OFFLINE,
    ALLOW_PREMIUM,
    KICK_NEEDS_PREMIUM
}