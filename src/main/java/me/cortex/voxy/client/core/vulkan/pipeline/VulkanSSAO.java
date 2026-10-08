package me.cortex.voxy.client.core.vulkan.pipeline;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.vulkan.bridge.VulkanBridge;
import me.cortex.voxy.client.core.vulkan.compute.VulkanBuffer;
import me.cortex.voxy.client.core.vulkan.compute.VulkanSpirvCompiler;
import me.cortex.voxy.common.Logger;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Map;

/**
 * Screen-Space Ambient Occlusion (SSAO) post-processing pass for Vulkan.
 * Implements Photon/Voxy's SSAO algorithm directly in Vulkan via dynamic rendering
 * and multiplicative blending onto the scene color target.
 */
public class VulkanSSAO {
    public static final int UNIFORM_BUFFER_SIZE = 256;

    private final VulkanBridge bridge;
    private final int colorFormat;
    private final VulkanBuffer uniformBuffer;

    private long descriptorSetLayout;
    private long pipelineLayout;
    private long pipeline;

    public VulkanSSAO(VulkanBridge bridge, int colorFormat) {
        this.bridge = bridge;
        this.colorFormat = colorFormat;
        this.uniformBuffer = VulkanBuffer.createHostVisible(bridge, UNIFORM_BUFFER_SIZE, VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        initPipeline();
    }

    private void initPipeline() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Create Descriptor Set Layout with Push Descriptors
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);

