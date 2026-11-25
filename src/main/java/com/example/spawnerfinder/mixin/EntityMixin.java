package com.example.spawnerfinder.mixin;

import com.example.spawnerfinder.SpawnerFinderClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "isGlowing", at = @At("HEAD"), cancellable = true)
    private void spawnerfinder$glowMinecarts(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (SpawnerFinderClient.showMinecarts
                && self instanceof AbstractMinecartEntity
                && self.getWorld().isClient()) {
            cir.setReturnValue(true);
        }
    }
}
