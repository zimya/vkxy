package me.cortex.voxy.client.core.vulkan.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import me.cortex.voxy.client.core.model.IModelStore;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.vulkan.bridge.VulkanBridge;
import me.cortex.voxy.client.core.vulkan.compute.VulkanBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.OptionalDouble;

public class VulkanModelStore implements IModelStore {
    public static final int MODEL_SIZE = 64;
    private final VulkanBridge bridge;

    private final VulkanBuffer modelBuffer;
    private final VulkanBuffer modelColourBuffer;
    private final GpuTexture texture;
    private final GpuTextureView textureView;
    private final GpuSampler sampler;

    private final long vkImageView;
    private final long vkSampler;

    private CommandEncoder currentEncoder;

    public VulkanModelStore(VulkanBridge bridge) {
        this.bridge = bridge;

        // Model data buffer (64 bytes * 65536 = 4MB)
        this.modelBuffer = VulkanBuffer.createHostVisible(bridge, (long) MODEL_SIZE * (1 << 16), 0);

        // Biome colour buffer (4 bytes * 65536 = 256KB)
        this.modelColourBuffer = VulkanBuffer.createHostVisible(bridge, 4L * (1 << 16), 0);

        // Model texture atlas
        GpuDevice device = RenderSystem.getDevice();
        int width = ModelFactory.MODEL_TEXTURE_SIZE * 3 * 256;
        int height = ModelFactory.MODEL_TEXTURE_SIZE * 2 * 256;
        int mipLevels = Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE);

        this.texture = device.createTexture(
                () -> "VoxyModelTextures",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                1,
                mipLevels
        );
        this.textureView = device.createTextureView(this.texture);

        int maxMip = ((TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")))
                .maxMipLevel;

        this.sampler = device.createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                OptionalDouble.of(maxMip)
        );

        this.vkImageView = bridge.getVkImageView(this.textureView);
        this.vkSampler = bridge.getVkSampler(this.sampler);

        Logger.info("VulkanModelStore initialized: modelBuffer=" + this.modelBuffer.getSize() +
                ", colorBuffer=" + this.modelColourBuffer.getSize() +
                ", atlas=" + width + "x" + height + " (mips=" + mipLevels + ")");
    }

    public VulkanBuffer getModelBuffer() {
        return this.modelBuffer;
    }

    public VulkanBuffer getModelColourBuffer() {
        return this.modelColourBuffer;
    }

    public GpuTexture getTexture() {
        return this.texture;
    }

    public GpuTextureView getTextureView() {
        return this.textureView;
    }

    public GpuSampler getSampler() {
        return this.sampler;
    }

    public long getVkImageView() {
        return this.vkImageView;
    }

    public long getVkSampler() {
        return this.vkSampler;
    }

    @Override
    public void uploadModel(int modelId, MemoryBuffer data) {
        long dstAddr = this.modelBuffer.map() + (long) modelId * MODEL_SIZE;
        UnsafeUtil.memcpy(data.address, dstAddr, MODEL_SIZE);
    }

    @Override
    public void uploadModelColour(int biomeUploadIndex, MemoryBuffer data) {
        long dstAddr = this.modelColourBuffer.map() + biomeUploadIndex * 4L;
        UnsafeUtil.memcpy(data.address, dstAddr, data.size);
    }

    @Override
    public void uploadTexture(int modelId, MemoryBuffer textureData, boolean hasMips) {
        if (this.currentEncoder == null) {
            this.currentEncoder = RenderSystem.getDevice().createCommandEncoder();
        }
        int X = (modelId & 0xFF) * ModelFactory.MODEL_TEXTURE_SIZE * 3;
        int Y = ((modelId >> 8) & 0xFF) * ModelFactory.MODEL_TEXTURE_SIZE * 2;
        long cAddr = textureData.address;
        int layers = hasMips ? ModelFactory.LAYERS : 1;
        for (int lvl = 0; lvl < layers; lvl++) {
            int w = (ModelFactory.MODEL_TEXTURE_SIZE * 3) >> lvl;
            int h = (ModelFactory.MODEL_TEXTURE_SIZE * 2) >> lvl;
            int size = w * h * 4;
            ByteBuffer buf = MemoryUtil.memByteBuffer(cAddr, size);
            this.currentEncoder.writeToTexture(this.texture, buf, lvl, 0, X >> lvl, Y >> lvl, w, h);
            cAddr += size;
        }
    }

    @Override
    public void uploadBiomes(MemoryBuffer biomeColourBuffer, MemoryBuffer modelBiomeIndexPairs) {
        long clrDst = this.modelColourBuffer.map();
        UnsafeUtil.memcpy(biomeColourBuffer.address, clrDst, biomeColourBuffer.size);

        long mdlDst = this.modelBuffer.map();
        long ptr = modelBiomeIndexPairs.address;
        for (long offset = 0; offset < modelBiomeIndexPairs.size; offset += 8) {
            long v = MemoryUtil.memGetLong(ptr); ptr += 8;
            int modelId = (int) (v & ((1L << 32) - 1));
            int biomeIndex = (int) (v >>> 32);
            MemoryUtil.memPutInt(mdlDst + ((long) modelId * MODEL_SIZE) + 4 * 6 + 4, biomeIndex);
        }
    }

    @Override
    public void flushUploads() {
        if (this.currentEncoder != null) {
            this.currentEncoder.submit();
            this.currentEncoder = null;
        }
    }

    @Override
    public void free() {
        if (this.currentEncoder != null) {
            this.currentEncoder.submit();
            this.currentEncoder = null;
        }
        if (this.modelBuffer != null) this.modelBuffer.free();
        if (this.modelColourBuffer != null) this.modelColourBuffer.free();
        if (this.textureView != null) this.textureView.close();
        if (this.texture != null) this.texture.close();
        if (this.sampler != null) this.sampler.close();
    }
}
