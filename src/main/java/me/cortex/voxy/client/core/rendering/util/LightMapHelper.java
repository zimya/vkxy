package me.cortex.voxy.client.core.rendering.util;

import net.minecraft.client.Minecraft;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_R;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL33.glSamplerParameteri;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.GL45.glCreateSamplers;

public class LightMapHelper {
    private static final int LM_SAMPLER;
    static {
        if (!me.cortex.voxy.client.core.vulkan.bridge.VulkanBridge.isAvailable()) {
            LM_SAMPLER = glCreateSamplers();
            glSamplerParameteri(LM_SAMPLER, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glSamplerParameteri(LM_SAMPLER, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glSamplerParameteri(LM_SAMPLER, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glSamplerParameteri(LM_SAMPLER, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glSamplerParameteri(LM_SAMPLER, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        } else {
            LM_SAMPLER = 0;
        }
    }

    public static void bind(int lightingIndex) {
        glBindSampler(lightingIndex, LM_SAMPLER);
        glBindTextureUnit(lightingIndex, getLightmapTextureId());
    }

    public static int getLightmapTextureId() {
        return ((com.mojang.renderpearl.backend.opengl.GlTextureView)Minecraft.getInstance().gameRenderer.levelLightmap()).texture().glId();
    }
}