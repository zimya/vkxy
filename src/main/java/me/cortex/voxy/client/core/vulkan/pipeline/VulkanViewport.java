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
    public static final int UNIFORM_SIZE = 512;
    private final VulkanBuffer uniformBuffer;

    public final Matrix4f projection = new Matrix4f();
    public final Matrix4f modelView = new Matrix4f();
    public final Matrix4f MVP = new Matrix4f();

    public final Vector3i section = new Vector3i();
    public final Vector3d innerTranslation = new Vector3d();
    public double cameraX, cameraY, cameraZ;
    public int frameId = 0;

    public final Matrix4f prevTranslatedMVP = new Matrix4f();
    public final Vector3i prevSection = new Vector3i();
    public final Vector3d prevInnerTranslation = new Vector3d();
    public final Matrix4f prevModelView = new Matrix4f();
    public final Matrix4f prevProjection = new Matrix4f();
    public double prevCameraX, prevCameraY, prevCameraZ;
    public boolean prevValid = false;

    public VulkanViewport(VulkanBridge bridge) {
        this.uniformBuffer = VulkanBuffer.createHostVisible(bridge, UNIFORM_SIZE, VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
    }

    public void update(Matrix4fc proj, Matrix4fc view, double camX, double camY, double camZ) {
        update(proj, view, camX, camY, camZ, null, null, null);
    }

    public void update(Matrix4fc proj, Matrix4fc view, double camX, double camY, double camZ,
                       org.joml.Vector4fc fogColor, org.joml.Vector4fc fogParams, org.joml.Vector4fc fadeParams) {
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

        // Detect camera rotation, movement, or FOV change (zoom)
        boolean isCameraMoving = true;
        if (this.prevValid) {
            org.joml.Vector3f currForward = view.positiveZ(new org.joml.Vector3f()).negate();
            org.joml.Vector3f prevForward = this.prevModelView.positiveZ(new org.joml.Vector3f()).negate();
            float viewDot = Math.clamp(currForward.dot(prevForward), -1.0f, 1.0f);

            double distSq = (camX - this.prevCameraX) * (camX - this.prevCameraX) +
                            (camY - this.prevCameraY) * (camY - this.prevCameraY) +
                            (camZ - this.prevCameraZ) * (camZ - this.prevCameraZ);

            float fovDiff = Math.abs(proj.m11() - this.prevProjection.m11());
            float zoomFactor = Math.max(1.0f, proj.m11() / 1.428f);
            float rotThreshold = 1.0f - (0.00002f / (zoomFactor * zoomFactor));

            isCameraMoving = (viewDot < rotThreshold) || (distSq > 0.0025) || (fovDiff > 0.0005f);
        }

        // Upload to uniform buffer
        long ptr = this.uniformBuffer.map();

        // 0..63: Translate MVP by -innerTranslation
        Matrix4f translatedMVP = new Matrix4f(this.MVP);
        translatedMVP.translate((float) -this.innerTranslation.x, (float) -this.innerTranslation.y, (float) -this.innerTranslation.z);
        translatedMVP.getToAddress(ptr);
        ptr += 64;

        // 64..79: ivec3 baseSectionPos (12 bytes) + uint frameId (4 bytes)
        MemoryUtil.memPutInt(ptr, this.section.x); ptr += 4;
        MemoryUtil.memPutInt(ptr, this.section.y); ptr += 4;
        MemoryUtil.memPutInt(ptr, this.section.z); ptr += 4;
        MemoryUtil.memPutInt(ptr, this.frameId & 0x7FFFFFFF); ptr += 4;

        // 80..95: vec3 cameraSubPos (12 bytes) + 4 bytes pad0
        MemoryUtil.memPutFloat(ptr, (float) this.innerTranslation.x); ptr += 4;
        MemoryUtil.memPutFloat(ptr, (float) this.innerTranslation.y); ptr += 4;
        MemoryUtil.memPutFloat(ptr, (float) this.innerTranslation.z); ptr += 4;
        MemoryUtil.memPutInt(ptr, 0); ptr += 4; // pad0

        // 96..111: vec4 fogColor (16 bytes)
        if (fogColor != null) {
            MemoryUtil.memPutFloat(ptr, fogColor.x()); ptr += 4;
            MemoryUtil.memPutFloat(ptr, fogColor.y()); ptr += 4;
            MemoryUtil.memPutFloat(ptr, fogColor.z()); ptr += 4;
            MemoryUtil.memPutFloat(ptr, fogColor.w()); ptr += 4;
        } else {
            MemoryUtil.memPutFloat(ptr, 0f); ptr += 4;
            MemoryUtil.memPutFloat(ptr, 0f); ptr += 4;
            MemoryUtil.memPutFloat(ptr, 0f); ptr += 4;
            MemoryUtil.memPutFloat(ptr, 0f); ptr += 4;
        }

        // 112..127: vec4 fogParams (16 bytes)
        if (fogParams != null) {
            MemoryUtil.memPutFloat(ptr, fogParams.x()); ptr += 4;
            MemoryUtil.memPutFloat(ptr, fogParams.y()); ptr += 4;
            MemoryUtil.memPutFloat(ptr, fogParams.z()); ptr += 4;
            MemoryUtil.memPutFloat(ptr, fogParams.w()); ptr += 4;
        } else {
            MemoryUtil.memPutFloat(ptr, 0f); ptr += 4;
            MemoryUtil.memPutFloat(ptr, 0f); ptr += 4;
            MemoryUtil.memPutFloat(ptr, 0f); ptr += 4;
            MemoryUtil.memPutFloat(ptr, 0f); ptr += 4;
        }

        // 128..143: vec4 fadeParams (16 bytes)
        if (fadeParams != null) {
            MemoryUtil.memPutFloat(ptr, fadeParams.x()); ptr += 4;
            MemoryUtil.memPutFloat(ptr, fadeParams.y()); ptr += 4;
            MemoryUtil.memPutFloat(ptr, fadeParams.z()); ptr += 4;
            MemoryUtil.memPutFloat(ptr, fadeParams.w()); ptr += 4;
        } else {
            MemoryUtil.memPutFloat(ptr, 0f); ptr += 4;
            MemoryUtil.memPutFloat(ptr, 0f); ptr += 4;
            MemoryUtil.memPutFloat(ptr, 0f); ptr += 4;
            MemoryUtil.memPutFloat(ptr, 0f); ptr += 4;
        }

        // 144..207: mat4 prevMVP (64 bytes)
        if (this.prevValid) {
            this.prevTranslatedMVP.getToAddress(ptr);
        } else {
            translatedMVP.getToAddress(ptr);
        }
        ptr += 64;

        // 208..223: ivec3 prevBaseSectionPos (12 bytes) + uint isCameraMoving (4 bytes)
        if (this.prevValid) {
            MemoryUtil.memPutInt(ptr, this.prevSection.x); ptr += 4;
            MemoryUtil.memPutInt(ptr, this.prevSection.y); ptr += 4;
            MemoryUtil.memPutInt(ptr, this.prevSection.z); ptr += 4;
        } else {
            MemoryUtil.memPutInt(ptr, this.section.x); ptr += 4;
            MemoryUtil.memPutInt(ptr, this.section.y); ptr += 4;
            MemoryUtil.memPutInt(ptr, this.section.z); ptr += 4;
        }
        MemoryUtil.memPutInt(ptr, isCameraMoving ? 1 : 0); ptr += 4;

        // 224..239: vec3 prevCameraSubPos (12 bytes) + 4 bytes pad1
        if (this.prevValid) {
            MemoryUtil.memPutFloat(ptr, (float) this.prevInnerTranslation.x); ptr += 4;
            MemoryUtil.memPutFloat(ptr, (float) this.prevInnerTranslation.y); ptr += 4;
            MemoryUtil.memPutFloat(ptr, (float) this.prevInnerTranslation.z); ptr += 4;
        } else {
            MemoryUtil.memPutFloat(ptr, (float) this.innerTranslation.x); ptr += 4;
            MemoryUtil.memPutFloat(ptr, (float) this.innerTranslation.y); ptr += 4;
            MemoryUtil.memPutFloat(ptr, (float) this.innerTranslation.z); ptr += 4;
        }
        MemoryUtil.memPutInt(ptr, 0); ptr += 4; // pad1

        // Update previous state for next frame
        this.prevTranslatedMVP.set(translatedMVP);
        this.prevSection.set(this.section);
        this.prevInnerTranslation.set(this.innerTranslation);
        this.prevModelView.set(view);
        this.prevProjection.set(proj);
        this.prevCameraX = camX;
        this.prevCameraY = camY;
        this.prevCameraZ = camZ;
        this.prevValid = true;
    }

    public static Matrix4f computeVulkanProjection(Matrix4fc baseProjection) {
        var rawMCProj = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
        var extraProjection = rawMCProj.invert(new Matrix4f()).mul(baseProjection);
        var properties = me.cortex.voxy.client.core.RenderProperties.getRenderProperties();

        float near = 0.05f;
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
