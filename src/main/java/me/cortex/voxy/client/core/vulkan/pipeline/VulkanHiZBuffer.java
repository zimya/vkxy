package me.cortex.voxy.client.core.vulkan.pipeline;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.vulkan.bridge.VulkanBridge;
import me.cortex.voxy.client.core.vulkan.compute.VulkanBuffer;
import me.cortex.voxy.client.core.vulkan.compute.VulkanComputePipeline;
import me.cortex.voxy.common.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Map;

/**
 * Hierarchical Z (Hi-Z) Depth Pyramid for GPU & CPU occlusion culling.
 * Generates a full mipmap chain of conservative depth values using compute shaders.
 */
public class VulkanHiZBuffer {
    private final VulkanBridge bridge;
    private final VulkanComputePipeline level0Pipeline;
    private final VulkanComputePipeline downsamplePipeline;

    private int width = 0;
    private int height = 0;
    private int levels = 0;

    private long vkImage = 0;
    private long vmaAllocation = 0;
    private long fullImageView = 0;
    private long[] mipImageViews;
    private long sampler = 0;

    // Coarse CPU readback for hierarchical octree culling
    private int coarseMipLevel = 0;
    private int coarseWidth = 0;
    private int coarseHeight = 0;
    private VulkanBuffer coarseReadbackBuffer;
    private float[] coarseDepthGrid;

    private boolean isReady = false;

    public VulkanHiZBuffer(VulkanBridge bridge) {
        this.bridge = bridge;

        // 1. Level 0 pipeline: downsample scene depth to Hi-Z level 0
        String level0Src = ShaderLoader.parse("voxy:vulkan/hiz/hiz_level0.comp");
        this.level0Pipeline = new VulkanComputePipeline(bridge, "hiz_level0.comp", level0Src, Map.of(),
                new int[]{0, 1},
                new int[]{VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
                8 // push constants: ivec2 dstSize
        );

        // 2. Downsample pipeline: downsample level k-1 to level k
        String downsampleSrc = ShaderLoader.parse("voxy:vulkan/hiz/hiz_downsample.comp");
        this.downsamplePipeline = new VulkanComputePipeline(bridge, "hiz_downsample.comp", downsampleSrc, Map.of(),
                new int[]{0, 1},
                new int[]{VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
                16 // push constants: ivec2 inSize, ivec2 outSize
        );

        createSampler();
        alloc(1, 1);
        this.isReady = false;
    }

    private void createSampler() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK12.VK_FILTER_NEAREST)
                    .minFilter(VK12.VK_FILTER_NEAREST)
                    .mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f)
                    .maxLod(16.0f);

