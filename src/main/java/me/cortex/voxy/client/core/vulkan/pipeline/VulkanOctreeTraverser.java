package me.cortex.voxy.client.core.vulkan.pipeline;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.NodeStore;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public class VulkanOctreeTraverser {

    private static final int MAX_REQUESTS_PER_FRAME = 50;
    private static final double TARGET_QUEUE_SIZE = 2500.0;

    private final NodeManager nodeManager;
    private final RenderGenerationService renderGen;
    private final IntArrayList queue = new IntArrayList(8192);
    private FrustumIntersection lastFrustum;
    private int lastCulledByVanillaCount = 0;

    public VulkanOctreeTraverser(
        NodeManager nodeManager,
        RenderGenerationService renderGen
    ) {
        this.nodeManager = nodeManager;
        this.renderGen = renderGen;
    }

    public FrustumIntersection getLastFrustum() {
        return this.lastFrustum;
    }

    public int getLastCulledByVanillaCount() {
        return this.lastCulledByVanillaCount;
    }

    public int traverse(
        Camera camera,
        Matrix4fc viewRotation,
        Matrix4fc projMatrix,
        int viewportWidth,
        int viewportHeight,
        float sectionRenderDistance,
        IntArrayList outVisibleSections
    ) {
        outVisibleSections.clear();
        NodeStore nodeData = this.nodeManager.getNodeData();

        double camX = camera.position().x();
        double camY = camera.position().y();
        double camZ = camera.position().z();

        float maxDistBlocks = sectionRenderDistance * 512.0f;
        double maxDistSq = (double) maxDistBlocks * (double) maxDistBlocks;

        float nearFrustum = 0.05f;
        float farFrustum = maxDistBlocks;
        Matrix4f frustumProj = new Matrix4f(projMatrix)
            .m22((farFrustum + nearFrustum) / (nearFrustum - farFrustum))
            .m32(
                (2.0f * farFrustum * nearFrustum) / (nearFrustum - farFrustum)
            );

        Matrix4f viewProj = frustumProj.mul(viewRotation);
        FrustumIntersection frustum = new FrustumIntersection(viewProj);
        this.lastFrustum = frustum;

        float m11 = Math.abs(projMatrix.m11());
        float baseFovM11 = 1.37f;
        float zoomRatio = Math.max(1.0f, m11 / baseFovM11);

        float subDivSize = Math.max(16.0f, VoxyConfig.CONFIG.subDivisionSize);
        float focalLength = viewportHeight * m11 * 0.5f;
        float factor = focalLength / subDivSize;
        float factorSq = factor * factor;

        int pendingTasks = this.renderGen.getTaskCount();
        double fullness = Math.max(
            0.0,
            (TARGET_QUEUE_SIZE - pendingTasks) / TARGET_QUEUE_SIZE
        );
        int maxRequests = (int) Math.ceil(
            fullness * fullness * MAX_REQUESTS_PER_FRAME
        );

        // Vanilla Minecraft render distance tracking for LOD boundary culling
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client != null ? client.level : null;
        ClientChunkCache chunkCache =
            level != null ? level.getChunkSource() : null;
        int vanillaRenderDistance =
            client != null && client.options != null
                ? client.options.getEffectiveRenderDistance()
                : 0;
        int camChunkX = (int) Math.floor(camX) >> 4;
        int camChunkZ = (int) Math.floor(camZ) >> 4;
        double vanillaRdBlocks = vanillaRenderDistance * 16.0;
        double vanillaRdSq = vanillaRdBlocks * vanillaRdBlocks;

        this.queue.clear();
        this.lastCulledByVanillaCount = 0;
        for (int topId : this.nodeManager.getTopLevelNodeIds()) {
            this.queue.add(topId);
        }

        int requestCount = 0;
        for (int qIdx = 0; qIdx < this.queue.size(); qIdx++) {
            int nodeId = this.queue.getInt(qIdx);
            if (!nodeData.nodeExists(nodeId)) continue;
            long pos = nodeData.nodePosition(nodeId);
            if (pos == -1) continue;

            int lvl = WorldEngine.getLevel(pos);
            int x = WorldEngine.getX(pos);
            int y = WorldEngine.getY(pos);
            int z = WorldEngine.getZ(pos);

            double minX = (double) (x << lvl) * 32.0;
            double minY = (double) (y << lvl) * 32.0;
            double minZ = (double) (z << lvl) * 32.0;
            double size = (double) (32 << lvl);
            double maxX = minX + size;
            double maxY = minY + size;
            double maxZ = minZ + size;

            // Horizontal distance check
            double dx = Math.max(0.0, Math.max(minX - camX, camX - maxX));
            double dz = Math.max(0.0, Math.max(minZ - camZ, camZ - maxZ));
            if (dx * dx + dz * dz > maxDistSq) {
                continue;
            }

            // 3D distance check
            double dy = Math.max(0.0, Math.max(minY - camY, camY - maxY));
            double distSq = dx * dx + dy * dy + dz * dz;

            // Frustum culling
            boolean cameraNearOrInside = distSq < size * size * 2.0;
            if (!cameraNearOrInside) {
                float relMinX = (float) (minX - camX);
                float relMaxX = (float) (maxX - camX);
                float relMinY = (float) (minY - camY);
                float relMaxY = (float) (maxY - camY);
                float relMinZ = (float) (minZ - camZ);
                float relMaxZ = (float) (maxZ - camZ);
                if (
                    !frustum.testAab(
                        relMinX,
                        relMinY,
                        relMinZ,
                        relMaxX,
                        relMaxY,
                        relMaxZ
                    )
                ) {
                    continue;
                }
            }

            // LoD Boundary Culling: if fully inside vanilla loaded range, cull to avoid duplicate rendering!
            if (
                isFullyInsideVanilla(
                    lvl,
                    x,
                    y,
                    z,
                    minX,
                    maxX,
                    minY,
                    maxY,
                    minZ,
                    maxZ,
                    camX,
                    camY,
                    camZ,
                    camChunkX,
                    camChunkZ,
                    vanillaRenderDistance,
                    vanillaRdSq,
                    chunkCache
                )
            ) {
                this.lastCulledByVanillaCount++;
                continue;
            }

            // Boundary intersection test: node straddles the edge between vanilla and Voxy
            boolean boundaryIntersect =
                lvl != 0 &&
                intersectsVanillaBoundary(
                    minX,
                    maxX,
                    minY,
                    maxY,
                    minZ,
                    maxZ,
                    camX,
                    camY,
                    camZ,
                    vanillaRdSq
                );

            // Descend decision with zoom-aware distance clamping & boundary subdivision
            boolean shouldDescend =
                lvl != 0 &&
                (cameraNearOrInside ||
                    boundaryIntersect ||
                    distSq < size * size * factorSq);
            if (shouldDescend && !boundaryIntersect) {
                double maxLODDist;
                switch (lvl) {
                    case 1: // Subdividing lvl 1 into lvl 0 (native 1x1 block detail)
                        maxLODDist = Math.min(maxDistBlocks, 384.0 * zoomRatio);
                        break;
                    case 2: // Subdividing lvl 2 into lvl 1 (2x2 block detail)
                        maxLODDist = Math.min(maxDistBlocks, 896.0 * zoomRatio);
                        break;
                    case 3: // Subdividing lvl 3 into lvl 2 (4x4 block detail)
                        maxLODDist = Math.min(
                            maxDistBlocks,
                            1792.0 * zoomRatio
                        );
                        break;
                    default: // Level 4 or higher
                        maxLODDist = maxDistBlocks;
                        break;
                }
                if (distSq > maxLODDist * maxLODDist) {
                    shouldDescend = false;
                }
            }

            if (shouldDescend) {
                int childPtr = nodeData.getChildPtr(nodeId);
                if (childPtr != -1) {
                    int childCount = nodeData.getChildPtrCount(nodeId);
                    boolean allChildrenReady = true;
                    for (int c = 0; c < childCount; c++) {
                        int cNodeId = childPtr + c;
                        if (!nodeData.nodeExists(cNodeId)) {
                            allChildrenReady = false;
                            break;
                        }
                        long cPos = nodeData.nodePosition(cNodeId);
                        if (cPos == -1) {
                            allChildrenReady = false;
                            break;
                        }

                        int cLvl = WorldEngine.getLevel(cPos);
                        int cX = WorldEngine.getX(cPos);
                        int cY = WorldEngine.getY(cPos);
                        int cZ = WorldEngine.getZ(cPos);
                        double cMinX = (double) (cX << cLvl) * 32.0;
                        double cMaxX = cMinX + (double) (32 << cLvl);
                        double cMinY = (double) (cY << cLvl) * 32.0;
                        double cMaxY = cMinY + (double) (32 << cLvl);
                        double cMinZ = (double) (cZ << cLvl) * 32.0;
                        double cMaxZ = cMinZ + (double) (32 << cLvl);

                        // If child is fully inside vanilla, it will be culled and does not need a mesh
                        if (
                            isFullyInsideVanilla(
                                cLvl,
                                cX,
                                cY,
                                cZ,
                                cMinX,
                                cMaxX,
                                cMinY,
                                cMaxY,
                                cMinZ,
                                cMaxZ,
                                camX,
                                camY,
                                camZ,
                                camChunkX,
                                camChunkZ,
                                vanillaRenderDistance,
                                vanillaRdSq,
                                chunkCache
                            )
                        ) {
                            continue;
                        }

                        int cMesh = nodeData.getNodeGeometry(cNodeId);
                        int cChildPtr = nodeData.getChildPtr(cNodeId);
                        boolean inFlight = nodeData.isNodeRequestInFlight(
                            cNodeId
                        );
                        // If child is in-flight or has neither mesh nor deeper child tree, it is an unbuilt gap
                        if (
                            inFlight ||
                            (cMesh == NodeManager.NULL_GEOMETRY_ID &&
                                cChildPtr == -1)
                        ) {
                            allChildrenReady = false;
                            if (!inFlight && requestCount < maxRequests) {
                                this.nodeManager.processRequest(cPos);
                                requestCount++;
                            }
                        }
                    }

                    if (allChildrenReady) {
                        for (int c = 0; c < childCount; c++) {
                            this.queue.add(childPtr + c);
                        }
                    } else if (boundaryIntersect) {
                        // For boundary nodes: if all children aren't ready, render parent mesh as fallback
                        // (if available) so no dark seams/holes appear while children build.
                        // Also enqueue children so any ready child chunks can be rendered.
                        int parentMesh = nodeData.getNodeGeometry(nodeId);
                        if (parentMesh >= 1) {
                            outVisibleSections.add(parentMesh);
                        } else {
                            for (int c = 0; c < childCount; c++) {
                                this.queue.add(childPtr + c);
                            }
                        }
                    } else {
                        // Atomic LOD substitution fallback: render parent geometry while children are incomplete
                        int parentMesh = nodeData.getNodeGeometry(nodeId);
                        if (parentMesh >= 1) {
                            outVisibleSections.add(parentMesh);
                        } else {
                            // If parent lacks geometry, queue children anyway to show whatever is available
                            for (int c = 0; c < childCount; c++) {
                                this.queue.add(childPtr + c);
                            }
                        }
                    }
                } else {
                    // Node needs subdivision
                    if (
                        !nodeData.isNodeRequestInFlight(nodeId) &&
                        requestCount < maxRequests
                    ) {
                        this.nodeManager.processRequest(pos);
                        requestCount++;
                    }
                    // Render self as fallback while children are building so no gaps appear
                    int meshId = nodeData.getNodeGeometry(nodeId);
                    if (meshId >= 1) {
                        outVisibleSections.add(meshId);
                    }
                }
            } else {
                // Leaf or reached finest level
                int meshId = nodeData.getNodeGeometry(nodeId);
                if (meshId >= 1) {
                    outVisibleSections.add(meshId);
                } else if (meshId == NodeManager.NULL_GEOMETRY_ID) {
                    if (
                        !nodeData.isNodeRequestInFlight(nodeId) &&
                        requestCount < maxRequests
                    ) {
                        this.nodeManager.processRequest(pos);
                        requestCount++;
                    }
                    int childPtr = nodeData.getChildPtr(nodeId);
                    if (childPtr != -1) {
                        int childCount = nodeData.getChildPtrCount(nodeId);
                        for (int c = 0; c < childCount; c++) {
                            int cNodeId = childPtr + c;
                            if (nodeData.nodeExists(cNodeId)) {
                                int cMesh = nodeData.getNodeGeometry(cNodeId);
                                if (cMesh >= 1) {
                                    outVisibleSections.add(cMesh);
                                }
                            }
                        }
                    }
                }
            }
        }
        return outVisibleSections.size();
    }

    private boolean isFullyInsideVanilla(
        int lvl,
        int x,
        int y,
        int z,
        double minX,
        double maxX,
        double minY,
        double maxY,
        double minZ,
        double maxZ,
        double camX,
        double camY,
        double camZ,
        int camChunkX,
        int camChunkZ,
        int vanillaRenderDistance,
        double vanillaRdSq,
        ClientChunkCache chunkCache
    ) {
        if (chunkCache == null || vanillaRenderDistance <= 0) return false;

        // In Minecraft, chunk sections are culled in 3D (both horizontally and vertically).
        // A node can ONLY be fully inside vanilla's rendered region if its furthest point
        // is within vanilla's render envelope in 3D.
        // We use a conservative inner safety margin (vanillaRenderDistance - 5.0 section)
        // so Voxy slightly underlaps vanilla chunks rather than leaving holes at unmeshed edge boundaries.
        double safeRdBlocks = Math.max(
            16.0,
            (vanillaRenderDistance - 5.0) * 16.0
        );
        double safeRdSq = safeRdBlocks * safeRdBlocks;

        double dxFar = Math.max(Math.abs(minX - camX), Math.abs(maxX - camX));
        double dyFar = Math.max(Math.abs(minY - camY), Math.abs(maxY - camY));
        double dzFar = Math.max(Math.abs(minZ - camZ), Math.abs(maxZ - camZ));
        double distFar3DSq = dxFar * dxFar + dyFar * dyFar + dzFar * dzFar;

        // If furthest 3D point is outside vanilla render sphere, it cannot be fully inside!
        // This is crucial at high altitude: when player is at high Y, the ground directly beneath
        // has large dy and is beyond vanilla's vertical render distance, so Voxy MUST NOT cull it!
        if (distFar3DSq > safeRdSq) {
            return false;
        }

        if (lvl == 0) {
            int startChunkX = x << 1;
            int startChunkZ = z << 1;
            for (int cx = startChunkX; cx <= startChunkX + 1; cx++) {
                for (int cz = startChunkZ; cz <= startChunkZ + 1; cz++) {
                    if (
                        !isChunkLoaded(
                            chunkCache,
                            cx,
                            cz,
                            camChunkX,
                            camChunkZ,
                            vanillaRenderDistance
                        )
                    ) {
                        return false;
                    }
                }
            }
            return true;
        } else {
            // For lvl > 0: check the 4 corner chunks of this node
            int minCX = (x << lvl) * 2;
            int maxCX = minCX + (2 << lvl) - 1;
            int minCZ = (z << lvl) * 2;
            int maxCZ = minCZ + (2 << lvl) - 1;

            return (
                isChunkLoaded(
                    chunkCache,
                    minCX,
                    minCZ,
                    camChunkX,
                    camChunkZ,
                    vanillaRenderDistance
                ) &&
                isChunkLoaded(
                    chunkCache,
                    maxCX,
                    minCZ,
                    camChunkX,
                    camChunkZ,
                    vanillaRenderDistance
                ) &&
                isChunkLoaded(
                    chunkCache,
                    minCX,
                    maxCZ,
                    camChunkX,
                    camChunkZ,
                    vanillaRenderDistance
                ) &&
                isChunkLoaded(
                    chunkCache,
                    maxCX,
                    maxCZ,
                    camChunkX,
                    camChunkZ,
                    vanillaRenderDistance
                )
            );
        }
    }

    private static boolean isChunkLoaded(
        ClientChunkCache chunkCache,
        int cx,
        int cz,
        int camChunkX,
        int camChunkZ,
        int vanillaRenderDistance
    ) {
        if (
            Math.abs(cx - camChunkX) > vanillaRenderDistance ||
            Math.abs(cz - camChunkZ) > vanillaRenderDistance
        ) {
            return false;
        }
        var chunk = ((ICheekyClientChunkCache) chunkCache).voxy$cheekyGetChunk(
            cx,
            cz
        );
        return chunk != null && !(chunk instanceof EmptyLevelChunk);
    }

    private static boolean intersectsVanillaBoundary(
        double minX,
        double maxX,
        double minY,
        double maxY,
        double minZ,
        double maxZ,
        double camX,
        double camY,
        double camZ,
        double vanillaRdSq
    ) {
        double dxClose = Math.max(0.0, Math.max(minX - camX, camX - maxX));
        double dyClose = Math.max(0.0, Math.max(minY - camY, camY - maxY));
        double dzClose = Math.max(0.0, Math.max(minZ - camZ, camZ - maxZ));
        double distClose3DSq =
            dxClose * dxClose + dyClose * dyClose + dzClose * dzClose;

        double dxFar = Math.max(Math.abs(minX - camX), Math.abs(maxX - camX));
        double dyFar = Math.max(Math.abs(minY - camY), Math.abs(maxY - camY));
        double dzFar = Math.max(Math.abs(minZ - camZ), Math.abs(maxZ - camZ));
        double distFar3DSq = dxFar * dxFar + dyFar * dyFar + dzFar * dzFar;

        return distClose3DSq < vanillaRdSq && distFar3DSq > vanillaRdSq;
    }
}
