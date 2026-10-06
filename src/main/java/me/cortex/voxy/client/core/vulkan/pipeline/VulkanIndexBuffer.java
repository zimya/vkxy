package me.cortex.voxy.client.core.vulkan.pipeline;

import me.cortex.voxy.client.core.vulkan.bridge.VulkanBridge;
import me.cortex.voxy.client.core.vulkan.compute.VulkanBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

public class VulkanIndexBuffer {
    private static VulkanIndexBuffer INSTANCE;

    private final VulkanBuffer buffer;
    private final int indexCount;

    public VulkanIndexBuffer(VulkanBridge bridge) {
        // 16380 quads * 6 indices * 2 bytes/index + 36 bytes for cube
        long size = (1L << 16) * 6 * 2 + 6 * 2 * 3;
        this.buffer = VulkanBuffer.createHostVisible(bridge, size, VK12.VK_BUFFER_USAGE_INDEX_BUFFER_BIT);

        MemoryBuffer quadIndices = generateQuadIndicesShort(16380);
        this.buffer.upload(0, quadIndices);
        quadIndices.free();

        this.indexCount = 16380 * 6;
        Logger.info("VulkanIndexBuffer initialized with " + this.indexCount + " indices (" + size + " bytes)");
    }

    public static synchronized VulkanIndexBuffer getInstance(VulkanBridge bridge) {
        if (INSTANCE == null) {
            INSTANCE = new VulkanIndexBuffer(bridge);
        }
        return INSTANCE;
    }

    public void bind(VkCommandBuffer cb) {
        VK12.vkCmdBindIndexBuffer(cb, this.buffer.getHandle(), 0, VK12.VK_INDEX_TYPE_UINT16);
    }

    public VulkanBuffer getBuffer() {
        return this.buffer;
    }

    public void free() {
        if (this.buffer != null) {
            this.buffer.free();
        }
    }

    private static MemoryBuffer generateQuadIndicesShort(int quadCount) {
        if (quadCount > 16383) {
            throw new IllegalArgumentException("Quad count too large");
        }
        MemoryBuffer buffer = new MemoryBuffer(quadCount * 6L * 2);
        long ptr = buffer.address;
        for (int i = 0; i < quadCount * 4; i += 4) {
            org.lwjgl.system.MemoryUtil.memPutShort(ptr + (0 * 2), (short) (i + 1));
            org.lwjgl.system.MemoryUtil.memPutShort(ptr + (1 * 2), (short) (i + 2));
            org.lwjgl.system.MemoryUtil.memPutShort(ptr + (2 * 2), (short) (i + 0));
            org.lwjgl.system.MemoryUtil.memPutShort(ptr + (3 * 2), (short) (i + 1));
            org.lwjgl.system.MemoryUtil.memPutShort(ptr + (4 * 2), (short) (i + 3));
            org.lwjgl.system.MemoryUtil.memPutShort(ptr + (5 * 2), (short) (i + 2));
            ptr += 6 * 2;
        }
        return buffer;
    }
}
