package me.cortex.voxy.client.core.vulkan.pipeline;

import me.cortex.voxy.client.core.vulkan.bridge.VulkanBridge;
import me.cortex.voxy.client.core.vulkan.compute.VulkanBuffer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;

public class VulkanViewport {
    public static final int UNIFORM_SIZE = 256; // Aligned uniform buffer size
    private final VulkanBuffer uniformBuffer;

    public final Matrix4f projection = new Matrix4f();
    public final Matrix4f modelView = new Matrix4f();
    public final Matrix4f MVP = new Matrix4f();

    public final Vector3i section = new Vector3i();
    public final Vector3d innerTranslation = new Vector3d();
    public double cameraX, cameraY, cameraZ;
    public int frameId = 0;

    public VulkanViewport(VulkanBridge bridge) {
        this.uniformBuffer = VulkanBuffer.createHostVisible(bridge, UNIFORM_SIZE, VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
    }

    public void update(Matrix4fc proj, Matrix4fc view, double camX, double camY, double camZ) {
        this.cameraX = camX;
        this.cameraY = camY;
        this.cameraZ = camZ;
        this.frameId++;

        int secX = (int) Math.floor(camX) >> 5;
        int secY = (int) Math.floor(camY) >> 5;
        int secZ = (int) Math.floor(camZ) >> 5;
        this.section.set(secX, secY, secZ);

        this.innerTranslation.set(
                camX - (secX << 5),
                camY - (secY << 5),
                camZ - (secZ << 5)
        );

        this.modelView.set(view);
        this.projection.set(proj);
        this.MVP.set(proj).mul(view);

        // Upload to uniform buffer
        long ptr = this.uniformBuffer.map();

        // Translate MVP by -innerTranslation
        Matrix4f translatedMVP = new Matrix4f(this.MVP);
        translatedMVP.translate((float) -this.innerTranslation.x, (float) -this.innerTranslation.y, (float) -this.innerTranslation.z);
        translatedMVP.getToAddress(ptr);
        ptr += 64;

        // ivec3 baseSectionPos (12 bytes) + uint frameId (4 bytes)
        MemoryUtil.memPutInt(ptr, this.section.x); ptr += 4;
        MemoryUtil.memPutInt(ptr, this.section.y); ptr += 4;
        MemoryUtil.memPutInt(ptr, this.section.z); ptr += 4;
        MemoryUtil.memPutInt(ptr, this.frameId & 0x7FFFFFFF); ptr += 4;

        // vec3 cameraSubPos (12 bytes) + 4 bytes pad
        MemoryUtil.memPutFloat(ptr, (float) this.innerTranslation.x); ptr += 4;
        MemoryUtil.memPutFloat(ptr, (float) this.innerTranslation.y); ptr += 4;
        MemoryUtil.memPutFloat(ptr, (float) this.innerTranslation.z); ptr += 4;
        MemoryUtil.memPutInt(ptr, 0); // pad
    }

    public static Matrix4f computeVulkanProjection(Matrix4fc baseProjection) {
        var rawMCProj = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
        var extraProjection = rawMCProj.invert(new Matrix4f()).mul(baseProjection);
        var properties = me.cortex.voxy.client.core.RenderProperties.getRenderProperties();

        float near = 16f;
        float far = 16f * 3000f; // 48000 blocks

        // Flip near and far on reverse depth
        if (properties.isReverseZ()) {
            float tmp = near;
            near = far;
            far = tmp;
        }

        // Clip space depth mapping
        return extraProjection.mulLocal(
                new Matrix4f(rawMCProj)
                        .m22((properties.isZero2One() ? far : (far + near)) / (near - far))
                        .m32((properties.isZero2One() ? far : (far + far)) * near / (near - far))
        );
    }

    public VulkanBuffer getUniformBuffer() {
        return this.uniformBuffer;
    }

    public void free() {
        if (this.uniformBuffer != null) {
            this.uniformBuffer.free();
        }
    }
}