            LongBuffer pSampler = stack.mallocLong(1);
            int res = VK12.vkCreateSampler(this.bridge.getVkDevice(), samplerInfo, null, pSampler);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("Failed to create Hi-Z sampler: " + res);
            }
            this.sampler = pSampler.get(0);
        }
    }

    private void alloc(int newWidth, int newHeight) {
        destroyImageResources();

        this.width = newWidth;
        this.height = newHeight;
        this.levels = (int) Math.floor(Math.log(Math.max(width, height)) / Math.log(2.0)) + 1;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Create image
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK12.VK_IMAGE_TYPE_2D)
                    .format(VK12.VK_FORMAT_R32_SFLOAT)
                    .extent(it -> it.width(width).height(height).depth(1))
                    .mipLevels(levels)
                    .arrayLayers(1)
                    .samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT |
                            VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_GPU_ONLY);

            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            int res = Vma.vmaCreateImage(this.bridge.getVmaAllocator(), imageInfo, allocInfo, pImage, pAlloc, null);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate Hi-Z image: " + res);
            }
            this.vkImage = pImage.get(0);
            this.vmaAllocation = pAlloc.get(0);

            // Create full image view covering all mip levels
            VkImageViewCreateInfo fullViewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(this.vkImage)
                    .viewType(VK12.VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK12.VK_FORMAT_R32_SFLOAT)
                    .subresourceRange(it -> it
                            .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0)
                            .levelCount(levels)
                            .baseArrayLayer(0)
                            .layerCount(1)
                    );

            LongBuffer pView = stack.mallocLong(1);
            res = VK12.vkCreateImageView(this.bridge.getVkDevice(), fullViewInfo, null, pView);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("Failed to create full Hi-Z image view: " + res);
            }
            this.fullImageView = pView.get(0);

            // Create individual mip image views
            this.mipImageViews = new long[this.levels];
            for (int i = 0; i < this.levels; i++) {
                final int mip = i;
                VkImageViewCreateInfo mipViewInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType(VK12.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                        .image(this.vkImage)
                        .viewType(VK12.VK_IMAGE_VIEW_TYPE_2D)
                        .format(VK12.VK_FORMAT_R32_SFLOAT)
                        .subresourceRange(it -> it
                                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                                .baseMipLevel(mip)
                                .levelCount(1)
                                .baseArrayLayer(0)
                                .layerCount(1)
                        );

                res = VK12.vkCreateImageView(this.bridge.getVkDevice(), mipViewInfo, null, pView);
                if (res != VK12.VK_SUCCESS) {
                    throw new RuntimeException("Failed to create Hi-Z mip view " + i + ": " + res);
                }
                this.mipImageViews[i] = pView.get(0);
            }

            // Setup coarse CPU readback buffer (~64x32 resolution)
            this.coarseMipLevel = Math.min(4, this.levels - 1);
            this.coarseWidth = Math.max(1, this.width >> this.coarseMipLevel);
            this.coarseHeight = Math.max(1, this.height >> this.coarseMipLevel);
            this.coarseReadbackBuffer = VulkanBuffer.createHostVisible(this.bridge,
                    (long) this.coarseWidth * this.coarseHeight * 4L,
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            this.coarseDepthGrid = new float[this.coarseWidth * this.coarseHeight];
        }

        Logger.info("Allocated Vulkan Hi-Z buffer: " + this.width + "x" + this.height +
                " (" + this.levels + " levels, coarse=" + this.coarseWidth + "x" + this.coarseHeight + ")");
    }

    public void build(VkCommandBuffer cb, long depthImage, long depthImageView, long depthSampler, int srcWidth, int srcHeight) {
        if (depthImageView == 0 || depthSampler == 0 || srcWidth <= 0 || srcHeight <= 0) return;

        int targetWidth = srcWidth;
        int targetHeight = srcHeight;

        if (this.width != targetWidth || this.height != targetHeight || this.vkImage == 0) {
            alloc(targetWidth, targetHeight);
        }

        // Barrier on scene depth attachment: flush late fragment test writes from tile cache to compute shader
        if (depthImage != 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageMemoryBarrier.Buffer depthBarrier = VkImageMemoryBarrier.calloc(1, stack)
                        .sType(VK12.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .oldLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                        .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                        .image(depthImage)
                        .subresourceRange(it -> it
                                .aspectMask(VK12.VK_IMAGE_ASPECT_DEPTH_BIT)
                                .baseMipLevel(0)
                                .levelCount(1)
                                .baseArrayLayer(0)
                                .layerCount(1)
                        )
                        .srcAccessMask(VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT | VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT)
                        .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT);

                VK12.vkCmdPipelineBarrier(cb,
                        VK12.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK12.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                        VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        0, null, null, depthBarrier);
            }
        }

        // 1. Transition Level 0 to GENERAL
        transitionLayout(cb, this.vkImage, 0, 1,
                VK12.VK_IMAGE_LAYOUT_UNDEFINED, VK12.VK_IMAGE_LAYOUT_GENERAL,
                0, VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

        // 2. Dispatch Level 0 (downsample scene depth to Hi-Z level 0)
        this.level0Pipeline.pushDescriptors(cb, null, new VulkanComputePipeline.ImageBinding[]{
                VulkanComputePipeline.ImageBinding.sampled(0, depthImageView, depthSampler, VK12.VK_IMAGE_LAYOUT_GENERAL),
                VulkanComputePipeline.ImageBinding.storage(1, this.mipImageViews[0])
        });
        this.level0Pipeline.pushConstants(cb, this.width, this.height);
        int groupsX0 = (this.width + 15) / 16;
        int groupsY0 = (this.height + 15) / 16;
        this.level0Pipeline.dispatch(cb, groupsX0, groupsY0, 1);

        // 3. Dispatch subsequent mip levels
        for (int i = 1; i < this.levels; i++) {
            int inW = Math.max(1, this.width >> (i - 1));
            int inH = Math.max(1, this.height >> (i - 1));
            int outW = Math.max(1, this.width >> i);
            int outH = Math.max(1, this.height >> i);

            // Barrier: level i-1 write -> read
            transitionLayout(cb, this.vkImage, i - 1, 1,
                    VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

            // Barrier: level i undefined -> write
            transitionLayout(cb, this.vkImage, i, 1,
                    VK12.VK_IMAGE_LAYOUT_UNDEFINED, VK12.VK_IMAGE_LAYOUT_GENERAL,
                    0, VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

            this.downsamplePipeline.pushDescriptors(cb, null, new VulkanComputePipeline.ImageBinding[]{
                    VulkanComputePipeline.ImageBinding.storage(0, this.mipImageViews[i - 1]),
                    VulkanComputePipeline.ImageBinding.storage(1, this.mipImageViews[i])
            });
            this.downsamplePipeline.pushConstants(cb, inW, inH, outW, outH);
            int gx = (outW + 15) / 16;
            int gy = (outH + 15) / 16;
            this.downsamplePipeline.dispatch(cb, gx, gy, 1);
        }

        // 4. Transition all mips to SHADER_READ_ONLY_OPTIMAL for occlusion culler
        transitionLayout(cb, this.vkImage, 0, this.levels,
                VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

        // 5. Copy coarse mip to host readback buffer for CPU hierarchical octree culling
        if (this.coarseReadbackBuffer != null) {
            transitionLayout(cb, this.vkImage, this.coarseMipLevel, 1,
                    VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK12.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK12.VK_ACCESS_SHADER_READ_BIT, VK12.VK_ACCESS_TRANSFER_READ_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferImageCopy.Buffer copyRegion = VkBufferImageCopy.calloc(1, stack);
                copyRegion.bufferOffset(0);
                copyRegion.bufferRowLength(0);
                copyRegion.bufferImageHeight(0);
                copyRegion.imageSubresource(it -> it
                        .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(this.coarseMipLevel)
                        .baseArrayLayer(0)
                        .layerCount(1)
                );
                copyRegion.imageOffset(it -> it.set(0, 0, 0));
                copyRegion.imageExtent(it -> it.set(this.coarseWidth, this.coarseHeight, 1));

                VK12.vkCmdCopyImageToBuffer(cb, this.vkImage, VK12.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        this.coarseReadbackBuffer.getHandle(), copyRegion);

                VkBufferMemoryBarrier.Buffer bufBarrier = VkBufferMemoryBarrier.calloc(1, stack)
                        .sType(VK12.VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                        .srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK12.VK_ACCESS_HOST_READ_BIT)
                        .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                        .buffer(this.coarseReadbackBuffer.getHandle())
                        .offset(0)
                        .size(this.coarseReadbackBuffer.getSize());

                VK12.vkCmdPipelineBarrier(cb,
                        VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_PIPELINE_STAGE_HOST_BIT,
                        0, null, bufBarrier, null);
            }

            transitionLayout(cb, this.vkImage, this.coarseMipLevel, 1,
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK12.VK_ACCESS_TRANSFER_READ_BIT, VK12.VK_ACCESS_SHADER_READ_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        }

        this.isReady = true;
    }

    public void updateCoarseDepthGrid() {
        if (!this.isReady || this.coarseReadbackBuffer == null) return;
        this.coarseReadbackBuffer.invalidate(0, (long) this.coarseWidth * this.coarseHeight * 4L);
        long ptr = this.coarseReadbackBuffer.map();
        for (int i = 0; i < this.coarseDepthGrid.length; i++) {
            this.coarseDepthGrid[i] = MemoryUtil.memGetFloat(ptr + (long) i * 4L);
        }
    }

    public float sampleCoarseDepth(float u, float v) {
        if (!this.isReady || this.coarseDepthGrid == null || this.coarseWidth <= 0 || this.coarseHeight <= 0) return 0.0f;
        int x = Math.clamp((int) (u * this.coarseWidth), 0, this.coarseWidth - 1);
        int y = Math.clamp((int) (v * this.coarseHeight), 0, this.coarseHeight - 1);
        return this.coarseDepthGrid[y * this.coarseWidth + x];
    }

    private static void transitionLayout(VkCommandBuffer cb, long image, int baseMip, int mipCount,
                                         int oldLayout, int newLayout,
                                         int srcAccess, int dstAccess,
                                         int srcStage, int dstStage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .subresourceRange(it -> it
                            .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(baseMip)
                            .levelCount(mipCount)
                            .baseArrayLayer(0)
                            .layerCount(1)
                    )
                    .srcAccessMask(srcAccess)
                    .dstAccessMask(dstAccess);

            VK12.vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    public long getVkImageView() {
        return this.fullImageView;
    }

    public long getVkSampler() {
        return this.sampler;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public int getLevels() {
        return this.levels;
    }

    public boolean isReady() {
        return this.isReady;
    }

    public int getCoarseWidth() {
        return this.coarseWidth;
    }

    public int getCoarseHeight() {
        return this.coarseHeight;
    }

    public float[] getCoarseDepthGrid() {
        return this.coarseDepthGrid;
    }

    private void destroyImageResources() {
        this.isReady = false;
        if (this.mipImageViews != null) {
            for (long view : this.mipImageViews) {
                if (view != 0) VK12.vkDestroyImageView(this.bridge.getVkDevice(), view, null);
            }
            this.mipImageViews = null;
        }
        if (this.fullImageView != 0) {
            VK12.vkDestroyImageView(this.bridge.getVkDevice(), this.fullImageView, null);
            this.fullImageView = 0;
        }
        if (this.vkImage != 0) {
            Vma.vmaDestroyImage(this.bridge.getVmaAllocator(), this.vkImage, this.vmaAllocation);
            this.vkImage = 0;
            this.vmaAllocation = 0;
        }
        if (this.coarseReadbackBuffer != null) {
            this.coarseReadbackBuffer.free();
            this.coarseReadbackBuffer = null;
        }
    }

    public void free() {
        destroyImageResources();
        if (this.sampler != 0) {
            VK12.vkDestroySampler(this.bridge.getVkDevice(), this.sampler, null);
            this.sampler = 0;
        }
        this.level0Pipeline.free();
        this.downsamplePipeline.free();
    }
}
