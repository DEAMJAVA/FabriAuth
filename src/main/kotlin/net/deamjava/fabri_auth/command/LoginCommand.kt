package net.deamjava.fabri_auth.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.deamjava.fabri_auth.auth.AuthState
import net.deamjava.fabri_auth.auth.AuthStateManager
import net.deamjava.fabri_auth.auth.JoinMode
import net.deamjava.fabri_auth.auth.PasswordManager
import net.deamjava.fabri_auth.auth.PlayerDataMigrator
import net.deamjava.fabri_auth.auth.PremiumManager
import net.deamjava.fabri_auth.config.ConfigLoader
import net.deamjava.fabri_auth.luckperms.LuckPermsHook
import net.deamjava.fabri_auth.auth.SessionManager
import net.deamjava.fabri_auth.limbo.FakeJoinManager
import net.deamjava.fabri_auth.util.sendMessage
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

enum class MigrateDirection {
    PREMIUM_TO_CRACKED,
    CRACKED_TO_PREMIUM
}

object LoginCommand {

    private data class PendingMigration(val direction: MigrateDirection, val expiresAt: Long)

    private val pendingMigrations = ConcurrentHashMap<UUID, PendingMigration>()
    private const val MIGRATION_CONFIRM_WINDOW_MS = 30_000L

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {

        dispatcher.register(
            Commands.literal("login")
                .then(
                    Commands.argument("password", StringArgumentType.word())
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            val password = StringArgumentType.getString(ctx, "password")
                            handleLogin(player, password)
                            1
                        }
                )
        )

        dispatcher.register(
            Commands.literal("register")
                .then(
                    Commands.argument("password", StringArgumentType.word())
                        .then(
                            Commands.argument("confirm", StringArgumentType.word())
                                .executes { ctx ->
                                    val player = ctx.source.playerOrException
                                    val password = StringArgumentType.getString(ctx, "password")
                                    val confirm = StringArgumentType.getString(ctx, "confirm")
                                    handleRegister(player, password, confirm)
                                    1
                                }
                        )
                )
        )

        dispatcher.register(
            Commands.literal("logout")
                .executes { ctx ->
                    val player = ctx.source.playerOrException
                    handleLogout(player)
                    1
                }
        )

        dispatcher.register(
            Commands.literal("unregister")
                .then(
                    Commands.argument("password", StringArgumentType.word())
                        .executes { ctx ->
                            val player = ctx.source.playerOrException
                            val password = StringArgumentType.getString(ctx, "password")
                            handleUnregister(player, password)
                            1
                        }
                )
        )

        dispatcher.register(
            Commands.literal("changepass")
                .then(
                    Commands.argument("current", StringArgumentType.word())
                        .then(
                            Commands.argument("newpass", StringArgumentType.word())
                                .then(
                                    Commands.argument("confirm", StringArgumentType.word())
                                        .executes { ctx ->
                                            val player = ctx.source.playerOrException
                                            val current = StringArgumentType.getString(ctx, "current")
                                            val newpass = StringArgumentType.getString(ctx, "newpass")
                                            val confirm = StringArgumentType.getString(ctx, "confirm")
                                            handleChangePass(player, current, newpass, confirm)
                                            1
                                        }
                                )
                        )
                )
        )

        dispatcher.register(
            Commands.literal("premium")
                .executes { ctx ->
                    val player = ctx.source.playerOrException
                    handlePremium(player)
                    1
                }
        )

        dispatcher.register(
            Commands.literal("cracked")
                .executes { ctx ->
                    val player = ctx.source.playerOrException
                    handleCracked(player)
                    1
                }
        )

        dispatcher.register(
            Commands.literal("confirm")
                .executes { ctx ->
                    handleMigrateConfirm(ctx.source.playerOrException)
                    1
                }
        )

        dispatcher.register(
            Commands.literal("cancel")
                .executes { ctx ->
                    handleMigrateCancel(ctx.source.playerOrException)
                    1
                }
        )

