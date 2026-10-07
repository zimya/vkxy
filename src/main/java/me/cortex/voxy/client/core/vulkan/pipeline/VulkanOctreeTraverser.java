package me.cortex.voxy.client.core.vulkan.pipeline;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.NodeStore;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Camera;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public class VulkanOctreeTraverser {
    private static final int MAX_REQUESTS_PER_FRAME = 150;

    private final NodeManager nodeManager;
    private final IntArrayList queue = new IntArrayList(8192);

    public VulkanOctreeTraverser(NodeManager nodeManager) {
        this.nodeManager = nodeManager;
    }

    public int traverse(Camera camera, Matrix4fc viewRotation, Matrix4fc projMatrix,
                        int viewportWidth, int viewportHeight, float sectionRenderDistance,
                        IntArrayList outVisibleSections) {
        outVisibleSections.clear();
        NodeStore nodeData = this.nodeManager.getNodeData();

        double camX = camera.position().x();
        double camY = camera.position().y();
        double camZ = camera.position().z();

        Matrix4f viewProj = new Matrix4f(projMatrix).mul(viewRotation);
        FrustumIntersection frustum = new FrustumIntersection(viewProj);

        float m11 = Math.abs(projMatrix.m11());
        float subDivSize = Math.max(16.0f, VoxyConfig.CONFIG.subDivisionSize);
        float focalLength = (viewportHeight * m11) * 0.5f;
        float factor = focalLength / subDivSize;
        float factorSq = factor * factor;

        float maxDistBlocks = sectionRenderDistance * 512.0f;
        double maxDistSq = (double) maxDistBlocks * (double) maxDistBlocks;

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
                if (frustum.intersectAab(relMinX, relMinY, relMinZ, relMaxX, relMaxY, relMaxZ) == FrustumIntersection.OUTSIDE) {
                    continue;
                }
            }

            // Descend decision
            boolean shouldDescend = (lvl != 0) && (cameraNearOrInside || (distSq < size * size * factorSq));

            if (shouldDescend) {
                int childPtr = nodeData.getChildPtr(nodeId);
                if (childPtr != -1) {
                    int childCount = nodeData.getChildPtrCount(nodeId);
                    for (int c = 0; c < childCount; c++) {
                        this.queue.add(childPtr + c);
                    }
                } else {
                    // Node needs subdivision
                    if (!nodeData.isNodeRequestInFlight(nodeId) && requestCount < MAX_REQUESTS_PER_FRAME) {
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
                    if (!nodeData.isNodeRequestInFlight(nodeId) && requestCount < MAX_REQUESTS_PER_FRAME) {
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
