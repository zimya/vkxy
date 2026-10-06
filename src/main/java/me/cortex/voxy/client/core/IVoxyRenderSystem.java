package me.cortex.voxy.client.core;

import com.mojang.renderpearl.api.commands.RenderPass;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.world.WorldEngine;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.joml.Matrix4fc;

import java.util.List;

public interface IVoxyRenderSystem {
    void shutdown();
    void setRenderDistance(float renderDistance);
    WorldEngine getEngine();
    default void addDebugInfo(List<String> debug) {}

    default void renderOpaque(RenderPass renderPass) {}
    default void renderTranslucent(RenderPass renderPass) {}
    default void onFrameStart() {}

    default Viewport<?> getViewport() {
        return null;
    }

    default Viewport<?> setupViewport(Matrix4fc vanillaProjection, Matrix4fc modelView, FogParameters fogParameters, int width, int height, double cameraX, double cameraY, double cameraZ) {
        return null;
    }

    default void renderOpaque(Viewport<?> viewport, int sourceDepthTexture, int sourceColourTexture) {}
}
