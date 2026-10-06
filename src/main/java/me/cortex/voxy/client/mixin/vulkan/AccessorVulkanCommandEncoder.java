package me.cortex.voxy.client.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = VulkanCommandEncoder.class, remap = false)
public interface AccessorVulkanCommandEncoder {
    @Accessor("currentCommandBuffer")
    VkCommandBuffer voxy$getCurrentCommandBuffer();

    @Invoker("commandBuffer")
    VkCommandBuffer voxy$invokeCommandBuffer();

    @Invoker("endCommandBuffer")
    void voxy$invokeEndCommandBuffer();

    @Accessor("currentSubmitIndex")
    long voxy$getCurrentSubmitIndex();

    @Accessor("completedSubmitIndex")
    long voxy$getCompletedSubmitIndex();
}
