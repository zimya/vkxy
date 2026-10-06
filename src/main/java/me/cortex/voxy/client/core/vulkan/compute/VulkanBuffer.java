package me.cortex.voxy.client.core.vulkan.compute;

import me.cortex.voxy.client.core.vulkan.bridge.VulkanBridge;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * Vulkan buffer allocated via VMA, supporting Storage Buffer, Indirect Buffer, UBO, and Transfer operations.
 */
public class VulkanBuffer implements AutoCloseable {
    private final long vkBuffer;
    private final long allocation;
    private final long size;
    private long mappedAddress = 0;
    private boolean closed = false;

    public VulkanBuffer(long size, int usage, int vmaUsage, int vmaRequiredFlags, int vmaPreferredFlags) {
        this.size = size;
        var bridge = VulkanBridge.instance();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default();
            bufferInfo.size(size);
            bufferInfo.usage(usage);
            bufferInfo.sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack);
            allocInfo.usage(vmaUsage);
            if (vmaRequiredFlags != 0) {
                allocInfo.requiredFlags(vmaRequiredFlags);
            }
            if (vmaPreferredFlags != 0) {
                allocInfo.preferredFlags(vmaPreferredFlags);
            }

            LongBuffer bufferPtr = stack.callocLong(1);
            PointerBuffer allocPtr = stack.callocPointer(1);
            int res = Vma.vmaCreateBuffer(bridge.getVmaAllocator(), bufferInfo, allocInfo, bufferPtr, allocPtr, null);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate Vulkan buffer of size " + size + ", error=" + res);
            }
            this.vkBuffer = bufferPtr.get(0);
            this.allocation = allocPtr.get(0);
        }
    }

    public static VulkanBuffer createDeviceStorage(long size) {
        int usage = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
                | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                | VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        return new VulkanBuffer(size, usage, 8, 0, 0); // 8 = VMA_MEMORY_USAGE_AUTO
    }

    public static VulkanBuffer createDeviceStorage(VulkanBridge bridge, long size, int extraUsage) {
        int usage = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK12.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
                | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT
                | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                | VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
                | extraUsage;
        return new VulkanBuffer(size, usage, 8, 0, 0);
    }

    public static VulkanBuffer createHostVisible(long size, int usage) {
        int fullUsage = usage | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        // requiredFlags: 2 = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
        // preferredFlags: 8 = VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        return new VulkanBuffer(size, fullUsage, 8, 2, 8);
    }

    public static VulkanBuffer createHostVisible(VulkanBridge bridge, long size, int extraUsage) {
        return createHostVisible(size, extraUsage);
    }

    public long vkBuffer() {
        return this.vkBuffer;
    }

    public long getHandle() {
        return this.vkBuffer;
    }

    public long allocation() {
        return this.allocation;
    }

    public long size() {
        return this.size;
    }

    public long getSize() {
        return this.size;
    }

    public synchronized long map() {
        if (this.mappedAddress == 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer mappedPtr = stack.mallocPointer(1);
                int res = Vma.vmaMapMemory(VulkanBridge.instance().getVmaAllocator(), this.allocation, mappedPtr);
                if (res != VK12.VK_SUCCESS) {
                    throw new RuntimeException("Failed to map Vulkan buffer memory, error=" + res);
                }
                this.mappedAddress = mappedPtr.get(0);
            }
        }
        return this.mappedAddress;
    }

    public synchronized void unmap() {
        if (this.mappedAddress != 0) {
            Vma.vmaUnmapMemory(VulkanBridge.instance().getVmaAllocator(), this.allocation);
            this.mappedAddress = 0;
        }
    }

    public void zero(VkCommandBuffer cb) {
        fill(cb, 0, this.size, 0);
    }

    public void fill(VkCommandBuffer cb, long offset, long length, int value) {
        long alignedLen = length & ~3L;
        if (alignedLen > 0) {
            VK12.vkCmdFillBuffer(cb, this.vkBuffer, offset, alignedLen, value);
        }
    }

    public void upload(ByteBuffer data, long dstOffset) {
        long addr = map() + dstOffset;
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), addr, data.remaining());
    }

    public void upload(long dstOffset, MemoryBuffer data) {
        long addr = map() + dstOffset;
        UnsafeUtil.memcpy(data.address, addr, data.size);
    }

    public void free() {
        close();
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            unmap();
            long buf = this.vkBuffer;
            long alloc = this.allocation;
            VulkanBridge.instance().queueForDestroy(() -> {
                Vma.vmaDestroyBuffer(VulkanBridge.instance().getVmaAllocator(), buf, alloc);
            });
        }
    }
}
