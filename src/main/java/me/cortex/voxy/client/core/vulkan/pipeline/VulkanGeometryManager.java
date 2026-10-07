package me.cortex.voxy.client.core.vulkan.pipeline;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryManager;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.HierarchicalBitSet;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.lwjgl.system.MemoryUtil;

import java.util.function.Consumer;

public class VulkanGeometryManager implements IGeometryManager {
    public static final int SECTION_METADATA_SIZE = 32;

    private final VulkanSectionGeometryData geometryData;
    private final HierarchicalBitSet allocationSet;
    private final AllocationArena allocationHeap;
    private final Int2LongOpenHashMap sectionQuadAllocations = new Int2LongOpenHashMap();
    private final int maxSectionCount;
    private final long geometryCapacityBytes;
    private long allocatedQuads = 0;
    private long lastOomLogTime = 0;

    public VulkanGeometryManager(VulkanSectionGeometryData geometryData) {
        this.geometryData = geometryData;
        this.maxSectionCount = geometryData.getMaxSectionCount();
        this.geometryCapacityBytes = geometryData.getGeometryCapacityBytes();

        this.allocationSet = new HierarchicalBitSet(this.maxSectionCount);
        // Reserve section ID 0 so that 0 is never allocated to a valid section
        this.allocationSet.allocateNext();

        this.allocationHeap = new AllocationArena();
        this.allocationHeap.setLimit(this.geometryCapacityBytes / 8L); // Allocation in quads (8 bytes each)

        // Zero index 0 in metadata
        long metaAddr = this.geometryData.getMetadataBuffer().map();
        MemoryUtil.memSet(metaAddr, 0, SECTION_METADATA_SIZE);
    }

    @Override
    public int uploadSection(BuiltSection built) {
        if (built == null || built.isEmpty()) {
            if (built != null) built.free();
            return NodeManager.EMPTY_GEOMETRY_ID;
        }

        long quadCount = built.geometryBuffer.size / 8L;
        int upsized = (int) ((quadCount + 127L) & ~127L);

        long quadAddr = this.allocationHeap.alloc(upsized);
        if (quadAddr == -1) {
            long now = System.currentTimeMillis();
            if (now - this.lastOomLogTime > 3000) {
                this.lastOomLogTime = now;
                Logger.warn("Vulkan geometry buffer out of memory! Capacity: " + (this.geometryCapacityBytes / (1024 * 1024)) +
                        "MB, used: " + (this.getAllocatedBytes() / (1024 * 1024)) + "MB (" + this.getSectionCount() + " sections)");
            }
            built.free();
            return NodeManager.EMPTY_GEOMETRY_ID;
        }

        int secId = this.allocationSet.allocateNext();
        if (secId == HierarchicalBitSet.SET_FULL || secId == -1) {
            long now = System.currentTimeMillis();
            if (now - this.lastOomLogTime > 3000) {
                this.lastOomLogTime = now;
                Logger.warn("Vulkan section allocation full! Max sections: " + this.maxSectionCount);
            }
            this.allocationHeap.free(quadAddr);
            built.free();
            return NodeManager.EMPTY_GEOMETRY_ID;
        }

        this.allocatedQuads += upsized;
        this.sectionQuadAllocations.put(secId, (quadAddr << 32) | (long) upsized);

        // Copy geometry quads to geometryBuffer
        long dstAddr = this.geometryData.getGeometryBuffer().map() + quadAddr * 8L;
        UnsafeUtil.memcpy(built.geometryBuffer.address, dstAddr, built.geometryBuffer.size);

        // Write SectionMeta (32 bytes)
        long metaAddr = this.geometryData.getMetadataBuffer().map() + (long) secId * 32L;

        // uvec4 a:
        // x: posA (position >> 32), y: posB (position), z: aabb, w: quadStart
        MemoryUtil.memPutInt(metaAddr, (int) (built.position >> 32));
        MemoryUtil.memPutInt(metaAddr + 4, (int) built.position);
        MemoryUtil.memPutInt(metaAddr + 8, built.aabb);
        MemoryUtil.memPutInt(metaAddr + 12, (int) quadAddr + (built.offsets != null ? built.offsets[0] : 0));

        // uvec4 b: face counts
        int[] offsets = built.offsets;
        if (offsets != null && offsets.length >= 8) {
            int cntTranslucent = offsets[1] - offsets[0];
            int cntDoubleSided = offsets[2] - offsets[1];
            int cntDown = offsets[3] - offsets[2];
            int cntUp = offsets[4] - offsets[3];
            int cntNorth = offsets[5] - offsets[4];
            int cntSouth = offsets[6] - offsets[5];
            int cntWest = offsets[7] - offsets[6];
            int cntEast = (int) quadCount - offsets[7];

            MemoryUtil.memPutInt(metaAddr + 16, (cntTranslucent & 0xFFFF) | ((cntDoubleSided & 0xFFFF) << 16));
            MemoryUtil.memPutInt(metaAddr + 20, (cntDown & 0xFFFF) | ((cntUp & 0xFFFF) << 16));
            MemoryUtil.memPutInt(metaAddr + 24, (cntNorth & 0xFFFF) | ((cntSouth & 0xFFFF) << 16));
            MemoryUtil.memPutInt(metaAddr + 28, (cntWest & 0xFFFF) | ((cntEast & 0xFFFF) << 16));
        } else {
            MemoryUtil.memSet(metaAddr + 16, 0, 16);
        }

        this.geometryData.setSectionCount(this.allocationSet.getCount() - 1);
        built.free();
        return secId;
    }

    @Override
    public int uploadReplaceSection(int oldId, BuiltSection section) {
        if (oldId > 0) {
            this.removeSection(oldId);
        }
        return this.uploadSection(section);
    }

    @Override
    public void removeSection(int id) {
        if (id <= 0) return;

        long alloc = this.sectionQuadAllocations.remove(id);
        if (alloc != 0) {
            long quadAddr = alloc >>> 32;
            int size = (int) alloc;
            this.allocatedQuads = Math.max(0, this.allocatedQuads - size);
            this.allocationHeap.free(quadAddr);
        }

        this.allocationSet.free(id);

        long metaAddr = this.geometryData.getMetadataBuffer().map() + (long) id * 32L;
        MemoryUtil.memSet(metaAddr, 0, SECTION_METADATA_SIZE);

        this.geometryData.setSectionCount(this.allocationSet.getCount() - 1);
    }

    @Override
    public void downloadAndRemove(int id, Consumer<BuiltSection> callback) {
        this.removeSection(id);
    }

    public int getSectionCount() {
        return Math.max(0, this.allocationSet.getCount() - 1);
    }

    public long getAllocatedBytes() {
        return this.allocatedQuads * 8L;
    }

    public long getCapacityBytes() {
        return this.geometryCapacityBytes;
    }

    public long getFreeBytes() {
        return Math.max(0, this.geometryCapacityBytes - this.getAllocatedBytes());
    }

    public void free() {
        this.sectionQuadAllocations.clear();
        this.allocatedQuads = 0;
        this.allocationHeap.reset();
    }
}
