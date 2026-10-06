package me.cortex.voxy.client.core.vulkan.compute;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Compiles GLSL shaders into SPIR-V bytecode using Shaderc.
 */
public final class VulkanSpirvCompiler {
    public static final int STAGE_VERTEX = Shaderc.shaderc_glsl_vertex_shader;
    public static final int STAGE_FRAGMENT = Shaderc.shaderc_glsl_fragment_shader;
    public static final int STAGE_COMPUTE = Shaderc.shaderc_glsl_compute_shader;

    private VulkanSpirvCompiler() {}

    public static ByteBuffer compile(String name, String glslSource, int stage, Map<String, String> defines) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            // Target Vulkan 1.2
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, 4202496);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);

            if (defines != null) {
                for (Map.Entry<String, String> entry : defines.entrySet()) {
                    Shaderc.shaderc_compile_options_add_macro_definition(
                            options,
                            entry.getKey(),
                            entry.getValue() == null ? "" : entry.getValue()
                    );
                }
            }

            long result = Shaderc.shaderc_compile_into_spv(compiler, glslSource, stage, name, "main", options);
            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != Shaderc.shaderc_compilation_status_success) {
                    String error = Shaderc.shaderc_result_get_error_message(result);
                    throw new RuntimeException("SPIR-V compilation failed for [" + name + "]:\n" + error);
                }
                ByteBuffer spvBytes = Shaderc.shaderc_result_get_bytes(result);
                ByteBuffer copy = MemoryUtil.memAlloc(spvBytes.remaining());
                copy.put(spvBytes);
                copy.flip();
                return copy;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
