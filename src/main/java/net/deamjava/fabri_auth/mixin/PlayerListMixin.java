package net.deamjava.fabri_auth.mixin;

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

/**
 * The core interception point for the fake-limbo join flow.
 *
 * PlayerList#placeNewPlayer is the single vanilla method that finalizes a
 * join: it constructs the real ServerGamePacketListenerImpl, adds the player
 * to the players/playersByUUID lists, adds them to their level, and
 * broadcasts the join message. By gating it at HEAD, we can substitute a
 * fake limbo session for anyone not yet authenticated, and let vanilla run
 * completely untouched for everyone else (including the second call made by
 * FakeJoinManager#promote once auth succeeds).
 */
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
}