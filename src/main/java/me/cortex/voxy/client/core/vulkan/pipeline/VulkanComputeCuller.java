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
    private final VulkanComputePipeline prefixSumPipeline;
    private final VulkanComputePipeline buildTranslucentsPipeline;

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

        // 3. Prefix Sum Pipeline (for distance radix sorting)
        String prefixSumSrc = ShaderLoader.parse("voxy:util/prefixsum/simple.comp");
        this.prefixSumPipeline = new VulkanComputePipeline(bridge, "simple.comp", prefixSumSrc, Map.of("IO_BUFFER", "0"),
                new int[]{0},
                new int[]{VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER}
        );

        // 4. Build Translucents Pipeline (places sorted indirect draw commands)
        String buildTransSrc = ShaderLoader.parse("voxy:lod/gl46/buildtranslucents.comp");
        Map<String, String> transDefines = new HashMap<>();
        transDefines.put("TRANSLUCENT_WRITE_BASE", "1024");
        transDefines.put("TRANSLUCENT_DISTANCE_BUFFER_BINDING", "5");
        transDefines.put("TRANSLUCENT_OFFSET", String.valueOf(OPAQUE_DRAW_COUNT));
        this.buildTranslucentsPipeline = new VulkanComputePipeline(bridge, "buildtranslucents.comp", buildTransSrc, transDefines,
                new int[]{0, 1, 2, 3, 4, 5},
                new int[]{
                        VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, // 0: SceneUniform
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, // 1: drawCallBuffer
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, // 2: drawCountCallBuffer
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, // 3: sectionMetadataBuffer
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, // 4: indirectLookupBuffer
                        VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER  // 5: distanceCountBuffer
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

        // 1. Zero drawCallBuffer and distance histogram bins (MoltenVK indirect draw fallback)
        VK12.vkCmdFillBuffer(cb, drawCallBuffer.getHandle(), 0, drawCallBuffer.getSize(), 0);
        VK12.vkCmdFillBuffer(cb, distanceCountBuffer.getHandle(), 0, 1024L * 4, 0);

        // Barrier after fillBuffer
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier.Buffer fillBarriers = VkBufferMemoryBarrier.calloc(2, stack);
            fillBarriers.get(0)
                    .sType(VK12.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(drawCallBuffer.getHandle())
                    .offset(0)
                    .size(drawCallBuffer.getSize());

            fillBarriers.get(1)
                    .sType(VK12.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(distanceCountBuffer.getHandle())
                    .offset(0)
                    .size(1024L * 4);

            VK12.vkCmdPipelineBarrier(cb,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, fillBarriers, null);
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

        // Barrier between cmdgen and prefixSum: distanceCountBuffer write -> compute read/write
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(distanceCountBuffer.getHandle())
                    .offset(0)
                    .size(1024L * 4);

            VK12.vkCmdPipelineBarrier(cb,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, barrier, null);
        }

        // 4. Dispatch Prefix Sum (distance radix sorting)
        this.prefixSumPipeline.pushBuffers(cb, new VulkanBuffer[]{distanceCountBuffer});
        this.prefixSumPipeline.dispatch(cb, 1, 1, 1);

        // Barrier between prefixSum and buildTranslucents: distanceCountBuffer & drawCountBuffer -> compute read/write
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(distanceCountBuffer.getHandle())
                    .offset(0)
                    .size(distanceCountBuffer.getSize());

            VK12.vkCmdPipelineBarrier(cb,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, barrier, null);
        }

        // 5. Dispatch Build Translucents (writes sorted indirect draw commands)
        this.buildTranslucentsPipeline.pushBuffers(cb, new VulkanBuffer[]{
                uniformBuffer, drawCallBuffer, drawCountBuffer, metadataBuffer,
                indirectLookupBuffer, distanceCountBuffer
        });
        this.buildTranslucentsPipeline.dispatch(cb, groupCountX, 1, 1);

        // 6. Barrier from compute write to draw indirect read, vertex shader storage buffer read, and host read
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
        this.prefixSumPipeline.free();
        this.buildTranslucentsPipeline.free();
    }
}
