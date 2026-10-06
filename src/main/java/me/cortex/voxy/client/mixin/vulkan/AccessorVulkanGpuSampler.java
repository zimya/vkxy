package me.cortex.voxy.client.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VulkanGpuSampler.class, remap = false)
public interface AccessorVulkanGpuSampler {
    @Accessor("vkSampler")
    long voxy$getVkSampler();
}
