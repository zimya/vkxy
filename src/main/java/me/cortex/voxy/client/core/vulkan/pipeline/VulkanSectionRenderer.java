package me.cortex.voxy.client.core.vulkan.pipeline;

import me.cortex.voxy.client.core.vulkan.bridge.VulkanBridge;
import me.cortex.voxy.client.core.vulkan.compute.VulkanBuffer;
import me.cortex.voxy.common.Logger;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

public class VulkanSectionRenderer {
    public static final int OPAQUE_DRAW_COUNT = VulkanComputeCuller.OPAQUE_DRAW_COUNT;
    public static final int TRANSLUCENT_DRAW_COUNT = VulkanComputeCuller.TRANSLUCENT_DRAW_COUNT;
    private static final int TOTAL_DRAW_COUNT = OPAQUE_DRAW_COUNT + TRANSLUCENT_DRAW_COUNT * 2;

    private final VulkanBridge bridge;
    private final VulkanTerrainPipeline terrainPipeline;
    private final VulkanComputeCuller culler;
    private final VulkanIndexBuffer indexBuffer;
    private final VulkanViewport viewport;
    private final VulkanModelStore modelStore;
    private final VulkanSectionGeometryData geometryData;

    public static final int FRAMES_IN_FLIGHT = VulkanBridge.FRAMES_IN_FLIGHT;

    public static class FrameData {
        final VulkanBuffer drawCallBuffer;
        final VulkanBuffer drawCountCallBuffer;
        final VulkanBuffer indirectLookupBuffer;
        final VulkanBuffer positionScratchBuffer;
        final VulkanBuffer distanceCountBuffer;
        final VulkanBuffer statisticsBuffer;
        int maxOpaqueDrawCount = 0;
        int maxTranslucentDrawCount = 0;
        int sectionCount = 0;

