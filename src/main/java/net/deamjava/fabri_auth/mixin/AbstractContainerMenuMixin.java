package net.deamjava.fabri_auth.mixin;

import net.deamjava.fabri_auth.command.LoginCommand;
import net.deamjava.fabri_auth.config.ConfigLoader;
import net.deamjava.fabri_auth.limbo.LimboManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {

    @Inject(method = "doClick", at = @At("HEAD"), cancellable = true)
    private void fabriAuth$blockInventoryClick(int slotId, int button, ClickType clickType,
                                               Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (!ConfigLoader.INSTANCE.getConfig().getEnabled()) return;
        if (!ConfigLoader.INSTANCE.getConfig().getBlockInventoryUntilAuthed()) return;
        if (LoginCommand.isBlocked(sp) && LimboManager.INSTANCE.isInLimbo(sp)) {
            ci.cancel();
            sp.containerMenu.sendAllDataToRemote();
        }
    }
}