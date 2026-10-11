package cc.minevisor.live.fabric.mixin;

import cc.minevisor.live.fabric.MinevisorLiveMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {

    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", at = @At("HEAD"), require = 0)
    private void minevisor$onSwing(CallbackInfo ci) {
        count();
    }

    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z",
            at = @At("RETURN"), require = 0)
    private void minevisor$onSwingAnimated(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) count();
    }

    private void count() {
        if ((Object) this instanceof ServerPlayer player) MinevisorLiveMod.SWINGS.swing(player.getUUID());
    }
}
