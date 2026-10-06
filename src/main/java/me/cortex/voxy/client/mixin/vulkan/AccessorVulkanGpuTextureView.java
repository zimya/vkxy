package me.cortex.voxy.client.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VulkanGpuTextureView.class, remap = false)
public interface AccessorVulkanGpuTextureView {
    @Accessor("vkImageView")
    long voxy$getVkImageView();
}
