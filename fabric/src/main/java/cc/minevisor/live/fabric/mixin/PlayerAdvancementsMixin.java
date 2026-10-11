package cc.minevisor.live.fabric.mixin;

import cc.minevisor.live.fabric.MinevisorLiveMod;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsMixin {

    @Shadow
    private ServerPlayer player;

    @Inject(method = "award", at = @At("RETURN"))
    private void minevisor$onAward(AdvancementHolder advancement, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && player != null) MinevisorLiveMod.onAdvancement(player);
    }

    @Inject(method = "revoke", at = @At("RETURN"))
    private void minevisor$onRevoke(AdvancementHolder advancement, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && player != null) MinevisorLiveMod.onAdvancement(player);
    }
}
