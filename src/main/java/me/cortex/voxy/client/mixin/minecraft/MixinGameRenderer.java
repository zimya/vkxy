package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.core.vulkan.pipeline.VulkanRenderSystem;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    @ModifyArg(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
        ),
        index = 0
    )
    private Matrix4f voxy$captureLevelProjection(Matrix4f projectionMatrix) {
        VulkanRenderSystem.setCapturedLevelProjection(projectionMatrix);
        return projectionMatrix;
    }
}
