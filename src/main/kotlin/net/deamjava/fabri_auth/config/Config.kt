// src/main/kotlin/net/deamjava/fabri_auth/config/Config.kt
package net.deamjava.fabri_auth.config

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import net.fabricmc.loader.api.FabricLoader
import java.io.File

data class Config(
    // --- Feature toggles ---
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("debug_raw_password_storage") val debugRawPasswordStorage: Boolean = false,
    @SerializedName("require_login") val requireLogin: Boolean = true,

    // --- Session ---
    @SerializedName("session_enabled") val sessionEnabled: Boolean = true,
    @SerializedName("session_ip_based") val sessionIpBased: Boolean = true,
    @SerializedName("session_timeout_seconds") val sessionTimeoutSeconds: Long = 3600L,

    // --- Password ---
    @SerializedName("bcrypt_log_rounds") val bcryptLogRounds: Int = 10,
    @SerializedName("global_password_enabled") val globalPasswordEnabled: Boolean = false,
    @SerializedName("global_password_hash") val globalPasswordHash: String = "",

    // --- Coordinate protection ---
    @SerializedName("block_movement_until_authed") val blockMovementUntilAuthed: Boolean = true,
    @SerializedName("block_chat_until_authed") val blockChatUntilAuthed: Boolean = true,

    // --- Integration toggles ---
    @SerializedName("luckperms_integration") val luckpermsIntegration: Boolean = true,
    @SerializedName("floodgate_integration") val floodgateIntegration: Boolean = true,
    @SerializedName("carpet_integration") val carpetIntegration: Boolean = true,
    @SerializedName("vanish_integration") val vanishIntegration: Boolean = true,

    // --- Messages ---
    @SerializedName("message_not_logged_in") val messageNotLoggedIn: String =
        "§cYou must login first! Use /login <password>",
    @SerializedName("message_login_success") val messageLoginSuccess: String =
        "§aSuccessfully logged in!",
    @SerializedName("message_login_failed") val messageLoginFailed: String =
        "§cIncorrect password.",
    @SerializedName("message_already_logged_in") val messageAlreadyLoggedIn: String =
        "§eYou are already logged in.",
    @SerializedName("message_not_registered") val messageNotRegistered: String =
        "§cYou are not registered. Use /register <password> <confirm>",
    @SerializedName("message_register_success") val messageRegisterSuccess: String =
        "§aRegistered successfully! You are now logged in.",
    @SerializedName("message_password_mismatch") val messagePasswordMismatch: String =
        "§cPasswords do not match.",
    @SerializedName("message_session_restored") val messageSessionRestored: String =
        "§aSession restored. Welcome back!"
)

object ConfigLoader {
    private val GSON = GsonBuilder().setPrettyPrinting().create()
    private val configFile: File by lazy {
        FabricLoader.getInstance()
            .configDir
            .resolve("fabri-auth.json")
            .toFile()
    }

    var config: Config = Config()
        private set

    fun load() {
        if (!configFile.exists()) {
            save(Config())
        }
        config = try {
            GSON.fromJson(configFile.readText(), Config::class.java) ?: Config()
        } catch (e: Exception) {
            println("[FabriAuth] Failed to parse config, using defaults: ${e.message}")
            Config()
        }
    }

    fun save(cfg: Config = config) {
        configFile.parentFile?.mkdirs()
        configFile.writeText(GSON.toJson(cfg))
        config = cfg
    }
}