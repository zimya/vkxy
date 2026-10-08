package me.cortex.voxy.client.core.vulkan.pipeline;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.NodeStore;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Camera;
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

    public VulkanOctreeTraverser(NodeManager nodeManager, RenderGenerationService renderGen) {
        this.nodeManager = nodeManager;
        this.renderGen = renderGen;
    }

    public FrustumIntersection getLastFrustum() {
        return this.lastFrustum;
    }

    public int traverse(Camera camera, Matrix4fc viewRotation, Matrix4fc projMatrix,
                        int viewportWidth, int viewportHeight, float sectionRenderDistance,
                        IntArrayList outVisibleSections) {
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
                .m32((2.0f * farFrustum * nearFrustum) / (nearFrustum - farFrustum));

        Matrix4f viewProj = frustumProj.mul(viewRotation);
        FrustumIntersection frustum = new FrustumIntersection(viewProj);
        this.lastFrustum = frustum;

        float m11 = Math.abs(projMatrix.m11());
        float baseFovM11 = 1.37f;
        float zoomRatio = Math.max(1.0f, m11 / baseFovM11);
        double zoomScale = Math.sqrt(zoomRatio);

        float subDivSize = Math.max(16.0f, VoxyConfig.CONFIG.subDivisionSize);
        float focalLength = (viewportHeight * m11) * 0.5f;
        float factor = focalLength / subDivSize;
        float factorSq = factor * factor;

        int pendingTasks = this.renderGen.getTaskCount();
        double fullness = Math.max(0.0, (TARGET_QUEUE_SIZE - pendingTasks) / TARGET_QUEUE_SIZE);
        int maxRequests = (int) Math.ceil(fullness * fullness * MAX_REQUESTS_PER_FRAME);

        this.queue.clear();
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
                if (!frustum.testAab(relMinX, relMinY, relMinZ, relMaxX, relMaxY, relMaxZ)) {
                    continue;
                }
            }

            // Descend decision with zoom-aware distance clamping
            boolean shouldDescend = (lvl != 0) && (cameraNearOrInside || (distSq < size * size * factorSq));
            if (shouldDescend) {
                double maxLODDist;
                switch (lvl) {
                    case 1: // Subdividing lvl 1 into lvl 0 (16-block detail)
                        maxLODDist = Math.min(1792.0, 384.0 * zoomScale);
                        break;
                    case 2: // Subdividing lvl 2 into lvl 1 (32-block detail)
                        maxLODDist = Math.min(3584.0, 896.0 * zoomScale);
                        break;
                    case 3: // Subdividing lvl 3 into lvl 2 (64-block detail)
                        maxLODDist = Math.min(6144.0, 1792.0 * zoomScale);
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
                        int cMesh = nodeData.getNodeGeometry(cNodeId);
                        int cChildPtr = nodeData.getChildPtr(cNodeId);
                        boolean inFlight = nodeData.isNodeRequestInFlight(cNodeId);
                        // If child is in-flight or has neither mesh nor deeper child tree, it is an unbuilt gap
                        if (inFlight || (cMesh == NodeManager.NULL_GEOMETRY_ID && cChildPtr == -1)) {
                            allChildrenReady = false;
                            long cPos = nodeData.nodePosition(cNodeId);
                            if (cPos != -1 && !inFlight && requestCount < maxRequests) {
                                this.nodeManager.processRequest(cPos);
                                requestCount++;
                            }
                        }
                    }

                    if (allChildrenReady) {
                        for (int c = 0; c < childCount; c++) {
                            this.queue.add(childPtr + c);
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
                    if (!nodeData.isNodeRequestInFlight(nodeId) && requestCount < maxRequests) {
                        this.nodeManager.processRequest(pos);
                        requestCount++;
                    }
                    // Render self as fallback while children are building
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
                    if (!nodeData.isNodeRequestInFlight(nodeId) && requestCount < maxRequests) {
                        this.nodeManager.processRequest(pos);
                        requestCount++;
                    }
                    int childPtr = nodeData.getChildPtr(nodeId);
                    if (childPtr != -1) {
                        int childCount = nodeData.getChildPtrCount(nodeId);
                        for (int c = 0; c < childCount; c++) {
                            this.queue.add(childPtr + c);
                        }
                    }
                }
            }
        }
        return outVisibleSections.size();
    }
}
