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
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkQueue;

import java.util.Objects;

/**
 * Isolated bridge to Minecraft 26.3's RenderPearl Vulkan backend.
 * All Mojang private / internal Vulkan class interactions must remain encapsulated here.
 */
public final class VulkanBridge {
    private static VulkanBridge INSTANCE;

    private final FrontendGpuDevice frontendDevice;
    private final VulkanDevice vulkanDevice;

    private VulkanBridge(FrontendGpuDevice frontendDevice, VulkanDevice vulkanDevice) {
        this.frontendDevice = Objects.requireNonNull(frontendDevice, "frontendDevice");
        this.vulkanDevice = Objects.requireNonNull(vulkanDevice, "vulkanDevice");
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

    public FrontendGpuDevice getFrontendDevice() {
        return this.frontendDevice;
    }

    public VulkanDevice getVulkanDevice() {
        return this.vulkanDevice;
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

    public VkCommandBuffer allocateTransientCommandBuffer() {
        return this.getCommandEncoder().allocateAndBeginTransientCommandBuffer();
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
            return vtv.vkImageView();
        }
        throw new IllegalArgumentException("TextureView is not a VulkanGpuTextureView: " + (view == null ? "null" : view.getClass().getName()));
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

    public void queueForDestroy(Runnable cleanup) {
        this.getCommandEncoder().queueForDestroy(cleanup::run);
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
}
