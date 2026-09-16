package net.deamjava.fabri_auth.mixin;

import net.deamjava.fabri_auth.auth.AuthStateManager;
import net.deamjava.fabri_auth.config.ConfigLoader;
import net.deamjava.fabri_auth.join.FabriAuthJoinGate;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(method = "placeNewPlayer", at = @At("HEAD"), cancellable = true)
    private void fabriAuth$gateRealJoin(
            Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {

        if (!ConfigLoader.INSTANCE.getConfig().getEnabled()) return;
        if (!ConfigLoader.INSTANCE.getConfig().getRequireLogin()) return;

        PlayerList playerList = (PlayerList) (Object) this;
        boolean shouldJoinNow = FabriAuthJoinGate.INSTANCE.decide(
                playerList, connection, player, cookie, playerList.getServer()
        );

        if (!shouldJoinNow) {
            ci.cancel();
        }
    }

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void fabriAuth$skipSaveWhileUnauthed(ServerPlayer player, CallbackInfo ci) {
        if (!ConfigLoader.INSTANCE.getConfig().getEnabled()) return;
        if (!ConfigLoader.INSTANCE.getConfig().getRequireLogin()) return;
        if (AuthStateManager.INSTANCE.isAuthenticated(player.getUUID())) return;

        ci.cancel();
    }

}