package net.deamjava.fabri_auth.mixin;

import net.deamjava.fabri_auth.command.LoginCommand;
import net.deamjava.fabri_auth.config.ConfigLoader;
import net.deamjava.fabri_auth.limbo.LimboManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
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

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void fabriAuth$onMovePlayer(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!ConfigLoader.INSTANCE.getConfig().getEnabled()) return;
        if (!ConfigLoader.INSTANCE.getConfig().getBlockMovementUntilAuthed()) return;

        if (LoginCommand.isBlocked(player)) {
            if (LimboManager.INSTANCE.isInLimbo(player)) {
                ((ServerGamePacketListenerImpl)(Object)this).teleport(0.5, 4.0, 0.5, 0f, 0f);
            } else {
                ((ServerGamePacketListenerImpl)(Object)this).teleport(
                        player.getX(), player.getY(), player.getZ(),
                        player.getYRot(), player.getXRot()
                );
            }
            ci.cancel();
        }
    }

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

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void fabriAuth$onChatCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        if (!ConfigLoader.INSTANCE.getConfig().getEnabled()) return;

        if (LoginCommand.isBlocked(player)) {
            String command = packet.command().toLowerCase();
            if (command.startsWith("login ")   || command.equals("login")   ||
                    command.startsWith("register ") || command.equals("register") ||
                    command.startsWith("premium")   ||
                    command.startsWith("cracked")) {
                return;
            }
            player.sendSystemMessage(
                    Component.literal(ConfigLoader.INSTANCE.getConfig().getMessageNotLoggedIn())
            );
            ci.cancel();
        }
    }
}