package me.cortex.voxy.client.core.vulkan.pipeline;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.vulkan.bridge.VulkanBridge;
import me.cortex.voxy.client.core.vulkan.compute.VulkanBuffer;
import me.cortex.voxy.client.core.vulkan.compute.VulkanComputePipeline;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.HashMap;
import java.util.Map;

public class VulkanComputeCuller {
    public static final int OPAQUE_DRAW_COUNT = 400_000;
    public static final int TRANSLUCENT_DRAW_COUNT = 100_000;
    private static final int TEMPORAL_OFFSET = OPAQUE_DRAW_COUNT + TRANSLUCENT_DRAW_COUNT;

    private final VulkanBridge bridge;
    private final VulkanComputePipeline prepPipeline;
    private final VulkanComputePipeline cmdgenPipeline;

    public VulkanComputeCuller(VulkanBridge bridge) {
        this.bridge = bridge;

        // 1. Prep Pipeline
        String prepSrc = ShaderLoader.parse("voxy:lod/gl46/prep.comp");
        this.prepPipeline = new VulkanComputePipeline(bridge, "prep.comp", prepSrc, Map.of(),
                new int[]{1, 2}, // DRAW_COUNT (1), INDIRECT_LOOKUP (2)
                new int[]{VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER}
        );

        // 2. Cmdgen Pipeline
        String cmdgenSrc = ShaderLoader.parse("voxy:lod/gl46/cmdgen.comp");
        Map<String, String> defines = new HashMap<>();
        defines.put("TRANSLUCENT_WRITE_BASE", "1024");
        defines.put("TEMPORAL_OFFSET", String.valueOf(TEMPORAL_OFFSET));
        defines.put("TRANSLUCENT_DISTANCE_BUFFER_BINDING", "7");

        this.cmdgenPipeline = new VulkanComputePipeline(bridge, "cmdgen.comp", cmdgenSrc, defines,
                new int[]{0, 1, 2, 3, 4, 5, 6, 7},
                new int[]{
                        VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, // 0: SceneUniform
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, // 1: drawCallBuffer
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, // 2: drawCountCallBuffer
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, // 3: sectionMetadataBuffer
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, // 4: visibilityBuffer
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, // 5: indirectLookupBuffer
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, // 6: positionScratchBuffer
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER  // 7: distanceCountBuffer
                }
        );

        Logger.info("VulkanComputeCuller initialized successfully");
    }

    public void dispatchCulling(VkCommandBuffer cb, int sectionCount,
                                VulkanBuffer uniformBuffer,
                                VulkanBuffer drawCallBuffer,
                                VulkanBuffer drawCountBuffer,
                                VulkanBuffer metadataBuffer,
                                VulkanBuffer visibilityBuffer,
                                VulkanBuffer indirectLookupBuffer,
                                VulkanBuffer positionScratchBuffer,
                                VulkanBuffer distanceCountBuffer) {
        if (sectionCount <= 0) return;

        // 1. Zero drawCallBuffer so unused draw calls have instanceCount = 0 (MoltenVK indirect draw fallback)
        VK12.vkCmdFillBuffer(cb, drawCallBuffer.getHandle(), 0, drawCallBuffer.getSize(), 0);

        // Barrier after fillBuffer
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier.Buffer fillBarrier = VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(drawCallBuffer.getHandle())
                    .offset(0)
                    .size(drawCallBuffer.getSize());

            VK12.vkCmdPipelineBarrier(cb,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, fillBarrier, null);
        }

        // 2. Dispatch Prep
        this.prepPipeline.pushBuffers(cb, new VulkanBuffer[]{drawCountBuffer, indirectLookupBuffer});
        this.prepPipeline.dispatch(cb, 1, 1, 1);

        // Barrier between prep and cmdgen
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier.Buffer prepBarrier = VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(drawCountBuffer.getHandle())
                    .offset(0)
                    .size(drawCountBuffer.getSize());

            VK12.vkCmdPipelineBarrier(cb,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, prepBarrier, null);
        }

        // 3. Dispatch Cmdgen
        this.cmdgenPipeline.pushBuffers(cb, new VulkanBuffer[]{
                uniformBuffer, drawCallBuffer, drawCountBuffer, metadataBuffer,
                visibilityBuffer, indirectLookupBuffer, positionScratchBuffer, distanceCountBuffer
        });
        int groupCountX = (sectionCount + 127) / 128;
        this.cmdgenPipeline.dispatch(cb, groupCountX, 1, 1);

        // 4. Barrier from compute write to draw indirect read and vertex shader storage buffer read
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier.Buffer barriers = VkBufferMemoryBarrier.calloc(3, stack);

            // drawCallBuffer barrier
            barriers.get(0)
                    .sType(VK12.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_INDIRECT_COMMAND_READ_BIT)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(drawCallBuffer.getHandle())
                    .offset(0)
                    .size(drawCallBuffer.getSize());

            // positionScratchBuffer barrier
            barriers.get(1)
                    .sType(VK12.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(positionScratchBuffer.getHandle())
                    .offset(0)
                    .size(positionScratchBuffer.getSize());

            // drawCountBuffer barrier (host read)
            barriers.get(2)
                    .sType(VK12.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_HOST_READ_BIT)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(drawCountBuffer.getHandle())
                    .offset(0)
                    .size(drawCountBuffer.getSize());

            VK12.vkCmdPipelineBarrier(cb,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK12.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK12.VK_PIPELINE_STAGE_HOST_BIT,
                    0, null, barriers, null);
        }
    }

    public void free() {
        this.prepPipeline.free();
        this.cmdgenPipeline.free();
    }
}
