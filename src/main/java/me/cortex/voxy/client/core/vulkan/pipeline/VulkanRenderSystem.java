package me.cortex.voxy.client.core.vulkan.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.vulkan.bridge.VulkanBridge;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Arrays;
import java.util.List;

public class VulkanRenderSystem implements IVoxyRenderSystem {
    private final WorldEngine worldIn;
    private final VulkanBridge bridge;
    private final VulkanModelStore modelStore;
    private final ModelBakerySubsystem modelService;
    private final VulkanSectionGeometryData geometryData;
    private final VulkanSectionRenderer sectionRenderer;
    private final RenderGenerationService renderGen;
    private final RenderDistanceTracker renderDistanceTracker;

    // Section allocation and tracking
    private final Long2IntOpenHashMap posToSectionId = new Long2IntOpenHashMap();
    private final IntArrayList activeSectionIds = new IntArrayList();
    private int nextSectionId = 1; // 1-indexed to keep 0 as invalid
    private long nextQuadOffset = 0; // Quads offset in geometry buffer (in units of 8 bytes)

    public VulkanRenderSystem(WorldEngine world, ServiceManager sm) {
        this.worldIn = world;
        this.worldIn.acquireRef();
        this.bridge = VulkanBridge.getInstance();

        Logger.info("Creating Vulkan render system on " + this.bridge.getDevice().toString());

        int maxSections = 1 << 18; // 262,144 sections
        long geometryCapacity = 256L * 1024 * 1024; // 256MB geometry buffer

        this.modelStore = new VulkanModelStore(this.bridge);
        this.geometryData = new VulkanSectionGeometryData(this.bridge, maxSections, geometryCapacity);

        // VK_FORMAT_R8G8B8A8_UNORM (37), VK_FORMAT_D32_SFLOAT (126)
        this.sectionRenderer = new VulkanSectionRenderer(this.bridge, this.modelStore, this.geometryData,
                VK12.VK_FORMAT_R8G8B8A8_UNORM, VK12.VK_FORMAT_D32_SFLOAT);

        this.modelService = new ModelBakerySubsystem(world.getMapper(), this.modelStore);
        this.renderGen = new RenderGenerationService(world, this.modelService, sm, false);
        this.renderGen.setResultConsumer(this::onSectionMeshBuilt);

        Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
        world.getMapper().setBiomeCallback(this.modelService::addBiome);

        int minSec = Minecraft.getInstance().level.getMinSectionY() >> 5;
        int maxSec = (Minecraft.getInstance().level.getMaxSectionY() - 1) >> 5;
        this.renderDistanceTracker = new RenderDistanceTracker(40, minSec, maxSec,
                this::addTopLevelSection, this::removeTopLevelSection);

        this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);

        world.setDirtyCallback((section, flags, neighborMask) -> this.renderGen.enqueueTask(section.key));