            // 0: SSAOUniforms (uniformBuffer)
            bindings.get(0).binding(0)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK12.VK_SHADER_STAGE_FRAGMENT_BIT);

            // 1: depthTex (sampler2D)
            bindings.get(1).binding(1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK12.VK_SHADER_STAGE_FRAGMENT_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
                    .pBindings(bindings);

            LongBuffer pSetLayout = stack.mallocLong(1);
            int res = VK12.vkCreateDescriptorSetLayout(this.bridge.getDevice(), layoutInfo, null, pSetLayout);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("Failed to create SSAO descriptor set layout: " + res);
            }
            this.descriptorSetLayout = pSetLayout.get(0);

            // 2. Create Pipeline Layout
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(pSetLayout);

            LongBuffer pPipelineLayout = stack.mallocLong(1);
            res = VK12.vkCreatePipelineLayout(this.bridge.getDevice(), pipelineLayoutInfo, null, pPipelineLayout);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("Failed to create SSAO pipeline layout: " + res);
            }
            this.pipelineLayout = pPipelineLayout.get(0);

            // 3. Compile Shaders to SPIR-V
            String vertSrc = ShaderLoader.parse("voxy:post/ssao_fullscreen.vert");
            ByteBuffer vertSpv = VulkanSpirvCompiler.compile("ssao_fullscreen.vert", vertSrc, VulkanSpirvCompiler.STAGE_VERTEX, Map.of());

            String fragSrc = ShaderLoader.parse("voxy:post/ssao_raster.frag");
            ByteBuffer fragSpv = VulkanSpirvCompiler.compile("ssao_raster.frag", fragSrc, VulkanSpirvCompiler.STAGE_FRAGMENT, Map.of());

            long vertModule = createShaderModule(vertSpv, stack);
            long fragModule = createShaderModule(fragSpv, stack);
            MemoryUtil.memFree(vertSpv);
            MemoryUtil.memFree(fragSpv);

            // 4. Create Graphics Pipeline with Multiplicative Blending
            this.pipeline = createGraphicsPipeline(vertModule, fragModule, this.colorFormat, stack);

            VK12.vkDestroyShaderModule(this.bridge.getDevice(), vertModule, null);
            VK12.vkDestroyShaderModule(this.bridge.getDevice(), fragModule, null);

            Logger.info("VulkanSSAO initialized successfully (pipeline=" + this.pipeline + ")");
        }
    }

    private long createShaderModule(ByteBuffer spv, MemoryStack stack) {
        VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(spv);
        LongBuffer pModule = stack.mallocLong(1);
        int res = VK12.vkCreateShaderModule(this.bridge.getDevice(), createInfo, null, pModule);
        if (res != VK12.VK_SUCCESS) {
            throw new RuntimeException("Failed to create shader module for SSAO: " + res);
        }
        return pModule.get(0);
    }

    private long createGraphicsPipeline(long vertModule, long fragModule, int colorFormat, MemoryStack stack) {
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
                .depthTestEnable(false)
                .depthWriteEnable(false);

        // Multiplicative blend: DstColor = DstColor * SrcColor (ao factor)
        VkPipelineColorBlendAttachmentState.Buffer blendAttachments = VkPipelineColorBlendAttachmentState.calloc(1, stack);
        blendAttachments.get(0)
                .blendEnable(true)
                .srcColorBlendFactor(VK12.VK_BLEND_FACTOR_DST_COLOR)
                .dstColorBlendFactor(VK12.VK_BLEND_FACTOR_ZERO)
                .colorBlendOp(VK12.VK_BLEND_OP_ADD)
                .srcAlphaBlendFactor(VK12.VK_BLEND_FACTOR_ZERO)
                .dstAlphaBlendFactor(VK12.VK_BLEND_FACTOR_ONE)
                .alphaBlendOp(VK12.VK_BLEND_OP_ADD)
                .colorWriteMask(VK12.VK_COLOR_COMPONENT_R_BIT | VK12.VK_COLOR_COMPONENT_G_BIT | VK12.VK_COLOR_COMPONENT_B_BIT | VK12.VK_COLOR_COMPONENT_A_BIT);

        VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                .pAttachments(blendAttachments);

        IntBuffer dynamicStates = stack.ints(VK12.VK_DYNAMIC_STATE_VIEWPORT, VK12.VK_DYNAMIC_STATE_SCISSOR);
        VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType(VK12.VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                .pDynamicStates(dynamicStates);

        // Dynamic rendering: only color attachment, no depth attachment
        IntBuffer colorFormats = stack.ints(colorFormat);
        VkPipelineRenderingCreateInfoKHR renderingInfo = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                .sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR)
                .pColorAttachmentFormats(colorFormats)
                .depthAttachmentFormat(0);

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
        int res = VK12.vkCreateGraphicsPipelines(this.bridge.getDevice(), VK12.VK_NULL_HANDLE, pipelineInfo, null, pPipeline);
        if (res != VK12.VK_SUCCESS) {
            throw new RuntimeException("Failed to create SSAO graphics pipeline: " + res);
        }
        return pPipeline.get(0);
    }

    public void render(VkCommandBuffer cb, long depthImageView, long depthSampler, Matrix4fc proj, int width, int height, float vanillaRd) {
        if (this.pipeline == 0 || depthImageView == 0 || depthSampler == 0) return;

        var mode = VoxyConfig.CONFIG.getSSAOMode();
        float radius = 1.6f;
        float power = 2.2f;
        float intensity = 1.0f;
        if (mode == SSAO.SSAOMode.BASIC) {
            power = 1.5f;
            radius = 1.2f;
        } else if (mode == SSAO.SSAOMode.BEST) {
            power = 2.5f;
            radius = 1.8f;
        }

        // Upload uniforms
        long ptr = this.uniformBuffer.map();
        // 0..63: Proj
        new Matrix4f(proj).getToAddress(ptr);
        ptr += 64;

        // 64..127: invProj
        new Matrix4f(proj).invert().getToAddress(ptr);
        ptr += 64;

        // 128..143: screenSize
        MemoryUtil.memPutFloat(ptr, (float) width); ptr += 4;
        MemoryUtil.memPutFloat(ptr, (float) height); ptr += 4;
        MemoryUtil.memPutFloat(ptr, 1.0f / (float) width); ptr += 4;
        MemoryUtil.memPutFloat(ptr, 1.0f / (float) height); ptr += 4;

        // 144..159: ssaoParams (radius, maxScreenRadius, power, intensity)
        MemoryUtil.memPutFloat(ptr, radius); ptr += 4;
        MemoryUtil.memPutFloat(ptr, 0.08f); ptr += 4;
        MemoryUtil.memPutFloat(ptr, power); ptr += 4;
        MemoryUtil.memPutFloat(ptr, intensity); ptr += 4;

        // 160..175: fadeParams (vanillaRd, fadeStart, minAoFloor, unused)
        float fadeStart = Math.max(0.0f, vanillaRd - 16.0f);
        MemoryUtil.memPutFloat(ptr, vanillaRd); ptr += 4;
        MemoryUtil.memPutFloat(ptr, fadeStart); ptr += 4;
        MemoryUtil.memPutFloat(ptr, 0.45f); ptr += 4;
        MemoryUtil.memPutFloat(ptr, 0.0f); ptr += 4;

        VK12.vkCmdBindPipeline(cb, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipeline);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);

            // 0: uniformBuffer
            VkDescriptorBufferInfo.Buffer uboInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(this.uniformBuffer.getHandle()).offset(0).range(UNIFORM_BUFFER_SIZE);
            writes.get(0).sType(VK12.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(0).descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pBufferInfo(uboInfo);

            // 1: depthTex (depthImageView + depthSampler)
            VkDescriptorImageInfo.Buffer depthImageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(depthSampler).imageView(depthImageView)
                    .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            writes.get(1).sType(VK12.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstBinding(1).descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(depthImageInfo);

            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipelineLayout, 0, writes);
        }

        // Issue fullscreen triangle draw
        VK12.vkCmdDraw(cb, 3, 1, 0, 0);
    }

    public void free() {
        if (this.pipeline != 0) {
            VK12.vkDestroyPipeline(this.bridge.getDevice(), this.pipeline, null);
            this.pipeline = 0;
        }
        if (this.pipelineLayout != 0) {
            VK12.vkDestroyPipelineLayout(this.bridge.getDevice(), this.pipelineLayout, null);
            this.pipelineLayout = 0;
        }
        if (this.descriptorSetLayout != 0) {
            VK12.vkDestroyDescriptorSetLayout(this.bridge.getDevice(), this.descriptorSetLayout, null);
            this.descriptorSetLayout = 0;
        }
        if (this.uniformBuffer != null) {
            this.uniformBuffer.free();
        }
    }
}
