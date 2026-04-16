// src/main/java/net/deamjava/fabri_auth/mixin/ServerLoginPacketListenerImplMixin.java
package net.deamjava.fabri_auth.mixin;

import net.deamjava.fabri_auth.auth.AuthStateManager;
import net.deamjava.fabri_auth.auth.AuthState;
import net.deamjava.fabri_auth.config.ConfigLoader;
import net.deamjava.fabri_auth.integration.CarpetHook;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {

    // Shadow requestedUsername so we can read it in our inject
    @Shadow
    private String requestedUsername;

    /**
     * Injected at the head of handleHello to perform early checks.
     * At this point we have the player's claimed username and UUID.
     *
     * Note: The actual GameProfile UUID won't be final until authentication
     * completes, so we mark state as PENDING here and finalize it in
     * the ServerPlayConnectionEvents.JOIN handler on the Kotlin side.
     */
    @Inject(method = "handleHello", at = @At("HEAD"))
    private void fabriAuth$onHello(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (!ConfigLoader.INSTANCE.getConfig().getEnabled()) return;

        String username = packet.name();
        UUID profileId = packet.profileId();

        // For Carpet fake players, mark immediately (will be confirmed on join)
        if (CarpetHook.INSTANCE.isFakePlayer(username)) {
            AuthStateManager.INSTANCE.setState(profileId, AuthState.AUTHENTICATED);
        }
    }

    /**
     * Injected at RETURN of handleHello so we can observe the post-state.
     * At this point requestedUsername is set.
     */
    @Inject(method = "handleHello", at = @At("RETURN"))
    private void fabriAuth$afterHello(ServerboundHelloPacket packet, CallbackInfo ci) {
        // Intentionally left minimal - main auth logic lives in JOIN event.
        // Extended hooks (e.g., custom auth pipelines) would go here.
    }
}