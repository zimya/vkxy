package me.cortex.voxy.client.core.vulkan.pipeline;

import me.cortex.voxy.client.core.vulkan.bridge.VulkanBridge;
import me.cortex.voxy.client.core.vulkan.compute.VulkanBuffer;
import me.cortex.voxy.common.Logger;

public class VulkanSectionGeometryData {
    public static final int SECTION_METADATA_SIZE = 32;

    private final VulkanBuffer sectionMetadataBuffer;
    private final VulkanBuffer geometryBuffer;
    private final int maxSectionCount;
    private int currentSectionCount;

    public VulkanSectionGeometryData(VulkanBridge bridge, int maxSectionCount, long geometryCapacity) {
        this.maxSectionCount = maxSectionCount;
        this.sectionMetadataBuffer = VulkanBuffer.createHostVisible(bridge, (long) maxSectionCount * SECTION_METADATA_SIZE, 0);
        this.geometryBuffer = VulkanBuffer.createHostVisible(bridge, geometryCapacity, 0);
        Logger.info("VulkanSectionGeometryData initialized: maxSections=" + maxSectionCount + ", geometryCapacity=" + (geometryCapacity / (1024 * 1024)) + "MB");
    }

    public VulkanBuffer getMetadataBuffer() {
        return this.sectionMetadataBuffer;
    }

    public VulkanBuffer getGeometryBuffer() {
        return this.geometryBuffer;
    }

    public int getSectionCount() {
        return this.currentSectionCount;
    }

    public void setSectionCount(int count) {
        this.currentSectionCount = count;
    }

    public int getMaxSectionCount() {
        return this.maxSectionCount;
    }

    public long getGeometryCapacityBytes() {
        return this.geometryBuffer.getSize();
    }

    public void free() {
        if (this.sectionMetadataBuffer != null) {
            this.sectionMetadataBuffer.free();
        }
        if (this.geometryBuffer != null) {
            this.geometryBuffer.free();
        }
    }
}
