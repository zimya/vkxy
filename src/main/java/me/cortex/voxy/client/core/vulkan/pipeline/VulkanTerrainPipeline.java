package me.cortex.voxy.client.core.vulkan.pipeline;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.vulkan.bridge.VulkanBridge;
import me.cortex.voxy.client.core.vulkan.compute.VulkanBuffer;
import me.cortex.voxy.client.core.vulkan.compute.VulkanSpirvCompiler;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

public class VulkanTerrainPipeline {
    private final VulkanBridge bridge;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final long opaquePipeline;
    private final long translucentPipeline;

    public VulkanTerrainPipeline(VulkanBridge bridge, int colorFormat, int depthFormat) {
        this.bridge = bridge;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Create Descriptor Set Layout with Push Descriptors
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(9, stack);

            // 0: SceneUniform (uniform buffer)
            bindings.get(0).binding(0)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT | VK12.VK_SHADER_STAGE_FRAGMENT_BIT);

            // 1: QuadBuffer (storage buffer)
            bindings.get(1).binding(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);

            // 2: SectionBuffer (storage buffer)
            bindings.get(2).binding(2)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);

            // 3: ModelBuffer (storage buffer)
            bindings.get(3).binding(3)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT | VK12.VK_SHADER_STAGE_FRAGMENT_BIT);

            // 4: ModelColourBuffer (storage buffer)
            bindings.get(4).binding(4)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT | VK12.VK_SHADER_STAGE_FRAGMENT_BIT);

            // 5: PositionScratchBuffer (storage buffer)
            bindings.get(5).binding(5)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT);

            // 6: blockModelAtlas (sampler2D)
            bindings.get(6).binding(6)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK12.VK_SHADER_STAGE_FRAGMENT_BIT);

            // 7: lightSampler (sampler2D)
            bindings.get(7).binding(7)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK12.VK_SHADER_STAGE_VERTEX_BIT | VK12.VK_SHADER_STAGE_FRAGMENT_BIT);

            // 8: depthTex (sampler2D)
            bindings.get(8).binding(8)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK12.VK_SHADER_STAGE_FRAGMENT_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
                    .pBindings(bindings);

            LongBuffer pSetLayout = stack.mallocLong(1);
            int res = VK12.vkCreateDescriptorSetLayout(bridge.getDevice(), layoutInfo, null, pSetLayout);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("Failed to create terrain descriptor set layout: " + res);
            }
            this.descriptorSetLayout = pSetLayout.get(0);

            // 2. Create Pipeline Layout
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(pSetLayout);

            LongBuffer pPipelineLayout = stack.mallocLong(1);
            res = VK12.vkCreatePipelineLayout(bridge.getDevice(), pipelineLayoutInfo, null, pPipelineLayout);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("Failed to create terrain pipeline layout: " + res);
            }
            this.pipelineLayout = pPipelineLayout.get(0);

            // 3. Compile Shaders to SPIR-V
            String vertSrc = ShaderLoader.parse("voxy:lod/gl46/quads3.vert");
            Map<String, String> vertDefines = new HashMap<>();
            vertDefines.put("gl_VertexID", "gl_VertexIndex");
            vertDefines.put("gl_BaseInstance", "gl_InstanceIndex");
            vertDefines.put("NO_SHADE_FACE_TINT", "1.0f");
            vertDefines.put("UP_FACE_TINT", "1.0f");
            vertDefines.put("DOWN_FACE_TINT", "0.5f");
            vertDefines.put("Z_AXIS_FACE_TINT", "0.8f");
            vertDefines.put("X_AXIS_FACE_TINT", "0.6f");
            ByteBuffer vertSpv = VulkanSpirvCompiler.compile("quads3.vert", vertSrc, VulkanSpirvCompiler.STAGE_VERTEX, vertDefines);

            String fragSrc = ShaderLoader.parse("voxy:lod/gl46/quads.frag");
            fragSrc = fragSrc.replace("layout(binding = 0) uniform sampler2D blockModelAtlas;", "layout(binding = 6) uniform sampler2D blockModelAtlas;");
            fragSrc = fragSrc.replace("layout(binding = 2) uniform sampler2D depthTex;", "layout(binding = 8) uniform sampler2D depthTex;");
            // Disable depthTex discard comparison when depthTex is not a real depth buffer (avoids false bottom-viewport clipping)
            fragSrc = fragSrc.replace(
                    "if (DEPTH_SCALAR_COMPARE(gl_FragCoord.z, texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r)) {",
                    "if (false && DEPTH_SCALAR_COMPARE(gl_FragCoord.z, texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r)) {"
            );
            Map<String, String> opaqueFragDefines = new HashMap<>();
            opaqueFragDefines.put("LIGHTING_SAMPLER_BINDING", "7");
            opaqueFragDefines.put("USE_REVERSE_Z", "1");
            ByteBuffer opaqueFragSpv = VulkanSpirvCompiler.compile("quads.frag", fragSrc, VulkanSpirvCompiler.STAGE_FRAGMENT, opaqueFragDefines);

            Map<String, String> transFragDefines = new HashMap<>(opaqueFragDefines);
            transFragDefines.put("TRANSLUCENT", "1");
            ByteBuffer transFragSpv = VulkanSpirvCompiler.compile("quads_translucent.frag", fragSrc, VulkanSpirvCompiler.STAGE_FRAGMENT, transFragDefines);

            long vertModule = createShaderModule(vertSpv, stack);
            long opaqueFragModule = createShaderModule(opaqueFragSpv, stack);
            long transFragModule = createShaderModule(transFragSpv, stack);
            MemoryUtil.memFree(vertSpv);
            MemoryUtil.memFree(opaqueFragSpv);
            MemoryUtil.memFree(transFragSpv);

            // 4. Create Graphics Pipelines
            this.opaquePipeline = createGraphicsPipeline(vertModule, opaqueFragModule, colorFormat, depthFormat, false, stack);
            this.translucentPipeline = createGraphicsPipeline(vertModule, transFragModule, colorFormat, depthFormat, true, stack);

            // Shader modules can be destroyed after pipeline creation
            VK12.vkDestroyShaderModule(bridge.getDevice(), vertModule, null);
            VK12.vkDestroyShaderModule(bridge.getDevice(), opaqueFragModule, null);
            VK12.vkDestroyShaderModule(bridge.getDevice(), transFragModule, null);

            Logger.info("VulkanTerrainPipeline initialized successfully (opaque=" + this.opaquePipeline + ", translucent=" + this.translucentPipeline + ")");
        }
    }

    private long createShaderModule(ByteBuffer spv, MemoryStack stack) {
        VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(spv);
        LongBuffer pModule = stack.mallocLong(1);
        int res = VK12.vkCreateShaderModule(bridge.getDevice(), createInfo, null, pModule);
        if (res != VK12.VK_SUCCESS) {
            throw new RuntimeException("Failed to create shader module: " + res);
        }
        return pModule.get(0);
    }

    private long createGraphicsPipeline(long vertModule, long fragModule, int colorFormat, int depthFormat, boolean translucent, MemoryStack stack) {
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        stages.get(0).sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK12.VK_SHADER_STAGE_VERTEX_BIT)
                .module(vertModule)
                .pName(stack.UTF8("main"));
        stages.get(1).sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK12.VK_SHADER_STAGE_FRAGMENT_BIT)
                .module(fragModule)
                .pName(stack.UTF8("main"));

        VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);

        VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                .topology(VK12.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

        VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                .viewportCount(1)
                .scissorCount(1);

        VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                .polygonMode(VK12.VK_POLYGON_MODE_FILL)
                .cullMode(VK12.VK_CULL_MODE_NONE)
                .frontFace(VK12.VK_FRONT_FACE_COUNTER_CLOCKWISE)
                .lineWidth(1.0f);

        VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                .rasterizationSamples(VK12.VK_SAMPLE_COUNT_1_BIT);

        VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                .depthTestEnable(true)
                .depthWriteEnable(!translucent)
                .depthCompareOp(VK12.VK_COMPARE_OP_GREATER_OR_EQUAL); // Minecraft 26.3 Reverse-Z

        VkPipelineColorBlendAttachmentState.Buffer blendAttachments = VkPipelineColorBlendAttachmentState.calloc(1, stack);
        if (translucent) {
            blendAttachments.get(0)
                    .blendEnable(true)
                    .srcColorBlendFactor(VK12.VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK12.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK12.VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK12.VK_BLEND_FACTOR_ONE)
                    .dstAlphaBlendFactor(VK12.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .alphaBlendOp(VK12.VK_BLEND_OP_ADD)
                    .colorWriteMask(VK12.VK_COLOR_COMPONENT_R_BIT | VK12.VK_COLOR_COMPONENT_G_BIT | VK12.VK_COLOR_COMPONENT_B_BIT | VK12.VK_COLOR_COMPONENT_A_BIT);
        } else {
            blendAttachments.get(0)
                    .blendEnable(false)
                    .colorWriteMask(VK12.VK_COLOR_COMPONENT_R_BIT | VK12.VK_COLOR_COMPONENT_G_BIT | VK12.VK_COLOR_COMPONENT_B_BIT | VK12.VK_COLOR_COMPONENT_A_BIT);
        }

        VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                .pAttachments(blendAttachments);

        IntBuffer dynamicStates = stack.ints(VK12.VK_DYNAMIC_STATE_VIEWPORT, VK12.VK_DYNAMIC_STATE_SCISSOR);
        VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                .pDynamicStates(dynamicStates);

        // Dynamic rendering create info
        IntBuffer colorFormats = stack.ints(colorFormat);
        VkPipelineRenderingCreateInfoKHR renderingInfo = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                .sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR)
                .pColorAttachmentFormats(colorFormats)
                .depthAttachmentFormat(depthFormat);

        VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                .sType(VK12.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pNext(renderingInfo)
                .pStages(stages)
                .pVertexInputState(vertexInput)
                .pInputAssemblyState(inputAssembly)
                .pViewportState(viewportState)
                .pRasterizationState(rasterization)
                .pMultisampleState(multisample)
                .pDepthStencilState(depthStencil)
                .pColorBlendState(colorBlend)
                .pDynamicState(dynamicState)
                .layout(this.pipelineLayout)
                .subpass(0);

        LongBuffer pPipeline = stack.mallocLong(1);
        int res = VK12.vkCreateGraphicsPipelines(bridge.getDevice(), VK12.VK_NULL_HANDLE, pipelineInfo, null, pPipeline);
        if (res != VK12.VK_SUCCESS) {
            throw new RuntimeException("Failed to create graphics pipeline (translucent=" + translucent + "): " + res);
        }
        return pPipeline.get(0);
    }

    public void bindAndDraw(VkCommandBuffer cb, boolean translucent,
                            VulkanBuffer uniformBuffer, VulkanBuffer quadBuffer,
                            VulkanBuffer metadataBuffer, VulkanBuffer modelBuffer,
                            VulkanBuffer modelColourBuffer, VulkanBuffer positionBuffer,
                            long atlasView, long atlasSampler,
                            long lightmapView, long lightmapSampler,
                            long depthView, long depthSampler,
                            VulkanIndexBuffer indexBuffer, VulkanBuffer drawBuffer, int drawCount) {
        bindAndDraw(cb, translucent, uniformBuffer, quadBuffer, metadataBuffer, modelBuffer,
                modelColourBuffer, positionBuffer, atlasView, atlasSampler, lightmapView,
                lightmapSampler, depthView, depthSampler, indexBuffer, drawBuffer, 0L, drawCount);
    }

    public void bindAndDraw(VkCommandBuffer cb, boolean translucent,
                            VulkanBuffer uniformBuffer, VulkanBuffer quadBuffer,
                            VulkanBuffer metadataBuffer, VulkanBuffer modelBuffer,
                            VulkanBuffer modelColourBuffer, VulkanBuffer positionBuffer,
                            long atlasView, long atlasSampler,
                            long lightmapView, long lightmapSampler,
                            long depthView, long depthSampler,
                            VulkanIndexBuffer indexBuffer, VulkanBuffer drawBuffer,
                            long drawByteOffset, int drawCount) {
        long pipeline = translucent ? this.translucentPipeline : this.opaquePipeline;
        VK12.vkCmdBindPipeline(cb, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(9, stack);

            // 0: uniformBuffer
            VkDescriptorBufferInfo.Buffer uboInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(uniformBuffer.getHandle()).offset(0).range(uniformBuffer.getSize());
            writes.get(0).sType(VK12.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(0).descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pBufferInfo(uboInfo);

            // 1: quadBuffer
            VkDescriptorBufferInfo.Buffer quadInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(quadBuffer.getHandle()).offset(0).range(quadBuffer.getSize());
            writes.get(1).sType(VK12.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(1).descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(quadInfo);

            // 2: metadataBuffer
            VkDescriptorBufferInfo.Buffer metaInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(metadataBuffer.getHandle()).offset(0).range(metadataBuffer.getSize());
            writes.get(2).sType(VK12.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(2).descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(metaInfo);

            // 3: modelBuffer
            VkDescriptorBufferInfo.Buffer modelInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(modelBuffer.getHandle()).offset(0).range(modelBuffer.getSize());
            writes.get(3).sType(VK12.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(3).descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(modelInfo);

            // 4: modelColourBuffer
            VkDescriptorBufferInfo.Buffer colInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(modelColourBuffer.getHandle()).offset(0).range(modelColourBuffer.getSize());
            writes.get(4).sType(VK12.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(4).descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(colInfo);

            // 5: positionBuffer
            VkDescriptorBufferInfo.Buffer posInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(positionBuffer.getHandle()).offset(0).range(positionBuffer.getSize());
            writes.get(5).sType(VK12.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(5).descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(posInfo);

            // 6: atlas
            VkDescriptorImageInfo.Buffer atlasImageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(atlasSampler).imageView(atlasView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(6).sType(VK12.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(6).descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(atlasImageInfo);

            // 7: lightmap
            VkDescriptorImageInfo.Buffer lightImageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(lightmapSampler).imageView(lightmapView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(7).sType(VK12.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(7).descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(lightImageInfo);

            // 8: depth
            VkDescriptorImageInfo.Buffer depthImageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(depthSampler != 0 ? depthSampler : atlasSampler).imageView(depthView != 0 ? depthView : atlasView)
                    .imageLayout(VK12.VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL);
            writes.get(8).sType(VK12.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(8).descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(depthImageInfo);

            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipelineLayout, 0, writes);
        }

        // Bind Index Buffer
        indexBuffer.bind(cb);

        // Issue Indirect Draw
        VK12.vkCmdDrawIndexedIndirect(cb, drawBuffer.getHandle(), drawByteOffset, drawCount, 20);
    }

    public void free() {
        if (this.opaquePipeline != 0) {
            VK12.vkDestroyPipeline(bridge.getDevice(), this.opaquePipeline, null);
        }
        if (this.translucentPipeline != 0) {
            VK12.vkDestroyPipeline(bridge.getDevice(), this.translucentPipeline, null);
        }
        if (this.pipelineLayout != 0) {
            VK12.vkDestroyPipelineLayout(bridge.getDevice(), this.pipelineLayout, null);
        }
        if (this.descriptorSetLayout != 0) {
            VK12.vkDestroyDescriptorSetLayout(bridge.getDevice(), this.descriptorSetLayout, null);
        }
    }
}
