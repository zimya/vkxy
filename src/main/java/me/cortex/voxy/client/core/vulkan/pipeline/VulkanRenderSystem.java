package me.cortex.voxy.client.core.vulkan.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystem;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import me.cortex.voxy.client.core.vulkan.bridge.VulkanBridge;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class VulkanRenderSystem implements IVoxyRenderSystem {
    private record ChildChange(long key, byte childExistence) {}

    private final WorldEngine worldIn;
    private final VulkanBridge bridge;
    private final VulkanModelStore modelStore;
    private final ModelBakerySubsystem modelService;
    private final VulkanSectionGeometryData geometryData;
    private final VulkanGeometryManager geometryManager;
    private final VulkanSectionRenderer sectionRenderer;
    private final VulkanSSAO ssao;
    private final SectionUpdateRouter router;
    private final NodeManager nodeManager;
    private final VulkanOctreeTraverser traverser;
    private final RenderGenerationService renderGen;
    private final RenderDistanceTracker renderDistanceTracker;

    private final ConcurrentLinkedQueue<BuiltSection> completedMeshesQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ChildChange> childChangesQueue = new ConcurrentLinkedQueue<>();
    private final IntArrayList activeSectionIds = new IntArrayList(8192);
    private int evictionTickCounter = 0;

    public VulkanRenderSystem(WorldEngine world, ServiceManager sm) {
        this.worldIn = world;
        this.worldIn.acquireRef();
        this.bridge = VulkanBridge.getInstance();

        Logger.info("Creating Vulkan render system on " + this.bridge.getDevice().toString());

        int maxSections = 1 << 19; // 524,288 sections
        long geometryCapacity = 1536L * 1024 * 1024; // 1.5GB geometry buffer for distant LoDs and zoom

        this.modelStore = new VulkanModelStore(this.bridge);
        this.geometryData = new VulkanSectionGeometryData(this.bridge, maxSections, geometryCapacity);
        this.geometryManager = new VulkanGeometryManager(this.geometryData);

        // VK_FORMAT_R8G8B8A8_UNORM (37), VK_FORMAT_D32_SFLOAT (126)
        this.sectionRenderer = new VulkanSectionRenderer(this.bridge, this.modelStore, this.geometryData,
                VK12.VK_FORMAT_R8G8B8A8_UNORM, VK12.VK_FORMAT_D32_SFLOAT);
        this.ssao = new VulkanSSAO(this.bridge, VK12.VK_FORMAT_R8G8B8A8_UNORM);

        this.modelService = new ModelBakerySubsystem(world.getMapper(), this.modelStore);
        this.renderGen = new RenderGenerationService(world, this.modelService, sm, false);
        this.renderGen.setResultConsumer(this.completedMeshesQueue::add);

        this.router = new SectionUpdateRouter();
        this.nodeManager = new NodeManager(1 << 20, this.geometryManager, this.router);
        this.router.setCallbacks(
                this.renderGen::enqueueTask,
                this.renderGen::enqueueTask,
                section -> this.childChangesQueue.add(new ChildChange(section.key, section.getNonEmptyChildren()))
        );

        this.traverser = new VulkanOctreeTraverser(this.nodeManager, this.renderGen);

        Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
        world.getMapper().setBiomeCallback(this.modelService::addBiome);

        int minSec = Minecraft.getInstance().level.getMinSectionY() >> 5;
        int maxSec = (Minecraft.getInstance().level.getMaxSectionY() - 1) >> 5;
        Camera cam = Minecraft.getInstance().gameRenderer.mainCamera();
        double initX = cam != null ? cam.position().x() : (Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getX() : 0.0);
        double initZ = cam != null ? cam.position().z() : (Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getZ() : 0.0);
        this.renderDistanceTracker = new RenderDistanceTracker(250, minSec, maxSec, initX, initZ,
                this.nodeManager::insertTopLevelNode, this.nodeManager::removeTopLevelNode);

        this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);

        world.setDirtyCallback((section, flags, neighborMask) -> {
            this.router.forwardEvent(section, flags);
            if (neighborMask != 0) {
                if ((neighborMask & 0b000001) != 0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y - 1, section.z));
                if ((neighborMask & 0b000010) != 0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y + 1, section.z));
                if ((neighborMask & 0b000100) != 0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x - 1, section.y, section.z));
                if ((neighborMask & 0b001000) != 0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x + 1, section.y, section.z));
                if ((neighborMask & 0b010000) != 0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y, section.z - 1));
                if ((neighborMask & 0b100000) != 0) this.router.triggerRemesh(WorldEngine.getWorldSectionId(section.lvl, section.x, section.y, section.z + 1));
            }
        });

        Logger.info("Vulkan render system initialized successfully with NodeManager and Octree Traversal");
    }

    @Override
    public void onFrameStart() {
        // Drain child changes
        ChildChange change;
        while ((change = this.childChangesQueue.poll()) != null) {
            this.nodeManager.processChildChange(change.key, change.childExistence);
        }

        // Drain completed mesh results
        BuiltSection built;
        while ((built = this.completedMeshesQueue.poll()) != null) {
            this.nodeManager.processGeometryResult(built);
        }

        this.nodeManager.getNodeUpdates().clear();

        this.modelService.tick(100_000_000);

        this.sectionRenderer.getHiZBuffer().updateCoarseDepthGrid();

        Minecraft client = Minecraft.getInstance();
        Camera camera = client.gameRenderer.mainCamera();
        if (camera == null) return;

        double camX = camera.position().x();
        double camY = camera.position().y();
        double camZ = camera.position().z();

        // Process render distance tracking (top-level nodes)
        int trackerIters = 0;
        while (this.renderDistanceTracker.setCenterAndProcess(camX, camZ) && trackerIters++ < 4) {}

        // Update Viewport
        var mcProj = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
        Matrix4f voxyProj = VulkanViewport.computeVulkanProjection(mcProj);
        Matrix4f viewMatrix = camera.getViewRotationMatrix(new Matrix4f());

        // Compute Fog & Fade parameters
        org.joml.Vector4f fogColor = new org.joml.Vector4f(0, 0, 0, 0);
        org.joml.Vector4f fogParams = new org.joml.Vector4f(0, 0, 0, 0);
        org.joml.Vector4f fadeParams = new org.joml.Vector4f(0, 0, 0, 0);

        var gameRenderState = client.gameRenderer.gameRenderState();
        if (gameRenderState != null && gameRenderState.levelRenderState != null && gameRenderState.levelRenderState.cameraRenderState != null) {
            var cameraRenderState = gameRenderState.levelRenderState.cameraRenderState;
            var fogData = cameraRenderState.fogData;
            if (fogData != null) {
                fogColor.set(fogData.color.x, fogData.color.y, fogData.color.z, fogData.color.w);

                var fogMode = VoxyConfig.CONFIG.getFogMode();
                float vanillaRd = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f;
                float start = fogData.environmentalStart;
                float end = fogData.environmentalEnd;
                boolean fogCoversAllRendering = end < vanillaRd;

                if (fogMode.hasFog || fogCoversAllRendering) {
                    if (Math.abs(end - start) > 1.0f) {
                        float invEndFogDelta = 1.0f / (end - start);
                        float endDistance = Math.max(vanillaRd, 20.0f * 16.0f) * (float) Math.sqrt(3.0);
                        float startDelta = -start * invEndFogDelta;
                        float maxFogLerp = fogCoversAllRendering ? 1.0f : Math.clamp(endDistance * invEndFogDelta + startDelta, 0.0f, 1.0f);
                        fogParams.set(invEndFogDelta, startDelta, maxFogLerp, fogCoversAllRendering ? 1.0f : 0.0f);
                    }
                }

                if (fogMode.hasFade && !fogCoversAllRendering) {
                    int mode = 1; // 1: XZ distance
                    float rd = VoxyConfig.CONFIG.sectionRenderDistance * 16.0f * 32.0f - (float) Math.sqrt(32.0 * 32.0);
                    float fadeStart = Math.max(vanillaRd, rd * 0.9f);
                    float fadeEnd = Math.max(vanillaRd, rd);
                    if (fadeEnd > fadeStart) {
                        float scale = 1.0f / (fadeEnd - fadeStart);
                        fadeParams.set(mode, (-fadeStart) * scale, scale, 0.0f);
                    }
                }
            }
        }

        this.sectionRenderer.getViewport().update(voxyProj, viewMatrix, camX, camY, camZ, fogColor, fogParams, fadeParams);

        int width = client.getWindow().getWidth();
        int height = client.getWindow().getHeight();

        // Run hierarchical octree traversal
        int visibleCount = this.traverser.traverse(
                camera, viewMatrix, mcProj, width, height,
                VoxyConfig.CONFIG.sectionRenderDistance, this.activeSectionIds
        );

        if (visibleCount > 0) {
            this.sectionRenderer.updateVisibleSections(this.activeSectionIds.elements(), visibleCount);
            this.sectionRenderer.buildDrawCalls(visibleCount);
        } else {
            this.sectionRenderer.clearDrawCalls();
        }

        // Evict distant node geometry if memory is tight
        this.tickEviction(camX, camY, camZ);
    }

    private void tickEviction(double camX, double camY, double camZ) {
        this.evictionTickCounter++;
        // Check eviction every 30 frames (~0.5s)
        if (this.evictionTickCounter % 30 != 0) return;

        // Evict when free geometry memory is constrained (< 256MB)
        long freeBytes = this.geometryManager.getFreeBytes();
        if (freeBytes < 256L * 1024 * 1024) {
            long[] positions = this.nodeManager.getActiveSectionPositions();
            if (positions.length == 0) return;

            FrustumIntersection frustum = this.traverser.getLastFrustum();

            // Candidate filtering: NEVER evict nodes that are within the current view frustum!
            // NEVER evict Level >= 2 nodes to preserve baseline coarse LOD coverage across all angles!
            LongArrayList candidateList = new LongArrayList();
            for (long pos : positions) {
                int lvl = WorldEngine.getLevel(pos);
                if (lvl >= 2) continue; // Keep Level 2, 3, 4 permanently to guarantee no void holes

                if (frustum != null) {
                    double minX = (double) (WorldEngine.getX(pos) << lvl) * 32.0;
                    double minY = (double) (WorldEngine.getY(pos) << lvl) * 32.0;
                    double minZ = (double) (WorldEngine.getZ(pos) << lvl) * 32.0;
                    double size = (double) (32 << lvl);
                    double maxX = minX + size;
                    double maxY = minY + size;
                    double maxZ = minZ + size;

                    float relMinX = (float) (minX - camX);
                    float relMaxX = (float) (maxX - camX);
                    float relMinY = (float) (minY - camY);
                    float relMaxY = (float) (maxY - camY);
                    float relMinZ = (float) (minZ - camZ);
                    float relMaxZ = (float) (maxZ - camZ);

                    // If node intersects the current view frustum, PROTECT IT!
                    if (frustum.testAab(relMinX, relMinY, relMinZ, relMaxX, relMaxY, relMaxZ)) {
                        continue;
                    }
                }

                candidateList.add(pos);
            }

            if (candidateList.isEmpty()) return;

            // Sort: Level 0 first (finest LODs take up 90%+ VRAM), then by distance descending
            candidateList.sort((a, b) -> {
                int lvlA = WorldEngine.getLevel(a);
                int lvlB = WorldEngine.getLevel(b);
                if (lvlA != lvlB) {
                    return Integer.compare(lvlA, lvlB); // Lower level evicted first
                }
                double distA = getDistanceSq(a, camX, camY, camZ);
                double distB = getDistanceSq(b, camX, camY, camZ);
                return Double.compare(distB, distA);
            });

            // Evict up to 256 outside-frustum nodes per sweep
            int evicted = 0;
            int maxEvict = 256;
            for (int i = 0; i < candidateList.size() && evicted < maxEvict; i++) {
                long pos = candidateList.getLong(i);
                this.nodeManager.removeNodeGeometry(pos);
                evicted++;
            }

            if (evicted > 0) {
                Logger.info("Vulkan geometry eviction triggered: evicted " + evicted +
                        " outside-frustum nodes. Free: " + (this.geometryManager.getFreeBytes() / (1024 * 1024)) +
                        "MB / " + (this.geometryManager.getCapacityBytes() / (1024 * 1024)) + "MB");
            }
        }
    }

    private static double getDistanceSq(long pos, double camX, double camY, double camZ) {
        int lvl = WorldEngine.getLevel(pos);
        double x = (double) ((WorldEngine.getX(pos) << lvl) * 32 + (16 << lvl));
        double y = (double) ((WorldEngine.getY(pos) << lvl) * 32 + (16 << lvl));
        double z = (double) ((WorldEngine.getZ(pos) << lvl) * 32 + (16 << lvl));
        double dx = x - camX;
        double dy = y - camY;
        double dz = z - camZ;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public void renderOpaque(RenderPass renderPass) {
        VkCommandBuffer cb = this.bridge.extractCommandBuffer(renderPass);
        if (cb == null) return;

        GpuTextureView lightmapView = Minecraft.getInstance().gameRenderer.levelLightmap();
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.LINEAR);
        long vkLightmapView = this.bridge.getVkImageView(lightmapView);
        long vkLightmapSampler = this.bridge.getVkSampler(sampler);

        int count = this.activeSectionIds.size();
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

        int count = this.activeSectionIds.size();
        if (count > 0) {
            this.sectionRenderer.renderTranslucent(cb, vkLightmapView, vkLightmapSampler, count);
        }
    }

    @Override
    public void renderSSAO() {
        if (!VoxyConfig.CONFIG.enabled || !VoxyConfig.CONFIG.isRenderingEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        var mainTarget = mc.gameRenderer.mainRenderTarget();
        if (mainTarget == null) return;

        GpuTextureView colorView = mainTarget.getColorTextureView();
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        if (colorView == null || depthView == null) return;

        var gameRenderState = mc.gameRenderer.gameRenderState();
        if (gameRenderState == null || gameRenderState.levelRenderState == null ||
                gameRenderState.levelRenderState.cameraRenderState == null) return;

        var rawMCProj = gameRenderState.levelRenderState.cameraRenderState.projectionMatrix;
        if (rawMCProj == null) return;

        int width = mainTarget.width;
        int height = mainTarget.height;

        RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Voxy SSAO")
                .withColorAttachment(colorView)
                .build();

        try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
            VkCommandBuffer cb = this.bridge.getRenderPassCommandBuffer(renderPass);
            long vkDepthView = this.bridge.getVkImageView(depthView);
            GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.NEAREST);
            long vkDepthSampler = this.bridge.getVkSampler(sampler);

            float vanillaRd = mc.options != null ? mc.options.getEffectiveRenderDistance() * 16.0f : 192.0f;
            this.ssao.render(cb, vkDepthView, vkDepthSampler, rawMCProj, width, height, vanillaRd);
        }
    }

    @Override
    public void buildHiZPyramid() {
        if (!VoxyConfig.CONFIG.enabled || !VoxyConfig.CONFIG.isRenderingEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        var mainTarget = mc.gameRenderer.mainRenderTarget();
        if (mainTarget == null) return;

        GpuTextureView depthView = mainTarget.getDepthTextureView();
        if (depthView == null) return;

        int width = mainTarget.width;
        int height = mainTarget.height;
        if (width <= 0 || height <= 0) return;

        long vkDepthImage = this.bridge.getVkImage(depthView);
        long vkDepthView = this.bridge.getVkImageView(depthView);
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.NEAREST);
        long vkDepthSampler = this.bridge.getVkSampler(sampler);

        VkCommandBuffer cb = this.bridge.allocateAndBeginCommandEncoderTransientBuffer();
        try {
            this.sectionRenderer.getHiZBuffer().build(cb, vkDepthImage, vkDepthView, vkDepthSampler, width, height);
        } finally {
            VK12.vkEndCommandBuffer(cb);
            this.bridge.executeCommandBuffer(cb);
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
        float cullPct = this.sectionRenderer.getCullPercentage();
        int culledQuads = this.sectionRenderer.getCulledQuads();
        int totalQuads = this.sectionRenderer.getTotalQuads();
        int culledSecs = this.sectionRenderer.getCulledSections();
        int totalSecs = this.sectionRenderer.getTotalSections();

        float[] grid = this.sectionRenderer.getHiZBuffer().getCoarseDepthGrid();
        float minD = 1.0f, maxD = 0.0f;
        int nonZero = 0;
        if (grid != null) {
            for (float d : grid) {
                if (d > 0.0f) {
                    minD = Math.min(minD, d);
                    maxD = Math.max(maxD, d);
                    nonZero++;
                }
            }
        }
        int totalGrid = grid != null ? grid.length : 0;

        debug.add(String.format("Hi-Z Occlusion: %.1f%% culled (%d/%d quads, %d/%d secs, hizRange=[%.4f, %.4f], solid=%d/%d)",
                cullPct, culledQuads, totalQuads, culledSecs, totalSecs, (nonZero > 0 ? minD : 0f), maxD, nonZero, totalGrid));
        debug.add("Voxy Vulkan: Vis=" + this.activeSectionIds.size() +
                ", Culled=" + this.traverser.getLastCulledByVanillaCount() +
                ", Top=" + this.nodeManager.getTopLevelNodeIds().size() +
                ", Geo=" + this.geometryManager.getSectionCount() +
                ", VRAM=" + (this.geometryManager.getAllocatedBytes() / (1024 * 1024)) + "/" +
                (this.geometryManager.getCapacityBytes() / (1024 * 1024)) + "MB" +
                ", SSAO=" + VoxyConfig.CONFIG.getSSAOMode().name().toLowerCase());
        this.modelService.addDebugData(debug);
        this.nodeManager.addDebug(debug);
    }

    @Override
    public void shutdown() {
        Logger.info("Shutting down Vulkan render system");
        this.worldIn.setDirtyCallback(null);
        this.worldIn.getMapper().setBiomeCallback(null);
        this.worldIn.getMapper().setStateCallback(null);
        this.renderGen.shutdown();
        this.modelService.shutdown();
        this.sectionRenderer.free();
        this.ssao.free();
        this.geometryManager.free();
        this.geometryData.free();
        this.modelStore.free();

        BuiltSection built;
        while ((built = this.completedMeshesQueue.poll()) != null) {
            built.free();
        }
        this.childChangesQueue.clear();

        this.worldIn.releaseRef();
        this.bridge.waitIdle();
        Logger.info("Vulkan render system shutdown complete");
    }
}
