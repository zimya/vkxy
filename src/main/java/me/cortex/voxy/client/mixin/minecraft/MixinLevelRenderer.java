package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystem;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.core.vulkan.bridge.VulkanBridge;
import me.cortex.voxy.client.core.vulkan.pipeline.VulkanRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer implements IVoxyRenderSystemHolder {
    @Unique @Nullable private WorldIdentifier identifier;
    @Unique private @Nullable IVoxyRenderSystem renderer;

    @Override
    public IVoxyRenderSystem voxy$getRenderSystem() {
        return this.renderer;
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void voxy$injectClose(CallbackInfo ci) {
        this.voxy$shutdownRenderer();
    }

    @Override
    public void voxy$shutdownRenderer() {
        if (this.renderer != null) {
            this.renderer.shutdown();
            this.renderer = null;
        }
    }

    @Override
    public void voxy$setWorld(Level level) {
        WorldIdentifier identifier = level==null?null:WorldIdentifier.of(level);
        if (Objects.equals(this.identifier, identifier)) return;
        this.voxy$shutdownRenderer();
        this.identifier = identifier;
    }

    @Override
    public void voxy$createRenderer() {
        if (this.renderer != null) throw new IllegalStateException("Cannot have multiple renderers");
        if (!VoxyConfig.CONFIG.enabled) {
            Logger.info("Not creating renderer due to disabled");
            return;
        }
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            Logger.info("Not creating renderer due to disabled rendering");
            return;
        }
        if (this.identifier == null) {
            Logger.info("Not creating renderer due to null identifier");
            return;
        }
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            Logger.info("Not creating renderer due to null instance");
            return;
        }
        WorldEngine world = this.identifier.getOrCreateEngine(true);
        if (world == null) {
            Logger.warn("Not creating renderer due to null engine");
            return;
        }
        this.voxy$createEngineDirect(world);
    }

    @Unique
    private void voxy$createEngineDirect(WorldEngine world) {
        var instance = world.instanceIn;
        if (instance == null) throw new IllegalStateException();
        try {
            if (VulkanBridge.isAvailable()) {
                this.renderer = new VulkanRenderSystem(world, instance.getServiceManager());
            } else {
                this.renderer = new VoxyRenderSystem(world, instance.getServiceManager());
            }
        } catch (RuntimeException e) {
            if (IrisUtil.irisShaderPackEnabled()) {
                IrisUtil.disableIrisShaders();
            } else {
                throw e;
            }
        }
        instance.updateDedicatedThreads();
    }

    @Inject(method = "addMainPass", at = @At("HEAD"))
    private void voxy$onAddMainPass(FrameGraphBuilder frame, FeatureRenderDispatcher.PreparedFrame featureFrame,
                                    GpuBufferSlice terrainFog, ChunkSectionsToRender chunkSectionsToRender,
                                    boolean consistentDepthRequired, CallbackInfo ci) {
        if (this.renderer != null) {
            this.renderer.onFrameStart();
        }
    }

    @Inject(method = "executeSolid", at = @At("TAIL"))
    private void voxy$onExecuteSolid(ChunkSectionsToRender chunkSectionsToRender,
                                     FeatureRenderDispatcher.PreparedFrame featureFrame,
                                     RenderPass renderPass, CallbackInfo ci) {
        if (this.renderer != null) {
            this.renderer.renderOpaque(renderPass);
        }
    }

    @Inject(method = "executeClassicTransparency", at = @At("TAIL"))
    private void voxy$onExecuteClassicTransparency(ChunkSectionsToRender chunkSectionsToRender,
                                                   FeatureRenderDispatcher.PreparedFrame featureFrame,
                                                   RenderPass renderPass, CallbackInfo ci) {
        if (this.renderer != null) {
            this.renderer.renderTranslucent(renderPass);
        }
    }
}
