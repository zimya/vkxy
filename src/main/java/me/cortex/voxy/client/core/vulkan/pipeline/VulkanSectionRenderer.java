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
    private final VulkanHiZBuffer hiZBuffer;
    private final VulkanBuffer statisticsBuffer;

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

        // 20 bytes per VkDrawIndexedIndirectCommand
        this.drawCallBuffer = VulkanBuffer.createDeviceStorage(bridge, (long) TOTAL_DRAW_COUNT * 20,
                VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        // Host visible so CPU can read exact opaqueDrawCount after dispatch
        this.drawCountCallBuffer = VulkanBuffer.createHostVisible(bridge, 256,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);

        this.visibilityBuffer = VulkanBuffer.createHostVisible(bridge, (long) maxSections * 4, 0);

        this.indirectLookupBuffer = VulkanBuffer.createHostVisible(bridge, (long) maxSections * 4 + 4, 0);

        this.positionScratchBuffer = VulkanBuffer.createDeviceStorage(bridge, (long) maxSections * 8, 0);

        this.distanceCountBuffer = VulkanBuffer.createDeviceStorage(bridge, 1024L * 4 + (long) TRANSLUCENT_DRAW_COUNT * 4,
                VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        this.statisticsBuffer = VulkanBuffer.createHostVisible(bridge, 256,
                VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);

        Logger.info("VulkanSectionRenderer initialized successfully with Hi-Z Occlusion Culling");
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
        // Visibility is governed by cull_hiz.comp on the GPU
    }

    private int currentOpaqueDrawCount = 0;
    private int currentTranslucentDrawCount = 0;

    public void buildDrawCalls(int sectionCount) {
        if (sectionCount <= 0) {
            this.currentOpaqueDrawCount = 0;
            this.currentTranslucentDrawCount = 0;
            return;
        }

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
                    this.distanceCountBuffer,
                    this.hiZBuffer,
                    this.statisticsBuffer
            );
        } finally {
            this.bridge.endAndSubmitTransientCommandBuffer(cb);
        }

        // Invalidate host cache for drawCountCallBuffer and read exact opaque & translucent draw counts
        this.drawCountCallBuffer.invalidate(0, 64);
        long ptr = this.drawCountCallBuffer.map();
        int actualOpaqueDrawCount = org.lwjgl.system.MemoryUtil.memGetInt(ptr + 12);
        this.currentOpaqueDrawCount = Math.max(0, Math.min(actualOpaqueDrawCount, OPAQUE_DRAW_COUNT));
        int actualTranslucentDrawCount = org.lwjgl.system.MemoryUtil.memGetInt(ptr + 16);
        this.currentTranslucentDrawCount = Math.max(0, Math.min(actualTranslucentDrawCount, TRANSLUCENT_DRAW_COUNT));

        // Read back occlusion culling statistics
        this.statisticsBuffer.invalidate(0, 24);
        long sPtr = this.statisticsBuffer.map();
        this.totalQuads = org.lwjgl.system.MemoryUtil.memGetInt(sPtr);
        this.visibleQuads = org.lwjgl.system.MemoryUtil.memGetInt(sPtr + 4);
        this.culledQuads = org.lwjgl.system.MemoryUtil.memGetInt(sPtr + 8);
        this.totalSections = org.lwjgl.system.MemoryUtil.memGetInt(sPtr + 12);
        this.visibleSections = org.lwjgl.system.MemoryUtil.memGetInt(sPtr + 16);
        this.culledSections = org.lwjgl.system.MemoryUtil.memGetInt(sPtr + 20);
        this.cullPercentage = this.totalQuads > 0 ? (100.0f * (float) this.culledQuads / (float) this.totalQuads) : 0.0f;
    }

    public void clearDrawCalls() {
        this.currentOpaqueDrawCount = 0;
        this.currentTranslucentDrawCount = 0;
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
        if (sectionCount <= 0 || this.currentOpaqueDrawCount <= 0) return;

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
                0L,
                this.currentOpaqueDrawCount
        );
    }

    public void renderTranslucent(VkCommandBuffer cb, long lightmapView, long lightmapSampler, int sectionCount) {
        if (sectionCount <= 0 || this.currentTranslucentDrawCount <= 0) return;

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
                (long) OPAQUE_DRAW_COUNT * 20L,
                this.currentTranslucentDrawCount
        );
    }

    public void free() {
        this.terrainPipeline.free();
        this.culler.free();
        this.viewport.free();
        this.hiZBuffer.free();
        this.statisticsBuffer.free();
        this.drawCallBuffer.free();
        this.drawCountCallBuffer.free();
        this.visibilityBuffer.free();
        this.indirectLookupBuffer.free();
        this.positionScratchBuffer.free();
        this.distanceCountBuffer.free();
    }
}