        dispatcher.register(
            Commands.literal("auth")
                .requires { it.permissions().hasPermission(Permissions.COMMANDS_OWNER) }

                .then(
                    Commands.literal("reload")
                        .executes { ctx ->
                            ConfigLoader.load()
                            ctx.source.sendSystemMessage(Component.literal("§a[FabriAuth] Config reloaded."))
                            1
                        }
                )

                .then(
                    Commands.literal("status")
                        .then(
                            Commands.argument("player", EntityArgument.player())
                                .executes { ctx ->
                                    val target = EntityArgument.getPlayer(ctx, "player")
                                    val state = AuthStateManager.getState(target.uuid)
                                    val mode = AuthStateManager.getJoinMode(target.uuid)
                                    ctx.source.sendSystemMessage(
                                        Component.literal("§e${target.name.string} — auth: $state, mode: $mode")
                                    )
                                    1
                                }
                        )
                )

                .then(
                    Commands.literal("forceReg")
                        .then(
                            Commands.argument("player", EntityArgument.player())
                                .then(
                                    Commands.argument("password", StringArgumentType.word())
                                        .executes { ctx ->
                                            val target = EntityArgument.getPlayer(ctx, "player")
                                            val password = StringArgumentType.getString(ctx, "password")
                                            handleForceReg(ctx.source, target, password)
                                            1
                                        }
                                )
                        )
                )

                .then(
                    Commands.literal("forceUnReg")
                        .then(
                            Commands.argument("player", EntityArgument.player())
                                .executes { ctx ->
                                    val target = EntityArgument.getPlayer(ctx, "player")
                                    handleForceUnReg(ctx.source, target)
                                    1
                                }
                        )
                )

                .then(
                    Commands.literal("forceChangePass")
                        .then(
                            Commands.argument("player", EntityArgument.player())
                                .then(
                                    Commands.argument("newpass", StringArgumentType.word())
                                        .executes { ctx ->
                                            val target = EntityArgument.getPlayer(ctx, "player")
                                            val newpass = StringArgumentType.getString(ctx, "newpass")
                                            handleForceChangePass(ctx.source, target, newpass)
                                            1
                                        }
                                )
                        )
                )


                .then(
                    Commands.literal("premium")
                        .then(
                            Commands.argument("username", StringArgumentType.word())
                                .executes { ctx ->
                                    val username = StringArgumentType.getString(ctx, "username")
                                    handleAdminPremium(ctx.source, username)
                                    1
                                }
                        )
                )

                .then(
                    Commands.literal("cracked")
                        .then(
                            Commands.argument("username", StringArgumentType.word())
                                .executes { ctx ->
                                    val username = StringArgumentType.getString(ctx, "username")
                                    handleAdminCracked(ctx.source, username)
                                    1
                                }
                        )
                )
        )
    }


    fun handleLogin(player: ServerPlayer, password: String) {
        val cfg = ConfigLoader.config
        val uuid = player.uuid
        val ip = player.ipAddress

        if (AuthStateManager.isAuthenticated(uuid)) {
            player.sendMessage(cfg.messageAlreadyLoggedIn)
            return
        }
        if (!AuthStateManager.isRegistered(uuid)) {
            player.sendMessage(cfg.messageNotRegistered)
            return
        }
        if (AuthStateManager.checkPassword(uuid, password)) {
            doAuthenticate(player, ip)
            player.sendMessage(cfg.messageLoginSuccess)
        } else {
            player.sendMessage(cfg.messageLoginFailed)
        }
    }


    fun handleRegister(player: ServerPlayer, password: String, confirm: String) {
        val cfg = ConfigLoader.config
        val uuid = player.uuid
        val ip = player.ipAddress

        if (AuthStateManager.isAuthenticated(uuid)) {
            player.sendMessage(cfg.messageAlreadyLoggedIn)
            return
        }
        if (AuthStateManager.isRegistered(uuid)) {
            player.sendMessage(cfg.messageAlreadyRegistered)
            return
        }
        if (password != confirm) {
            player.sendMessage(cfg.messagePasswordMismatch)
            return
        }
        val registered = AuthStateManager.register(uuid, player.name.string, password, ip)
        if (registered) {
            doAuthenticate(player, ip)
            player.sendMessage(cfg.messageRegisterSuccess)
        } else {
            player.sendMessage("§cRegistration failed. Password must be 4-64 characters.")
        }
    }


    fun handleLogout(player: ServerPlayer) {
        val uuid = player.uuid
        PlayerDataMigrator.forceSavePlayer(player)
        AuthStateManager.setState(uuid, AuthState.UNAUTHENTICATED)
        SessionManager.invalidateSession(uuid)
        LuckPermsHook.invalidateContexts(player)
        player.connection.disconnect(Component.literal("§eYou have been logged out. Please reconnect."))
    }


    fun handleUnregister(player: ServerPlayer, password: String) {
        val cfg = ConfigLoader.config
        val uuid = player.uuid

        if (!AuthStateManager.isAuthenticated(uuid)) {
            player.sendMessage(cfg.messageNotLoggedIn)
            return
        }
        if (!AuthStateManager.isRegistered(uuid)) {
            player.sendMessage(cfg.messageNotRegistered)
            return
        }
        if (!AuthStateManager.checkPassword(uuid, password)) {
            player.sendMessage(cfg.messageLoginFailed)
            return
        }
        AuthStateManager.unregister(uuid)
        SessionManager.invalidateSession(uuid)
        AuthStateManager.setState(uuid, AuthState.UNAUTHENTICATED)
        LuckPermsHook.invalidateContexts(player)
        player.connection.disconnect(Component.literal(cfg.messageUnregisterSuccess))
    }

    fun handleChangePass(player: ServerPlayer, current: String, newpass: String, confirm: String) {
        val cfg = ConfigLoader.config
        val uuid = player.uuid

        if (!AuthStateManager.isAuthenticated(uuid)) {
            player.sendMessage(cfg.messageNotLoggedIn)
            return
        }
        if (!AuthStateManager.isRegistered(uuid)) {
            player.sendMessage(cfg.messageNotRegistered)
            return
        }
        if (!AuthStateManager.checkPassword(uuid, current)) {
            player.sendMessage(cfg.messageLoginFailed)
            return
        }
        if (newpass != confirm) {
            player.sendMessage(cfg.messagePasswordMismatch)
            return
        }
        if (!PasswordManager.isValidPassword(newpass)) {
            player.sendMessage("§cNew password must be 4-64 characters.")
            return
        }
        val changed = AuthStateManager.changePassword(uuid, newpass)
        if (changed) {
            SessionManager.invalidateSession(uuid)
            player.sendMessage(cfg.messageChangePassSuccess)
        } else {
            player.sendMessage("§cFailed to change password.")
        }
    }


    fun handlePremium(player: ServerPlayer) {
        handleMigrateRequest(player, MigrateDirection.CRACKED_TO_PREMIUM)
    }

    fun handleCracked(player: ServerPlayer) {
        handleMigrateRequest(player, MigrateDirection.PREMIUM_TO_CRACKED)
    }


    fun handleMigrateRequest(player: ServerPlayer, direction: MigrateDirection) {
        val cfg = ConfigLoader.config
        val uuid = player.uuid

        if (!AuthStateManager.isAuthenticated(uuid)) {
            player.sendMessage(cfg.messageNotLoggedIn)
            return
        }

        val currentMode = AuthStateManager.getJoinMode(uuid)
        when (direction) {
            MigrateDirection.PREMIUM_TO_CRACKED -> {
                if (currentMode != JoinMode.PREMIUM) {
                    player.sendMessage("§cYou are not currently in premium mode. Nothing to migrate.")
                    return
                }
            }
            MigrateDirection.CRACKED_TO_PREMIUM -> {
                if (currentMode != JoinMode.OFFLINE) {
                    player.sendMessage("§cYou are not currently in cracked/offline mode. Nothing to migrate.")
                    return
                }
            }
        }

        pendingMigrations[uuid] = PendingMigration(
            direction = direction,
            expiresAt = System.currentTimeMillis() + MIGRATION_CONFIRM_WINDOW_MS
        )

        val (fromLabel, toLabel) = when (direction) {
            MigrateDirection.PREMIUM_TO_CRACKED -> "Premium" to "Cracked"
            MigrateDirection.CRACKED_TO_PREMIUM -> "Cracked" to "Premium"
        }
        player.sendMessage("§c⚠ This will migrate your account from $fromLabel to $toLabel mode.")
        player.sendMessage("§cYour password and registration move with it, but this action is IRREVERSIBLE.")
        player.sendMessage("§eType §f/confirm §eto proceed, or §f/cancel §eto abort. " +
                "This request expires in 30 seconds.")
    }

    fun handleMigrateConfirm(player: ServerPlayer) {
        val uuid = player.uuid
        val username = player.name.string
        val pending = pendingMigrations[uuid]

        if (pending == null) {
            player.sendMessage("§cYou have no pending migration. " +
                    "Use /migrate premiumToCracked or /migrate crackedToPremium first.")
            return
        }
        if (System.currentTimeMillis() > pending.expiresAt) {
            pendingMigrations.remove(uuid)
            player.sendMessage("§cYour migration request expired. Please run the /migrate command again.")
            return
        }
        pendingMigrations.remove(uuid)

        when (pending.direction) {
            MigrateDirection.PREMIUM_TO_CRACKED -> {
                val targetUuid = PremiumManager.offlineUuid(username)
                performMigration(
                    player, uuid, targetUuid, toPremium = false,
                    successMessage = "§aAccount migrated to Cracked mode. Please reconnect."
                )
            }

            MigrateDirection.CRACKED_TO_PREMIUM -> {
                player.sendMessage("§eVerifying your Mojang account, please wait...")
                CompletableFuture.supplyAsync {
                    PremiumManager.fetchMojangUuid(username)
                }.thenAcceptAsync({ mojangUuid ->
                    if (mojangUuid == null) {
                        player.sendMessage(
                            "§cCould not verify a Mojang account for '$username'. Migration aborted, " +
                                    "nothing was changed. Make sure your username matches your Mojang account exactly."
                        )
                        return@thenAcceptAsync
                    }
                    performMigration(
                        player, uuid, mojangUuid, toPremium = true,
                        successMessage = "§aAccount migrated to Premium mode. Please reconnect with your official Minecraft account."
                    )
                }, player.level().server)
            }
        }
    }

    private fun performMigration(
        player: ServerPlayer,
        sourceUuid: UUID,
        targetUuid: UUID,
        toPremium: Boolean,
        successMessage: String
    ) {
        val username = player.name.string

        when (PlayerDataMigrator.migrate(player, sourceUuid, targetUuid)) {
            PlayerDataMigrator.MigrationResult.SaveFailed -> {
                player.sendMessage("§cMigration aborted: could not save your current data first. " +
                        "Nothing was changed — please try again, or contact an admin if this keeps happening.")
                return
            }
            PlayerDataMigrator.MigrationResult.NothingToMigrate -> {
                println("[FabriAuth] No vanilla save data found to migrate for $username; " +
                        "continuing with account-record migration only.")
            }
            PlayerDataMigrator.MigrationResult.Success -> {
            }
        }

        val migrated = AuthStateManager.migrateAccountData(sourceUuid, targetUuid, username, toPremium = toPremium)
        if (!migrated) {
            player.sendMessage("§cMigration failed: no account data was found to migrate. " +
                    "Your save files were already moved — please contact an admin, this needs manual cleanup.")
            return
        }

        SessionManager.invalidateSession(sourceUuid)
        player.sendMessage(successMessage)
        player.connection.disconnect(Component.literal(successMessage))
    }

    fun handleMigrateCancel(player: ServerPlayer) {
        val uuid = player.uuid
        if (pendingMigrations.remove(uuid) != null) {
            player.sendMessage("§eMigration request cancelled.")
        } else {
            player.sendMessage("§cYou have no pending migration to cancel.")
        }
    }

    /** Clears any pending migration confirmation when a player disconnects, so it can't be confirmed by a future session. */
    fun clearPendingMigration(uuid: UUID) {
        pendingMigrations.remove(uuid)
    }


    private fun handleAdminPremium(source: CommandSourceStack, username: String) {
        val onlinePlayer = source.server.playerList.getPlayerByName(username)

        if (onlinePlayer != null) {
            val uuid = onlinePlayer.uuid
            if (AuthStateManager.getJoinMode(uuid) == JoinMode.PREMIUM) {
                source.sendSystemMessage(Component.literal("§e$username is already in premium mode."))
                return
            }
            CompletableFuture.supplyAsync {
                PremiumManager.fetchMojangUuid(username)
            }.thenAcceptAsync({ mojangUuid ->
                if (mojangUuid == null) {
                    source.sendSystemMessage(
                        Component.literal("§cCould not verify Mojang account for $username.")
                    )
                } else {
                    AuthStateManager.setPremiumMode(uuid, username, enable = true, mojangUuid = mojangUuid)
                    source.sendSystemMessage(Component.literal("§a$username has been set to premium mode."))
                    onlinePlayer.connection.disconnect(
                        Component.literal("§aAn admin has enabled premium mode for your account. Please reconnect.")
                    )
                }
            }, source.server)
        } else {
            val updated = AuthStateManager.setJoinModeByUsername(username, JoinMode.PREMIUM)
            if (!updated) {
                source.sendSystemMessage(
                    Component.literal("§cNo stored record found for '$username'. They must join the server at least once first.")
                )
            } else {
                source.sendSystemMessage(Component.literal("§a$username has been set to premium mode (offline update)."))
            }
        }
    }


    private fun handleAdminCracked(source: CommandSourceStack, username: String) {
        val onlinePlayer = source.server.playerList.getPlayerByName(username)

        if (onlinePlayer != null) {
            val uuid = onlinePlayer.uuid
            if (AuthStateManager.getJoinMode(uuid) == JoinMode.OFFLINE) {
                source.sendSystemMessage(Component.literal("§e$username is already in cracked/offline mode."))
                return
            }
            AuthStateManager.setPremiumMode(uuid, username, enable = false)
            source.sendSystemMessage(Component.literal("§a$username has been set to cracked/offline mode."))
            onlinePlayer.connection.disconnect(
                Component.literal("§aAn admin has enabled cracked mode for your account. Please reconnect.")
            )
        } else {
            val updated = AuthStateManager.setJoinModeByUsername(username, JoinMode.OFFLINE)
            if (!updated) {
                source.sendSystemMessage(
                    Component.literal("§cNo stored record found for '$username'. They must join the server at least once first.")
                )
            } else {
                source.sendSystemMessage(Component.literal("§a$username has been set to cracked/offline mode (offline update)."))
            }
        }
    }


    private fun handleForceReg(source: CommandSourceStack, target: ServerPlayer, password: String) {
        val uuid = target.uuid
        val ip = target.ipAddress

        if (AuthStateManager.isRegistered(uuid)) {
            source.sendSystemMessage(Component.literal("§c${target.name.string} is already registered."))
            return
        }
        if (!PasswordManager.isValidPassword(password)) {
            source.sendSystemMessage(Component.literal("§cPassword must be 4-64 characters."))
            return
        }
        val ok = AuthStateManager.register(uuid, target.name.string, password, ip)
        if (ok) {
            source.sendSystemMessage(Component.literal("§a${target.name.string} has been force-registered."))
            target.sendMessage("§aAn admin has registered you. Please change your password with /changepass.")
        } else {
            source.sendSystemMessage(Component.literal("§cForce-registration failed."))
        }
    }


    private fun handleForceUnReg(source: CommandSourceStack, target: ServerPlayer) {
        val uuid = target.uuid

        if (!AuthStateManager.isRegistered(uuid)) {
            source.sendSystemMessage(Component.literal("§c${target.name.string} is not registered."))
            return
        }
        val ok = AuthStateManager.unregister(uuid)
        if (ok) {
            SessionManager.invalidateSession(uuid)
            AuthStateManager.setState(uuid, AuthState.UNAUTHENTICATED)
            LuckPermsHook.invalidateContexts(target)
            source.sendSystemMessage(Component.literal("§a${target.name.string} has been force-unregistered."))
            target.connection.disconnect(Component.literal("§cAn admin has unregistered you. Please reconnect."))
        } else {
            source.sendSystemMessage(Component.literal("§cForce-unregister failed."))
        }
    }


    private fun handleForceChangePass(source: CommandSourceStack, target: ServerPlayer, newpass: String) {
        val uuid = target.uuid

        if (!AuthStateManager.isRegistered(uuid)) {
            source.sendSystemMessage(Component.literal("§c${target.name.string} is not registered."))
            return
        }
        if (!PasswordManager.isValidPassword(newpass)) {
            source.sendSystemMessage(Component.literal("§cPassword must be 4-64 characters."))
            return
        }
        val ok = AuthStateManager.changePassword(uuid, newpass)
        if (ok) {
            SessionManager.invalidateSession(uuid)
            source.sendSystemMessage(Component.literal("§a${target.name.string}'s password has been changed."))
            target.sendMessage("§eAn admin has changed your password. Please re-login with /login <newpassword>.")
        } else {
            source.sendSystemMessage(Component.literal("§cForce-changepass failed."))
        }
    }


    fun doAuthenticate(player: ServerPlayer, ip: String?) {
        val uuid = player.uuid
        AuthStateManager.markAuthenticated(uuid, ip)
        if (ip != null) SessionManager.createSession(uuid, ip)
        LuckPermsHook.invalidateContexts(player)
        FakeJoinManager.promote(uuid)
    }

    @JvmStatic
    fun isBlocked(player: ServerPlayer): Boolean {
        if (!ConfigLoader.config.requireLogin) return false
        return !AuthStateManager.isAuthenticated(player.uuid)
    }

    private val ServerPlayer.ipAddress: String?
        get() = (this.connection.remoteAddress as? java.net.InetSocketAddress)
            ?.address?.hostAddress
}