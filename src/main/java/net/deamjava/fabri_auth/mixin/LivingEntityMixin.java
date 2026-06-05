package net.deamjava.fabri_auth.mixin;

import net.deamjava.fabri_auth.command.LoginCommand;
import net.deamjava.fabri_auth.limbo.LimboManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void fabriAuth$freezeInLimbo(CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        if (LoginCommand.isBlocked(player) && LimboManager.INSTANCE.isInLimbo(player)) {
            ci.cancel();
        }
    }
}