        Logger.info("Vulkan render system initialized successfully");
    }

    private synchronized void addTopLevelSection(long key) {
        if (!this.posToSectionId.containsKey(key)) {
            int secId = this.nextSectionId++;
            this.posToSectionId.put(key, secId);
            this.activeSectionIds.add(secId);
        }
        // Enqueue meshing for section
        this.renderGen.enqueueTask(key);
    }

    private synchronized void removeTopLevelSection(long key) {
        int secId = this.posToSectionId.remove(key);
        if (secId != 0) {
            this.activeSectionIds.rem(secId);
        }
    }

    private synchronized void onSectionMeshBuilt(BuiltSection built) {
        if (built == null || built.isEmpty()) return;

        int secId = this.posToSectionId.get(built.position);
        if (secId == 0) {
            secId = this.nextSectionId++;
            this.posToSectionId.put(built.position, secId);
            this.activeSectionIds.add(secId);
        }

        long quadCount = built.geometryBuffer.size / 8L;
        long quadStart = this.nextQuadOffset;
        this.nextQuadOffset += quadCount;

        if (this.nextSectionId % 50 == 1) {
            Logger.info(String.format("Vulkan meshed %d sections, %d quads (latest sec %s, quads %d)",
                    this.nextSectionId - 1, this.nextQuadOffset, me.cortex.voxy.common.world.WorldEngine.pprintPos(built.position), quadCount));
        }

        // Copy geometry quads to geometryBuffer
        long dstAddr = this.geometryData.getGeometryBuffer().map() + quadStart * 8L;
        UnsafeUtil.memcpy(built.geometryBuffer.address, dstAddr, built.geometryBuffer.size);

        // Write SectionMeta (32 bytes) to metadataBuffer
        long metaAddr = this.geometryData.getMetadataBuffer().map() + (long) secId * 32L;

        // uvec4 a:
        // x: posA (position >> 32), y: posB (position), z: aabb, w: quadStart
        MemoryUtil.memPutInt(metaAddr, (int) (built.position >> 32));
        MemoryUtil.memPutInt(metaAddr + 4, (int) built.position);
        MemoryUtil.memPutInt(metaAddr + 8, built.aabb);
        MemoryUtil.memPutInt(metaAddr + 12, (int) quadStart + (built.offsets != null ? built.offsets[0] : 0));

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
            MemoryUtil.memPutInt(metaAddr + 16, 0);
            MemoryUtil.memPutInt(metaAddr + 20, 0);
            MemoryUtil.memPutInt(metaAddr + 24, 0);
            MemoryUtil.memPutInt(metaAddr + 28, 0);
        }

        built.free();
    }

    @Override
    public void onFrameStart() {
        this.modelService.tick(100_000_000);

        Minecraft client = Minecraft.getInstance();
        Camera camera = client.gameRenderer.mainCamera();
        if (camera == null) return;

        double camX = camera.position().x();
        double camY = camera.position().y();
        double camZ = camera.position().z();

        // Process render distance tracking
        this.renderDistanceTracker.setCenterAndProcess(camX, camZ);

        // Update Viewport
        var mcProj = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
        Matrix4f voxyProj = VulkanViewport.computeVulkanProjection(mcProj);
        Matrix4f viewMatrix = camera.getViewRotationMatrix(new Matrix4f());

        this.sectionRenderer.getViewport().update(voxyProj, viewMatrix, camX, camY, camZ);

        // Update visible section lists
        synchronized (this) {
            int count = this.activeSectionIds.size();
            if (count > 0) {
                this.sectionRenderer.updateVisibleSections(this.activeSectionIds.elements(), count);
                this.sectionRenderer.buildDrawCalls(count);
            }
        }
    }

    @Override
    public void renderOpaque(RenderPass renderPass) {
        VkCommandBuffer cb = this.bridge.extractCommandBuffer(renderPass);
        if (cb == null) return;

        GpuTextureView lightmapView = Minecraft.getInstance().gameRenderer.levelLightmap();
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.LINEAR);
        long vkLightmapView = this.bridge.getVkImageView(lightmapView);
        long vkLightmapSampler = this.bridge.getVkSampler(sampler);

        int count;
        synchronized (this) {
            count = this.activeSectionIds.size();
        }
        if (count > 0) {
            this.sectionRenderer.renderOpaque(cb, vkLightmapView, vkLightmapSampler, count);
        }
    }

    @Override
    public void renderTranslucent(RenderPass renderPass) {
        VkCommandBuffer cb = this.bridge.extractCommandBuffer(renderPass);
        if (cb == null) return;

        GpuTextureView lightmapView = Minecraft.getInstance().gameRenderer.levelLightmap();
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.LINEAR);
        long vkLightmapView = this.bridge.getVkImageView(lightmapView);
        long vkLightmapSampler = this.bridge.getVkSampler(sampler);

        int count;
        synchronized (this) {
            count = this.activeSectionIds.size();
        }
        if (count > 0) {
            this.sectionRenderer.renderTranslucent(cb, vkLightmapView, vkLightmapSampler, count);
        }
    }

    @Override
    public void setRenderDistance(float renderDistance) {
        this.renderDistanceTracker.setRenderDistance((int) Math.ceil(renderDistance + 1));
    }

    @Override
    public WorldEngine getEngine() {
        return this.worldIn;
    }

    @Override
    public void addDebugInfo(List<String> debug) {
        debug.add("Voxy Vulkan: Sections=" + this.activeSectionIds.size() + ", Quads=" + this.nextQuadOffset);
        this.modelService.addDebugData(debug);
    }

    @Override
    public void shutdown() {
        Logger.info("Shutting down Vulkan render system");
        this.worldIn.setDirtyCallback(null);
        this.renderGen.shutdown();
        this.modelService.shutdown();
        this.sectionRenderer.free();
        this.geometryData.free();
        this.worldIn.releaseRef();
        Logger.info("Vulkan render system shutdown complete");
    }
}
