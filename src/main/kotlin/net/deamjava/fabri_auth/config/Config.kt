package net.deamjava.fabri_auth.config

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import net.fabricmc.loader.api.FabricLoader
import java.io.File

data class Config(
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("debug_raw_password_storage") val debugRawPasswordStorage: Boolean = false,
    @SerializedName("require_login") val requireLogin: Boolean = true,

    @SerializedName("auto_premium_login") val autoPremiumLogin: Boolean = true,

    @SerializedName("session_enabled") val sessionEnabled: Boolean = true,
    @SerializedName("session_ip_based") val sessionIpBased: Boolean = true,
    @SerializedName("session_timeout_seconds") val sessionTimeoutSeconds: Long = 3600L,

    @SerializedName("bcrypt_log_rounds") val bcryptLogRounds: Int = 10,
    @SerializedName("global_password_enabled") val globalPasswordEnabled: Boolean = false,
    @SerializedName("global_password_hash") val globalPasswordHash: String = "",

    @SerializedName("block_movement_until_authed") val blockMovementUntilAuthed: Boolean = true,
    @SerializedName("block_chat_until_authed") val blockChatUntilAuthed: Boolean = true,

    @SerializedName("limbo_enabled") val limboEnabled: Boolean = true,
    @SerializedName("limbo_world_name") val limboWorldName: String = "fabri_auth:limbo",

    @SerializedName("luckperms_integration") val luckpermsIntegration: Boolean = true,
    @SerializedName("floodgate_integration") val floodgateIntegration: Boolean = true,
    @SerializedName("carpet_integration") val carpetIntegration: Boolean = true,
    @SerializedName("vanish_integration") val vanishIntegration: Boolean = true,

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
    @SerializedName("message_already_registered") val messageAlreadyRegistered: String =
        "§cYou are already registered. Use /login <password>",
    @SerializedName("message_register_success") val messageRegisterSuccess: String =
        "§aRegistered successfully! You are now logged in.",
    @SerializedName("message_password_mismatch") val messagePasswordMismatch: String =
        "§cPasswords do not match.",
    @SerializedName("message_session_restored") val messageSessionRestored: String =
        "§aSession restored. Welcome back!",
    @SerializedName("message_unregister_success") val messageUnregisterSuccess: String =
        "§aYou have been unregistered.",
    @SerializedName("message_changepass_success") val messageChangePassSuccess: String =
        "§aPassword changed successfully.",
    @SerializedName("message_premium_success") val messagePremiumSuccess: String =
        "§aYou are now in premium mode. Please reconnect to authenticate with Mojang.",
    @SerializedName("message_cracked_success") val messageCrackedSuccess: String =
        "§aYou are now in cracked mode. Please reconnect.",
    @SerializedName("message_already_premium") val messageAlreadyPremium: String =
        "§eYou are already in premium mode.",
    @SerializedName("message_already_cracked") val messageAlreadyCracked: String =
        "§eYou are already in cracked mode.",
    @SerializedName("message_premium_not_found") val messagePremiumNotFound: String =
        "§cCould not verify your Mojang account. Make sure your username matches exactly.",
    @SerializedName("message_premium_cmd_unavailable") val messagePremiumCmdUnavailable: String =
        "§cThis command is not available when autoPremiumLogin is enabled."
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