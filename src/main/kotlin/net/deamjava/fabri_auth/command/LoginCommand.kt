// src/main/kotlin/net/deamjava/fabri_auth/command/LoginCommand.kt
package net.deamjava.fabri_auth.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import net.deamjava.fabri_auth.auth.AuthStateManager
import net.deamjava.fabri_auth.config.ConfigLoader
import net.deamjava.fabri_auth.integration.VanishHook
import net.deamjava.fabri_auth.luckperms.LuckPermsHook
import net.deamjava.fabri_auth.session.SessionManager
import net.deamjava.fabri_auth.util.sendMessage
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions

object LoginCommand {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        // /login <password>
        dispatcher.register(
            Commands.literal("login")
                .then(
                    Commands.argument("password", StringArgumentType.word())
                        .executes { ctx ->
                            val source = ctx.source
                            val player = source.playerOrException
                            val password = StringArgumentType.getString(ctx, "password")
                            handleLogin(player, password)
                            1
                        }
                )
        )

        // /register <password> <confirm>
        dispatcher.register(
            Commands.literal("register")
                .then(
                    Commands.argument("password", StringArgumentType.word())
                        .then(
                            Commands.argument("confirm", StringArgumentType.word())
                                .executes { ctx ->
                                    val source = ctx.source
                                    val player = source.playerOrException
                                    val password = StringArgumentType.getString(ctx, "password")
                                    val confirm = StringArgumentType.getString(ctx, "confirm")
                                    handleRegister(player, password, confirm)
                                    1
                                }
                        )
                )
        )

        // /logout
        dispatcher.register(
            Commands.literal("logout")
                .executes { ctx ->
                    val player = ctx.source.playerOrException
                    handleLogout(player)
                    1
                }
        )

        // /auth reload (op only)
        dispatcher.register(
            Commands.literal("auth")
                .requires { it.permissions().hasPermission(Permissions.COMMANDS_OWNER)  }
                .then(
                    Commands.literal("reload")
                        .executes { ctx ->
                            ConfigLoader.load()
                            ctx.source.sendSystemMessage(
                                net.minecraft.network.chat.Component.literal("§a[FabriAuth] Config reloaded.")
                            )
                            1
                        }
                )
                .then(
                    Commands.literal("status")
                        .then(
                            Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                                .executes { ctx ->
                                    val target = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player")
                                    val state = AuthStateManager.getState(target.uuid)
                                    ctx.source.sendSystemMessage(
                                        net.minecraft.network.chat.Component.literal(
                                            "§e${target.name.string} auth state: $state"
                                        )
                                    )
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
        AuthStateManager.setState(uuid, net.deamjava.fabri_auth.auth.AuthState.UNAUTHENTICATED)
        SessionManager.invalidateSession(uuid)
        VanishHook.hidePlayer(player)
        LuckPermsHook.invalidateContexts(player)
        player.sendMessage("§eYou have been logged out.")
    }

    fun doAuthenticate(player: ServerPlayer, ip: String?) {
        val uuid = player.uuid
        AuthStateManager.markAuthenticated(uuid, ip)
        if (ip != null) SessionManager.createSession(uuid, ip)
        VanishHook.showPlayer(player)
        LuckPermsHook.invalidateContexts(player)
    }

    /** Returns true if movement/chat should be blocked. */
    @JvmStatic
    fun isBlocked(player: ServerPlayer): Boolean {
        if (!ConfigLoader.config.requireLogin) return false
        return !AuthStateManager.isAuthenticated(player.uuid)
    }

    // Extension to get player IP safely
    private val ServerPlayer.ipAddress: String?
        get() = (this.connection.remoteAddress as? java.net.InetSocketAddress)
            ?.address?.hostAddress
}