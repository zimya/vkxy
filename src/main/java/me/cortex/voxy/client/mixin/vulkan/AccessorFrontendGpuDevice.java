package me.cortex.voxy.client.mixin.vulkan;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = FrontendGpuDevice.class, remap = false)
public interface AccessorFrontendGpuDevice {
    @Accessor("backend")
    GpuDeviceBackend voxy$getBackend();
}
