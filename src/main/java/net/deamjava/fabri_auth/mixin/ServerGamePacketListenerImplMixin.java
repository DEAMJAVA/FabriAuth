// src/main/java/net/deamjava/fabri_auth/mixin/ServerGamePacketListenerImplMixin.java
package net.deamjava.fabri_auth.mixin;

import net.deamjava.fabri_auth.command.LoginCommand;
import net.deamjava.fabri_auth.config.ConfigLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Shadow
    public ServerPlayer player;

    /**
     * Block player movement if not authenticated.
     * We inject at HEAD and cancel if the player is not authed.
     */
    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void fabriAuth$onMovePlayer(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!ConfigLoader.INSTANCE.getConfig().getEnabled()) return;
        if (!ConfigLoader.INSTANCE.getConfig().getBlockMovementUntilAuthed()) return;

        if (LoginCommand.isBlocked(player)) {
            // Teleport the player back to their current position to "freeze" them
            ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl)(Object)this;
            self.teleport(
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    player.getYRot(),
                    player.getXRot()
            );
            ci.cancel();
        }
    }

    /**
     * Block chat if not authenticated.
     * We allow /login and /register commands through by checking the message prefix.
     */
    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void fabriAuth$onChat(ServerboundChatPacket packet, CallbackInfo ci) {
        if (!ConfigLoader.INSTANCE.getConfig().getEnabled()) return;
        if (!ConfigLoader.INSTANCE.getConfig().getBlockChatUntilAuthed()) return;

        if (LoginCommand.isBlocked(player)) {
            player.sendSystemMessage(
                    Component.literal(ConfigLoader.INSTANCE.getConfig().getMessageNotLoggedIn())
            );
            ci.cancel();
        }
    }

    /**
     * Block signed chat commands that aren't /login or /register while unauthenticated.
     */
    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void fabriAuth$onChatCommand(
            net.minecraft.network.protocol.game.ServerboundChatCommandPacket packet,
            CallbackInfo ci) {
        if (!ConfigLoader.INSTANCE.getConfig().getEnabled()) return;

        if (LoginCommand.isBlocked(player)) {
            String command = packet.command().toLowerCase();
            // Allow auth-related commands
            if (command.startsWith("login") ||
                    command.startsWith("register") ||
                    command.startsWith("l ")) {
                return; // let it through
            }
            player.sendSystemMessage(
                    Component.literal(ConfigLoader.INSTANCE.getConfig().getMessageNotLoggedIn())
            );
            ci.cancel();
        }
    }
}