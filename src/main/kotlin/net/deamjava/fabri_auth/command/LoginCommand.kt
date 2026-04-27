package net.deamjava.fabri_auth.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.deamjava.fabri_auth.auth.AuthState
import net.deamjava.fabri_auth.auth.AuthStateManager
import net.deamjava.fabri_auth.auth.JoinMode
import net.deamjava.fabri_auth.auth.PasswordManager
import net.deamjava.fabri_auth.auth.PremiumManager
import net.deamjava.fabri_auth.config.ConfigLoader
import net.deamjava.fabri_auth.integration.VanishHook
import net.deamjava.fabri_auth.limbo.LimboManager
import net.deamjava.fabri_auth.luckperms.LuckPermsHook
import net.deamjava.fabri_auth.auth.SessionManager
import net.deamjava.fabri_auth.util.sendMessage
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions
import java.util.concurrent.CompletableFuture

object LoginCommand {

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
        AuthStateManager.setState(uuid, AuthState.UNAUTHENTICATED)
        SessionManager.invalidateSession(uuid)
        VanishHook.hidePlayer(player)
        LuckPermsHook.invalidateContexts(player)
        LimboManager.sendToLimbo(player)
        player.sendMessage("§eYou have been logged out.")
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
        VanishHook.hidePlayer(player)
        LuckPermsHook.invalidateContexts(player)
        LimboManager.sendToLimbo(player)
        player.sendMessage(cfg.messageUnregisterSuccess)
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
        val cfg = ConfigLoader.config
//        if (cfg.autoPremiumLogin) {
//            player.sendMessage(cfg.messagePremiumCmdUnavailable)
//            return
//        }

        val uuid = player.uuid
        val username = player.name.string

        if (AuthStateManager.getJoinMode(uuid) == JoinMode.PREMIUM) {
            player.sendMessage(cfg.messageAlreadyPremium)
            return
        }

        CompletableFuture.supplyAsync {
            PremiumManager.fetchMojangUuid(username)
        }.thenAcceptAsync({ mojangUuid ->
            if (mojangUuid == null) {
                player.sendMessage(cfg.messagePremiumNotFound)
            } else {
                AuthStateManager.setPremiumMode(uuid, username, enable = true, mojangUuid = mojangUuid)
                player.sendMessage(cfg.messagePremiumSuccess)
                player.connection.disconnect(
                    Component.literal("§aPremium mode enabled. Please reconnect to authenticate with Mojang.")
                )
            }
        }, player.level().server)
    }


    fun handleCracked(player: ServerPlayer) {
        val cfg = ConfigLoader.config
//        if (cfg.autoPremiumLogin) {
//            player.sendMessage(cfg.messagePremiumCmdUnavailable)
//            return
//        }

        val uuid = player.uuid
        val username = player.name.string

        if (AuthStateManager.getJoinMode(uuid) == JoinMode.OFFLINE) {
            player.sendMessage(cfg.messageAlreadyCracked)
            return
        }

        AuthStateManager.setPremiumMode(uuid, username, enable = false)
        player.sendMessage(cfg.messageCrackedSuccess)
        player.connection.disconnect(
            Component.literal("§aCracked mode enabled. Please reconnect.")
        )
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
            VanishHook.hidePlayer(target)
            LuckPermsHook.invalidateContexts(target)
            LimboManager.sendToLimbo(target)
            source.sendSystemMessage(Component.literal("§a${target.name.string} has been force-unregistered."))
            target.sendMessage("§cAn admin has unregistered you.")
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
        LimboManager.returnFromLimbo(player)
        VanishHook.showPlayer(player)
        LuckPermsHook.invalidateContexts(player)
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