package net.deamjava.fabri_auth.mixin;

import com.mojang.authlib.GameProfile;
import net.deamjava.fabri_auth.auth.IdentityDecision;
import net.deamjava.fabri_auth.auth.PremiumManager;
import net.deamjava.fabri_auth.auth.AuthState;
import net.deamjava.fabri_auth.auth.AuthStateManager;
import net.deamjava.fabri_auth.integration.CarpetHook;
import net.deamjava.fabri_auth.config.ConfigLoader;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.UUID;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {

    @Shadow private MinecraftServer server;
    @Shadow private Connection connection;
    @Shadow @Final private byte[] challenge;
    @Shadow private String requestedUsername;
    @Shadow public abstract void disconnect(Component component);

    @Unique
    private UUID fabriAuth$expectedPremiumUuid;

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void fabriAuth$onHello(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (!ConfigLoader.INSTANCE.getConfig().getEnabled()) return;
        if (!ConfigLoader.INSTANCE.getConfig().getAutoPremiumLogin()) return;

        String username = packet.name();
        UUID profileId = packet.profileId();

        if (profileId != null && CarpetHook.INSTANCE.isFakePlayer(username)) {
            AuthStateManager.INSTANCE.setState(profileId, AuthState.AUTHENTICATED);
            return;
        }

        if (this.server.usesAuthentication() || this.connection.isMemoryConnection()) return;
        if (profileId == null) return;

        UUID offlineUuid = PremiumManager.INSTANCE.offlineUuid(username);

        boolean clientClaimsPremium = !profileId.equals(offlineUuid);
        IdentityDecision decision = AuthStateManager.INSTANCE.resolveIdentity(username, clientClaimsPremium);

        switch (decision) {
            case KICK_NEEDS_PREMIUM -> {
                this.disconnect(Component.literal(
                        "This account is set to premium mode on this server. " +
                                "Please connect with your official Minecraft account."
                ));
                ci.cancel();
                return;
            }
            case FORCE_OFFLINE -> {
                return;
            }
            case ALLOW_PREMIUM, ALLOW_AND_RECORD -> {
            }
        }


        if (profileId.equals(offlineUuid)) {
            return;
        }

        UUID mojangUuid = PremiumManager.INSTANCE.fetchMojangUuid(username);
        if (mojangUuid == null) {
            if (AuthStateManager.INSTANCE.isPremiumUsername(username)) {
                this.disconnect(Component.literal("This username is reserved for a premium account."));
                ci.cancel();
            }
            return;
        }

        if (AuthStateManager.INSTANCE.hasProfileConflict(username, mojangUuid)) {
            this.disconnect(Component.literal(
                    "Profile conflict detected for this premium username. Ask an admin to resolve it."
            ));
            ci.cancel();
            return;
        }

        if (!profileId.equals(mojangUuid)) {
            this.disconnect(Component.literal(
                    "Premium username detected, but the Mojang session is invalid."
            ));
            ci.cancel();
            return;
        }

        this.requestedUsername = username;
        this.fabriAuth$expectedPremiumUuid = mojangUuid;
        this.fabriAuth$setState("KEY");
        this.connection.send(new ClientboundHelloPacket(
                "", this.server.getKeyPair().getPublic().getEncoded(), this.challenge, true
        ));
        ci.cancel();
    }

    @Inject(method = "startClientVerification", at = @At("TAIL"))
    private void fabriAuth$onStartClientVerification(GameProfile profile, CallbackInfo ci) {
        if (this.fabriAuth$expectedPremiumUuid == null) return;
        if (!this.fabriAuth$expectedPremiumUuid.equals(profile.id())) return;

        AuthStateManager.INSTANCE.promoteToPremiumIdentity(profile.name(), profile.id());
        AuthStateManager.INSTANCE.setState(profile.id(), AuthState.AUTHENTICATED);
        this.fabriAuth$expectedPremiumUuid = null;
    }

    @Unique
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void fabriAuth$setState(String stateName) {
        try {
            Field stateField = null;
            for (Field f : ServerLoginPacketListenerImpl.class.getDeclaredFields()) {
                if (f.getType().isEnum()) {
                    // Check if this enum has the state we're looking for
                    boolean hasKey = false;
                    for (Object constant : f.getType().getEnumConstants()) {
                        if (((Enum<?>)constant).name().equals(stateName)) {
                            hasKey = true;
                            break;
                        }
                    }
                    if (hasKey) {
                        stateField = f;
                        break;
                    }
                }
            }
            if (stateField == null) throw new RuntimeException("Could not find login state field");
            stateField.setAccessible(true);
            Class<? extends Enum> enumClass = (Class<? extends Enum>) stateField.getType();
            Enum state = Enum.valueOf((Class) enumClass, stateName);
            stateField.set(this, state);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to update login state", e);
        }
    }
}
