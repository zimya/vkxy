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

    // Viewport buffers
    private final VulkanBuffer drawCallBuffer;
    private final VulkanBuffer drawCountCallBuffer;
    private final VulkanBuffer visibilityBuffer;
    private final VulkanBuffer indirectLookupBuffer;
    private final VulkanBuffer positionScratchBuffer;
    private final VulkanBuffer distanceCountBuffer;

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

        int maxSections = geometryData.getMaxSectionCount();

        // 20 bytes per VkDrawIndexedIndirectCommand
        this.drawCallBuffer = VulkanBuffer.createDeviceStorage(bridge, (long) TOTAL_DRAW_COUNT * 20,
                VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        this.drawCountCallBuffer = VulkanBuffer.createDeviceStorage(bridge, 256,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);

        this.visibilityBuffer = VulkanBuffer.createHostVisible(bridge, (long) maxSections * 4, 0);

        this.indirectLookupBuffer = VulkanBuffer.createHostVisible(bridge, (long) maxSections * 4 + 4, 0);

        this.positionScratchBuffer = VulkanBuffer.createDeviceStorage(bridge, (long) maxSections * 8, 0);

        this.distanceCountBuffer = VulkanBuffer.createDeviceStorage(bridge, 1024L * 4 + (long) TRANSLUCENT_DRAW_COUNT * 4,
                VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        Logger.info("VulkanSectionRenderer initialized successfully");
    }

    public VulkanViewport getViewport() {
        return this.viewport;
    }

    public VulkanSectionGeometryData getGeometryData() {
        return this.geometryData;
    }

    public void updateVisibleSections(int[] activeSectionIds, int count) {
        // Update indirect lookup buffer with active section IDs
        long ptr = this.indirectLookupBuffer.map();
        org.lwjgl.system.MemoryUtil.memPutInt(ptr, count);
        ptr += 4;
        for (int i = 0; i < count; i++) {
            org.lwjgl.system.MemoryUtil.memPutInt(ptr, activeSectionIds[i]);
            ptr += 4;
        }

        // Set visibility data to current frameId so cmdgen renders all active sections
        int frameId = this.viewport.frameId;
        long visPtr = this.visibilityBuffer.map();
        for (int i = 0; i < count; i++) {
            int secId = activeSectionIds[i];
            org.lwjgl.system.MemoryUtil.memPutInt(visPtr + (long) secId * 4L, frameId);
        }
    }

    public void buildDrawCalls(int sectionCount) {
        if (sectionCount <= 0) return;

        VkCommandBuffer cb = this.bridge.beginTransientCommandBuffer();
        try {
            this.culler.dispatchCulling(cb, sectionCount,
                    this.viewport.getUniformBuffer(),
                    this.drawCallBuffer,
                    this.drawCountCallBuffer,
                    this.geometryData.getMetadataBuffer(),
                    this.visibilityBuffer,
                    this.indirectLookupBuffer,
                    this.positionScratchBuffer,
                    this.distanceCountBuffer
            );
        } finally {
            this.bridge.endAndSubmitTransientCommandBuffer(cb);
        }
    }

    public void renderOpaque(VkCommandBuffer cb, long lightmapView, long lightmapSampler, int sectionCount) {
        if (sectionCount <= 0) return;

        int maxDrawCount = Math.min((int) (sectionCount * 7L + 128), OPAQUE_DRAW_COUNT);
        this.terrainPipeline.bindAndDraw(cb, false,
                this.viewport.getUniformBuffer(),
                this.geometryData.getGeometryBuffer(),
                this.geometryData.getMetadataBuffer(),
                this.modelStore.getModelBuffer(),
                this.modelStore.getModelColourBuffer(),
                this.positionScratchBuffer,
                this.modelStore.getVkImageView(),
                this.modelStore.getVkSampler(),
                lightmapView,
                lightmapSampler,
                0, 0,
                this.indexBuffer,
                this.drawCallBuffer,
                maxDrawCount
        );
    }

    public void renderTranslucent(VkCommandBuffer cb, long lightmapView, long lightmapSampler, int sectionCount) {
        if (sectionCount <= 0) return;

        int maxDrawCount = Math.min(sectionCount, TRANSLUCENT_DRAW_COUNT);
        this.terrainPipeline.bindAndDraw(cb, true,
                this.viewport.getUniformBuffer(),
                this.geometryData.getGeometryBuffer(),
                this.geometryData.getMetadataBuffer(),
                this.modelStore.getModelBuffer(),
                this.modelStore.getModelColourBuffer(),
                this.positionScratchBuffer,
                this.modelStore.getVkImageView(),
                this.modelStore.getVkSampler(),
                lightmapView,
                lightmapSampler,
                0, 0,
                this.indexBuffer,
                this.drawCallBuffer,
                maxDrawCount
        );
    }

    public void free() {
        this.terrainPipeline.free();
        this.culler.free();
        this.viewport.free();
        this.drawCallBuffer.free();
        this.drawCountCallBuffer.free();
        this.visibilityBuffer.free();
        this.indirectLookupBuffer.free();
        this.positionScratchBuffer.free();
        this.distanceCountBuffer.free();
    }
}
