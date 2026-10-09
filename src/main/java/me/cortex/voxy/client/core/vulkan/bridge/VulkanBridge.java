package me.cortex.voxy.client.core.vulkan.bridge;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.Destroyable;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import me.cortex.voxy.client.mixin.vulkan.AccessorFrontendGpuDevice;
import me.cortex.voxy.client.mixin.vulkan.AccessorFrontendRenderPass;
import me.cortex.voxy.client.mixin.vulkan.AccessorVulkanCommandEncoder;
import me.cortex.voxy.client.mixin.vulkan.AccessorVulkanRenderPass;
import me.cortex.voxy.common.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkQueue;

import java.nio.LongBuffer;
import java.util.Objects;

/**
 * Isolated bridge to Minecraft 26.3's RenderPearl Vulkan backend.
 * All Mojang private / internal Vulkan class interactions must remain encapsulated here.
 */
public final class VulkanBridge {
    private static VulkanBridge INSTANCE;

    private final FrontendGpuDevice frontendDevice;
    public static final int FRAMES_IN_FLIGHT = 2;
    private final VulkanDevice vulkanDevice;
    private final long[] transientCommandPools = new long[FRAMES_IN_FLIGHT];
    private final long[] transientFences = new long[FRAMES_IN_FLIGHT];

    private void ensureTransientCommandPool() {
        if (this.transientCommandPools[0] == 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                        .sType(VK12.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                        .queueFamilyIndex(this.vulkanDevice.graphicsQueue().queueFamilyIndex())
                        .flags(VK12.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
                LongBuffer pPool = stack.callocLong(1);

                org.lwjgl.vulkan.VkFenceCreateInfo fenceInfo = org.lwjgl.vulkan.VkFenceCreateInfo.calloc(stack)
                        .sType(VK12.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                        .flags(VK12.VK_FENCE_CREATE_SIGNALED_BIT);
                LongBuffer pFence = stack.callocLong(1);

                for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                    int res = VK12.vkCreateCommandPool(this.vulkanDevice.vkDevice(), poolInfo, null, pPool);
                    if (res != VK12.VK_SUCCESS) {
                        throw new RuntimeException("Failed to create transient VkCommandPool: " + res);
                    }
                    this.transientCommandPools[i] = pPool.get(0);

                    res = VK12.vkCreateFence(this.vulkanDevice.vkDevice(), fenceInfo, null, pFence);
                    if (res != VK12.VK_SUCCESS) {
                        throw new RuntimeException("Failed to create transient VkFence: " + res);
                    }
                    this.transientFences[i] = pFence.get(0);
                }
            }
        }
    }

    private VulkanBridge(FrontendGpuDevice frontendDevice, VulkanDevice vulkanDevice) {
        this.frontendDevice = Objects.requireNonNull(frontendDevice, "frontendDevice");
        this.vulkanDevice = Objects.requireNonNull(vulkanDevice, "vulkanDevice");
        ensureTransientCommandPool();
    }

    public static boolean isVulkanDevice(GpuDevice device) {
        if (device instanceof FrontendGpuDevice frontend) {
            return ((AccessorFrontendGpuDevice) frontend).voxy$getBackend() instanceof VulkanDevice;
        }
        return false;
    }

    public static synchronized boolean initialize(GpuDevice device) {
        if (!isVulkanDevice(device)) {
            INSTANCE = null;
            return false;
        }
        FrontendGpuDevice frontend = (FrontendGpuDevice) device;
        VulkanDevice backend = (VulkanDevice) ((AccessorFrontendGpuDevice) frontend).voxy$getBackend();
        INSTANCE = new VulkanBridge(frontend, backend);
        Logger.info("VulkanBridge initialized successfully: device=" + backend.getDeviceInfo().name());
        return true;
    }

    public static boolean isAvailable() {
        return INSTANCE != null;
    }

    public static VulkanBridge instance() {
        if (INSTANCE == null) {
            throw new IllegalStateException("VulkanBridge has not been initialized or active device is not Vulkan");
        }
        return INSTANCE;
    }

    public static VulkanBridge getInstance() {
        return instance();
    }

    public FrontendGpuDevice getFrontendDevice() {
        return this.frontendDevice;
    }

    public VulkanDevice getVulkanDevice() {
        return this.vulkanDevice;
    }

    public VkDevice getDevice() {
        return getVkDevice();
    }

    public VkDevice getVkDevice() {
        return this.vulkanDevice.vkDevice();
    }

    public VkInstance getVkInstance() {
        return this.vulkanDevice.instance().vkInstance();
    }

    public long getVmaAllocator() {
        return this.vulkanDevice.vma();
    }

    public VkQueue getGraphicsQueue() {
        return this.vulkanDevice.graphicsQueue().vkQueue();
    }

    public int getGraphicsQueueFamily() {
        return this.vulkanDevice.graphicsQueue().queueFamilyIndex();
    }

    public VkQueue getComputeQueue() {
        return this.vulkanDevice.computeQueue().vkQueue();
    }

    public int getComputeQueueFamily() {
        return this.vulkanDevice.computeQueue().queueFamilyIndex();
    }

    public VkQueue getTransferQueue() {
        return this.vulkanDevice.transferQueue().vkQueue();
    }

    public int getTransferQueueFamily() {
        return this.vulkanDevice.transferQueue().queueFamilyIndex();
    }

    public VulkanCommandEncoder getCommandEncoder() {
        CommandEncoder encoder = this.frontendDevice.createCommandEncoder();
        if (encoder instanceof FrontendCommandEncoder fce) {
            return (VulkanCommandEncoder) fce.backend();
        }
        return this.vulkanDevice.createCommandEncoder();
    }

    public static boolean isVulkanRenderPass(RenderPass renderPass) {
        if (renderPass instanceof FrontendRenderPass frp) {
            return ((AccessorFrontendRenderPass) frp).voxy$getBackend() instanceof VulkanRenderPass;
        }
        return false;
    }

    public VkCommandBuffer getRenderPassCommandBuffer(RenderPass renderPass) {
        if (renderPass instanceof FrontendRenderPass frp) {
            var backend = ((AccessorFrontendRenderPass) frp).voxy$getBackend();
            if (backend instanceof VulkanRenderPass vrp) {
                return ((AccessorVulkanRenderPass) vrp).voxy$getCommandBuffer();
            }
        }
        throw new IllegalArgumentException("RenderPass is not a VulkanRenderPass: " + renderPass.getClass().getName());
    }

    public VkCommandBuffer extractCommandBuffer(RenderPass renderPass) {
        return getRenderPassCommandBuffer(renderPass);
    }

    public VkCommandBuffer allocateTransientCommandBuffer() {
        return beginTransientCommandBuffer();
    }

    public VkCommandBuffer beginTransientCommandBuffer(int frameIndex) {
        ensureTransientCommandPool();
        int idx = Math.floorMod(frameIndex, FRAMES_IN_FLIGHT);
        long fence = this.transientFences[idx];
        // Wait for fence from previous cycle of this frame index (already signaled after 1 frame)
        VK12.vkWaitForFences(this.vulkanDevice.vkDevice(), fence, true, 1_000_000_000L);
        VK12.vkResetFences(this.vulkanDevice.vkDevice(), fence);
        VK12.vkResetCommandPool(this.vulkanDevice.vkDevice(), this.transientCommandPools[idx], 0);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(this.transientCommandPools[idx])
                    .level(VK12.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCb = stack.callocPointer(1);
            int res = VK12.vkAllocateCommandBuffers(this.vulkanDevice.vkDevice(), allocInfo, pCb);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate transient VkCommandBuffer: " + res);
            }
            VkCommandBuffer cb = new VkCommandBuffer(pCb.get(0), this.vulkanDevice.vkDevice());

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK12.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            res = VK12.vkBeginCommandBuffer(cb, beginInfo);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("Failed to begin transient VkCommandBuffer: " + res);
            }
            return cb;
        }
    }

    public void endAndSubmitTransientCommandBuffer(VkCommandBuffer cb, int frameIndex) {
        int res = VK12.vkEndCommandBuffer(cb);
        if (res != VK12.VK_SUCCESS) {
            throw new RuntimeException("Failed to end transient VkCommandBuffer: " + res);
        }

        int idx = Math.floorMod(frameIndex, FRAMES_IN_FLIGHT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkSubmitInfo2.Buffer submitInfo = org.lwjgl.vulkan.VkSubmitInfo2.calloc(1, stack).sType$Default();
            org.lwjgl.vulkan.VkCommandBufferSubmitInfo.Buffer cbInfo = org.lwjgl.vulkan.VkCommandBufferSubmitInfo.calloc(1, stack).sType$Default();
            cbInfo.commandBuffer(cb);
            submitInfo.pCommandBufferInfos(cbInfo);

            org.lwjgl.vulkan.KHRSynchronization2.vkQueueSubmit2KHR(
                    this.vulkanDevice.graphicsQueue().vkQueue(),
                    submitInfo,
                    this.transientFences[idx]
            );
        }
    }

    public VkCommandBuffer beginTransientCommandBuffer() {
        return beginTransientCommandBuffer(0);
    }

    public void endAndSubmitTransientCommandBuffer(VkCommandBuffer cb) {
        endAndSubmitTransientCommandBuffer(cb, 0);
    }

    public VkCommandBuffer getOrCreateActiveCommandBuffer() {
        return ((AccessorVulkanCommandEncoder) this.getCommandEncoder()).voxy$invokeCommandBuffer();
    }

    public void endActiveCommandBuffer() {
        ((AccessorVulkanCommandEncoder) this.getCommandEncoder()).voxy$invokeEndCommandBuffer();
    }

    public void executeCommandBuffer(VkCommandBuffer commandBuffer) {
        this.getCommandEncoder().execute(commandBuffer);
    }

    public VkCommandBuffer allocateAndBeginCommandEncoderTransientBuffer() {
        return ((VulkanCommandEncoder) this.getCommandEncoder()).allocateAndBeginTransientCommandBuffer();
    }

    public long getVkBuffer(GpuBuffer buffer) {
        if (buffer instanceof VulkanGpuBuffer vb) {
            return vb.vkBuffer();
        }
        throw new IllegalArgumentException("Buffer is not a VulkanGpuBuffer: " + (buffer == null ? "null" : buffer.getClass().getName()));
    }

    public long getVkBuffer(GpuBufferSlice slice) {
        return this.getVkBuffer(slice.buffer());
    }

    public long getBufferOffset(GpuBufferSlice slice) {
        return slice.offset();
    }

    public long getVkImage(GpuTexture texture) {
        if (texture instanceof VulkanGpuTexture vt) {
            return vt.vkImage();
        }
        throw new IllegalArgumentException("Texture is not a VulkanGpuTexture: " + (texture == null ? "null" : texture.getClass().getName()));
    }

    public long getVkImage(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vtv) {
            return vtv.texture().vkImage();
        }
        throw new IllegalArgumentException("TextureView is not a VulkanGpuTextureView: " + (view == null ? "null" : view.getClass().getName()));
    }

    public long getVkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vtv) {
            return ((me.cortex.voxy.client.mixin.vulkan.AccessorVulkanGpuTextureView) vtv).voxy$getVkImageView();
        }
        throw new IllegalArgumentException("TextureView is not a VulkanGpuTextureView: " + (view == null ? "null" : view.getClass().getName()));
    }

    public long getVkSampler(com.mojang.renderpearl.api.textures.GpuSampler sampler) {
        if (sampler instanceof com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler vs) {
            return ((me.cortex.voxy.client.mixin.vulkan.AccessorVulkanGpuSampler) vs).voxy$getVkSampler();
        }
        throw new IllegalArgumentException("Sampler is not a VulkanGpuSampler: " + (sampler == null ? "null" : sampler.getClass().getName()));
    }

    public long getPipelineHandle(CompiledRenderPipeline pipeline, boolean withDepth) {
        if (pipeline instanceof FrontendRenderPipeline frp && frp.backendRenderPipeline() instanceof VulkanRenderPipeline vrp) {
            return withDepth ? vrp.withDepthPipeline() : vrp.withoutDepthPipeline();
        }
        throw new IllegalArgumentException("Pipeline is not a VulkanRenderPipeline: " + (pipeline == null ? "null" : pipeline.getClass().getName()));
    }

    public long getPipelineLayoutHandle(CompiledRenderPipeline pipeline) {
        if (pipeline instanceof FrontendRenderPipeline frp && frp.backendRenderPipeline() instanceof VulkanRenderPipeline vrp) {
            return vrp.pipelineLayout();
        }
        throw new IllegalArgumentException("Pipeline is not a VulkanRenderPipeline: " + (pipeline == null ? "null" : pipeline.getClass().getName()));
    }

    public void queueForDestroy(Destroyable destroyable) {
        this.getCommandEncoder().queueForDestroy(destroyable);
    }

    public long getCurrentSubmitIndex() {
        return ((AccessorVulkanCommandEncoder) this.getCommandEncoder()).voxy$getCurrentSubmitIndex();
    }

    public long getCompletedSubmitIndex() {
        return ((AccessorVulkanCommandEncoder) this.getCommandEncoder()).voxy$getCompletedSubmitIndex();
    }

    public void waitIdle() {
        this.vulkanDevice.graphicsQueue().waitIdle();
        this.vulkanDevice.computeQueue().waitIdle();
        this.vulkanDevice.transferQueue().waitIdle();
    }

    public void destroy() {
        waitIdle();
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            if (this.transientFences[i] != 0) {
                VK12.vkDestroyFence(this.vulkanDevice.vkDevice(), this.transientFences[i], null);
                this.transientFences[i] = 0;
            }
            if (this.transientCommandPools[i] != 0) {
                VK12.vkDestroyCommandPool(this.vulkanDevice.vkDevice(), this.transientCommandPools[i], null);
                this.transientCommandPools[i] = 0;
            }
        }
    }
}