        FrameData(VulkanBridge bridge, int maxSections) {
            this.drawCallBuffer = VulkanBuffer.createDeviceStorage(bridge, (long) TOTAL_DRAW_COUNT * 20,
                    VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            this.drawCountCallBuffer = VulkanBuffer.createHostVisible(bridge, 256,
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
            this.indirectLookupBuffer = VulkanBuffer.createHostVisible(bridge, (long) maxSections * 4 + 4, 0);
            this.positionScratchBuffer = VulkanBuffer.createDeviceStorage(bridge, (long) maxSections * 8, 0);
            this.distanceCountBuffer = VulkanBuffer.createDeviceStorage(bridge, 1024L * 4 + (long) TRANSLUCENT_DRAW_COUNT * 4,
                    VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            this.statisticsBuffer = VulkanBuffer.createHostVisible(bridge, 256,
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        }

        void free() {
            this.drawCallBuffer.free();
            this.drawCountCallBuffer.free();
            this.indirectLookupBuffer.free();
            this.positionScratchBuffer.free();
            this.distanceCountBuffer.free();
            this.statisticsBuffer.free();
        }
    }

    private final FrameData[] frames = new FrameData[FRAMES_IN_FLIGHT];
    private int frameIndex = 0;
    private final VulkanBuffer visibilityBuffer;
    private final VulkanHiZBuffer hiZBuffer;

    private int totalQuads = 0;
    private int visibleQuads = 0;
    private int culledQuads = 0;
    private int totalSections = 0;
    private int visibleSections = 0;
    private int culledSections = 0;
    private float cullPercentage = 0.0f;

    public VulkanSectionRenderer(VulkanBridge bridge,
                                 VulkanModelStore modelStore,
                                 VulkanSectionGeometryData geometryData,
                                 int colorFormat,
                                 int depthFormat) {
        this.bridge = bridge;
        this.modelStore = modelStore;
        this.geometryData = geometryData;

        this.terrainPipeline = new VulkanTerrainPipeline(bridge, colorFormat, depthFormat);
        this.culler = new VulkanComputeCuller(bridge);
        this.indexBuffer = VulkanIndexBuffer.getInstance(bridge);
        this.viewport = new VulkanViewport(bridge);
        this.hiZBuffer = new VulkanHiZBuffer(bridge);

        int maxSections = geometryData.getMaxSectionCount();

        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            this.frames[i] = new FrameData(bridge, maxSections);
        }

        this.visibilityBuffer = VulkanBuffer.createHostVisible(bridge, (long) maxSections * 4, 0);

        Logger.info("VulkanSectionRenderer initialized successfully with double-buffered asynchronous Hi-Z Occlusion Culling");
    }

    public VulkanViewport getViewport() {
        return this.viewport;
    }

    public VulkanSectionGeometryData getGeometryData() {
        return this.geometryData;
    }

    public void updateVisibleSections(int[] activeSectionIds, int count) {
        this.frameIndex = (this.frameIndex + 1) % FRAMES_IN_FLIGHT;
        FrameData frame = this.frames[this.frameIndex];
        frame.sectionCount = count;

        // Update indirect lookup buffer for current frame with active section IDs
        long ptr = frame.indirectLookupBuffer.map();
        org.lwjgl.system.MemoryUtil.memPutInt(ptr, count);
        ptr += 4;
        for (int i = 0; i < count; i++) {
            org.lwjgl.system.MemoryUtil.memPutInt(ptr, activeSectionIds[i]);
            ptr += 4;
        }
    }

    public void buildDrawCalls(int sectionCount) {
        FrameData frame = this.frames[this.frameIndex];
        if (sectionCount <= 0) {
            frame.maxOpaqueDrawCount = 0;
            frame.maxTranslucentDrawCount = 0;
            return;
        }

        // Compute maximum potential indirect draws for current active sections
        frame.maxOpaqueDrawCount = Math.min(sectionCount * 7, OPAQUE_DRAW_COUNT);
        frame.maxTranslucentDrawCount = Math.min(sectionCount, TRANSLUCENT_DRAW_COUNT);

        VkCommandBuffer cb = this.bridge.beginTransientCommandBuffer(this.frameIndex);
        try {
            this.culler.dispatchCulling(cb, sectionCount,
                    this.viewport.getUniformBuffer(),
                    frame.drawCallBuffer,
                    frame.drawCountCallBuffer,
                    this.geometryData.getMetadataBuffer(),
                    this.visibilityBuffer,
                    frame.indirectLookupBuffer,
                    frame.positionScratchBuffer,
                    frame.distanceCountBuffer,
                    this.hiZBuffer,
                    frame.statisticsBuffer
            );
        } finally {
            this.bridge.endAndSubmitTransientCommandBuffer(cb, this.frameIndex);
        }

        // Asynchronously read back statistics from the PREVIOUS frame (which has finished GPU work)
        FrameData prevFrame = this.frames[(this.frameIndex + 1) % FRAMES_IN_FLIGHT];
        prevFrame.statisticsBuffer.invalidate(0, 24);
        long sPtr = prevFrame.statisticsBuffer.map();
        this.totalQuads = org.lwjgl.system.MemoryUtil.memGetInt(sPtr);
        this.visibleQuads = org.lwjgl.system.MemoryUtil.memGetInt(sPtr + 4);
        this.culledQuads = org.lwjgl.system.MemoryUtil.memGetInt(sPtr + 8);
        this.totalSections = org.lwjgl.system.MemoryUtil.memGetInt(sPtr + 12);
        this.visibleSections = org.lwjgl.system.MemoryUtil.memGetInt(sPtr + 16);
        this.culledSections = org.lwjgl.system.MemoryUtil.memGetInt(sPtr + 20);
        this.cullPercentage = this.totalQuads > 0 ? (100.0f * (float) this.culledQuads / (float) this.totalQuads) : 0.0f;
    }

    public void clearDrawCalls() {
        FrameData frame = this.frames[this.frameIndex];
        frame.maxOpaqueDrawCount = 0;
        frame.maxTranslucentDrawCount = 0;
        frame.sectionCount = 0;
        this.totalQuads = 0;
        this.visibleQuads = 0;
        this.culledQuads = 0;
        this.totalSections = 0;
        this.visibleSections = 0;
        this.culledSections = 0;
        this.cullPercentage = 0.0f;
    }

    public VulkanHiZBuffer getHiZBuffer() {
        return this.hiZBuffer;
    }

    public int getTotalQuads() {
        return this.totalQuads;
    }

    public int getVisibleQuads() {
        return this.visibleQuads;
    }

    public int getCulledQuads() {
        return this.culledQuads;
    }

    public int getTotalSections() {
        return this.totalSections;
    }

    public int getVisibleSections() {
        return this.visibleSections;
    }

    public int getCulledSections() {
        return this.culledSections;
    }

    public float getCullPercentage() {
        return this.cullPercentage;
    }

    public void renderOpaque(VkCommandBuffer cb, long lightmapView, long lightmapSampler, int sectionCount) {
        FrameData frame = this.frames[this.frameIndex];
        if (sectionCount <= 0 || frame.maxOpaqueDrawCount <= 0) return;

        this.terrainPipeline.bindAndDraw(cb, false,
                this.viewport.getUniformBuffer(),
                this.geometryData.getGeometryBuffer(),
                this.geometryData.getMetadataBuffer(),
                this.modelStore.getModelBuffer(),
                this.modelStore.getModelColourBuffer(),
                frame.positionScratchBuffer,
                this.modelStore.getVkImageView(),
                this.modelStore.getVkSampler(),
                lightmapView,
                lightmapSampler,
                0, 0,
                this.indexBuffer,
                frame.drawCallBuffer,
                0L,
                frame.maxOpaqueDrawCount
        );
    }

    public void renderTranslucent(VkCommandBuffer cb, long lightmapView, long lightmapSampler, int sectionCount) {
        FrameData frame = this.frames[this.frameIndex];
        if (sectionCount <= 0 || frame.maxTranslucentDrawCount <= 0) return;

        this.terrainPipeline.bindAndDraw(cb, true,
                this.viewport.getUniformBuffer(),
                this.geometryData.getGeometryBuffer(),
                this.geometryData.getMetadataBuffer(),
                this.modelStore.getModelBuffer(),
                this.modelStore.getModelColourBuffer(),
                frame.positionScratchBuffer,
                this.modelStore.getVkImageView(),
                this.modelStore.getVkSampler(),
                lightmapView,
                lightmapSampler,
                0, 0,
                this.indexBuffer,
                frame.drawCallBuffer,
                (long) OPAQUE_DRAW_COUNT * 20L,
                frame.maxTranslucentDrawCount
        );
    }

    public void free() {
        this.terrainPipeline.free();
        this.culler.free();
        this.viewport.free();
        this.hiZBuffer.free();
        this.visibilityBuffer.free();
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            if (this.frames[i] != null) {
                this.frames[i].free();
            }
        }
    }
}
