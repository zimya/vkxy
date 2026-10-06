package me.cortex.voxy.client.core;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

public interface IVoxyRenderSystemHolder {
    IVoxyRenderSystem voxy$getRenderSystem();
    void voxy$shutdownRenderer();
    void voxy$createRenderer();
    //void voxy$reloadRenderer();
    void voxy$setWorld(Level level);

    static IVoxyRenderSystem getNullable() {
        var lr = getNullableHolder();
        if (lr == null) return null;
        return lr.voxy$getRenderSystem();
    }

    static IVoxyRenderSystemHolder getNullableHolder() {
        return  (IVoxyRenderSystemHolder)Minecraft.getInstance().levelRenderer;
    }
}
