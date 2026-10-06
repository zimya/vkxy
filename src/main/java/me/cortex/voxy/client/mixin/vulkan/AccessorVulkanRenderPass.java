package me.cortex.voxy.client.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VulkanRenderPass.class, remap = false)
public interface AccessorVulkanRenderPass {
    @Accessor("commandBuffer")
    VkCommandBuffer voxy$getCommandBuffer();
}
