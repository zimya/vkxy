package me.cortex.voxy;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.vulkan.compute.VulkanSpirvCompiler;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class VulkanShaderCompileTest {
    public static void main(String[] args) {
        System.out.println("Testing prep.comp...");
        String prepSrc = ShaderLoader.parse("voxy:lod/gl46/prep.comp");
        ByteBuffer spvPrep = VulkanSpirvCompiler.compile("prep.comp", prepSrc, VulkanSpirvCompiler.STAGE_COMPUTE, Map.of());
        System.out.println("prep.comp compiled successfully! Bytes: " + spvPrep.remaining());

        System.out.println("Testing cmdgen.comp...");
        String cmdgenSrc = ShaderLoader.parse("voxy:lod/gl46/cmdgen.comp");
        Map<String, String> defines = new HashMap<>();
        defines.put("TRANSLUCENT_WRITE_BASE", "1024");
        defines.put("TEMPORAL_OFFSET", "500000");
        defines.put("TRANSLUCENT_DISTANCE_BUFFER_BINDING", "7");
        ByteBuffer spvCmdgen = VulkanSpirvCompiler.compile("cmdgen.comp", cmdgenSrc, VulkanSpirvCompiler.STAGE_COMPUTE, defines);
        System.out.println("cmdgen.comp compiled successfully! Bytes: " + spvCmdgen.remaining());

        System.out.println("Testing quads3.vert...");
        String vertSrc = ShaderLoader.parse("voxy:lod/gl46/quads3.vert");
        // For Vulkan, gl_VertexID is gl_VertexIndex, and gl_BaseInstance is gl_InstanceIndex
        Map<String, String> vertDefines = new HashMap<>();
        vertDefines.put("gl_VertexID", "gl_VertexIndex");
        vertDefines.put("gl_BaseInstance", "gl_InstanceIndex");
        vertDefines.put("NO_SHADE_FACE_TINT", "1.0f");
        vertDefines.put("UP_FACE_TINT", "1.0f");
        vertDefines.put("DOWN_FACE_TINT", "0.5f");
        vertDefines.put("Z_AXIS_FACE_TINT", "0.8f");
        vertDefines.put("X_AXIS_FACE_TINT", "0.6f");
        try {
            ByteBuffer spvVert = VulkanSpirvCompiler.compile("quads3.vert", vertSrc, VulkanSpirvCompiler.STAGE_VERTEX, vertDefines);
            System.out.println("quads3.vert compiled successfully! Bytes: " + spvVert.remaining());
        } catch (Exception e) {
            System.err.println("quads3.vert failed: " + e.getMessage());
        }

        System.out.println("Testing quads.frag...");
        String fragSrc = ShaderLoader.parse("voxy:lod/gl46/quads.frag");
        // Replace layout(binding = 0) uniform sampler2D blockModelAtlas with binding 6
        // and layout(binding = 2) uniform sampler2D depthTex with binding 8
        fragSrc = fragSrc.replace("layout(binding = 0) uniform sampler2D blockModelAtlas;", "layout(binding = 6) uniform sampler2D blockModelAtlas;");
        fragSrc = fragSrc.replace("layout(binding = 2) uniform sampler2D depthTex;", "layout(binding = 8) uniform sampler2D depthTex;");
        Map<String, String> fragDefines = new HashMap<>();
        fragDefines.put("LIGHTING_SAMPLER_BINDING", "7");
        try {
            ByteBuffer spvFrag = VulkanSpirvCompiler.compile("quads.frag", fragSrc, VulkanSpirvCompiler.STAGE_FRAGMENT, fragDefines);
            System.out.println("quads.frag compiled successfully! Bytes: " + spvFrag.remaining());
        } catch (Exception e) {
            System.err.println("quads.frag failed: " + e.getMessage());
        }

        System.out.println("Testing translucent quads.frag...");
        Map<String, String> transFragDefines = new HashMap<>(fragDefines);
        transFragDefines.put("TRANSLUCENT", "1");
        try {
            ByteBuffer spvTransFrag = VulkanSpirvCompiler.compile("quads.frag", fragSrc, VulkanSpirvCompiler.STAGE_FRAGMENT, transFragDefines);
            System.out.println("translucent quads.frag compiled successfully! Bytes: " + spvTransFrag.remaining());
        } catch (Exception e) {
            System.err.println("translucent quads.frag failed: " + e.getMessage());
        }

        System.out.println("Testing simple.comp (prefix sum)...");
        String simpleSrc = ShaderLoader.parse("voxy:util/prefixsum/simple.comp");
        Map<String, String> simpleDefines = new HashMap<>();
        simpleDefines.put("IO_BUFFER", "0");
        try {
            ByteBuffer spvSimple = VulkanSpirvCompiler.compile("simple.comp", simpleSrc, VulkanSpirvCompiler.STAGE_COMPUTE, simpleDefines);
            System.out.println("simple.comp compiled successfully! Bytes: " + spvSimple.remaining());
        } catch (Exception e) {
            System.err.println("simple.comp failed: " + e.getMessage());
        }

        System.out.println("Testing buildtranslucents.comp...");
        String buildTransSrc = ShaderLoader.parse("voxy:lod/gl46/buildtranslucents.comp");
        Map<String, String> transDefines = new HashMap<>();
        transDefines.put("TRANSLUCENT_WRITE_BASE", "1024");
        transDefines.put("TRANSLUCENT_DISTANCE_BUFFER_BINDING", "5");
        transDefines.put("TRANSLUCENT_OFFSET", "400000");
        try {
            ByteBuffer spvTrans = VulkanSpirvCompiler.compile("buildtranslucents.comp", buildTransSrc, VulkanSpirvCompiler.STAGE_COMPUTE, transDefines);
            System.out.println("buildtranslucents.comp compiled successfully! Bytes: " + spvTrans.remaining());
        } catch (Exception e) {
            System.err.println("buildtranslucents.comp failed: " + e.getMessage());
        }
    }
}
