package me.cortex.voxy.client.core.vulkan.compute;

import me.cortex.voxy.client.core.vulkan.bridge.VulkanBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates a Vulkan Compute Pipeline, its layout, and push-descriptor binding.
 */
public class VulkanComputePipeline implements AutoCloseable {
    public record BindingDesc(int binding, int descriptorType) {}

    public record BufferBinding(int binding, int type, long buffer, long offset, long range) {
        public static BufferBinding storage(int binding, long buffer, long offset, long range) {
            return new BufferBinding(binding, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, buffer, offset, range);
        }
        public static BufferBinding storage(int binding, VulkanBuffer buffer) {
            return storage(binding, buffer.vkBuffer(), 0, buffer.size());
        }
        public static BufferBinding uniform(int binding, long buffer, long offset, long range) {
            return new BufferBinding(binding, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, buffer, offset, range);
        }
        public static BufferBinding uniform(int binding, VulkanBuffer buffer) {
            return uniform(binding, buffer.vkBuffer(), 0, buffer.size());
        }
    }

    private final long pipeline;
    private final long pipelineLayout;
    private final long descriptorSetLayout;
    private final List<BindingDesc> bindings;
    private boolean closed = false;

    public VulkanComputePipeline(VulkanBridge bridge, String name, String glslSource, java.util.Map<String, String> defines, int[] bindingIndices, int[] descriptorTypes) {
        this(name, VulkanSpirvCompiler.compile(name, glslSource, VulkanSpirvCompiler.STAGE_COMPUTE, defines),
                createBindingDescs(bindingIndices, descriptorTypes), 0);
    }

    private static List<BindingDesc> createBindingDescs(int[] bindingIndices, int[] descriptorTypes) {
        List<BindingDesc> list = new ArrayList<>();
        for (int i = 0; i < bindingIndices.length; i++) {
            list.add(new BindingDesc(bindingIndices[i], descriptorTypes[i]));
        }
        return list;
    }

    public VulkanComputePipeline(String name, ByteBuffer spirv, List<BindingDesc> bindings, int pushConstantSize) {
        this.bindings = new ArrayList<>(bindings);
        var bridge = VulkanBridge.instance();
        var device = bridge.getVkDevice();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Create shader module
            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default();
            moduleInfo.pCode(spirv);
            LongBuffer modulePtr = stack.callocLong(1);
            int res = VK12.vkCreateShaderModule(device, moduleInfo, null, modulePtr);
            if (res != VK12.VK_SUCCESS) {
                throw new RuntimeException("Failed to create compute shader module for [" + name + "], error=" + res);
            }
            long shaderModule = modulePtr.get(0);

            try {
                // 2. Create descriptor set layout with PUSH_DESCRIPTOR flag
                VkDescriptorSetLayoutBinding.Buffer layoutBindings = VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);
                for (int i = 0; i < bindings.size(); i++) {
                    BindingDesc b = bindings.get(i);
                    VkDescriptorSetLayoutBinding lb = layoutBindings.get(i);
                    lb.binding(b.binding());
                    lb.descriptorType(b.descriptorType());
                    lb.descriptorCount(1);
                    lb.stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
                }
                VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default();
                layoutInfo.flags(1); // VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR = 1
                layoutInfo.pBindings(layoutBindings);
                LongBuffer dsLayoutPtr = stack.callocLong(1);
                res = VK12.vkCreateDescriptorSetLayout(device, layoutInfo, null, dsLayoutPtr);
                if (res != VK12.VK_SUCCESS) {
                    throw new RuntimeException("Failed to create descriptor set layout for [" + name + "], error=" + res);
                }
                this.descriptorSetLayout = dsLayoutPtr.get(0);

                // 3. Create pipeline layout
                VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
                LongBuffer setLayouts = stack.callocLong(1);
                setLayouts.put(0, this.descriptorSetLayout);
                pipelineLayoutInfo.pSetLayouts(setLayouts);

                if (pushConstantSize > 0) {
                    VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.calloc(1, stack);
                    pushConstants.stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
                    pushConstants.offset(0);
                    pushConstants.size(pushConstantSize);
                    pipelineLayoutInfo.pPushConstantRanges(pushConstants);
                }

                LongBuffer pipeLayoutPtr = stack.callocLong(1);
                res = VK12.vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pipeLayoutPtr);
                if (res != VK12.VK_SUCCESS) {
                    throw new RuntimeException("Failed to create pipeline layout for [" + name + "], error=" + res);
                }
                this.pipelineLayout = pipeLayoutPtr.get(0);

                // 4. Create compute pipeline
                VkComputePipelineCreateInfo.Buffer computeInfo = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default();
                computeInfo.stage().sType$Default();
                computeInfo.stage().stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
                computeInfo.stage().module(shaderModule);
                computeInfo.stage().pName(stack.UTF8("main"));
                computeInfo.layout(this.pipelineLayout);

                LongBuffer pipePtr = stack.callocLong(1);
                res = VK12.vkCreateComputePipelines(device, 0, computeInfo, null, pipePtr);
                if (res != VK12.VK_SUCCESS) {
                    throw new RuntimeException("Failed to create compute pipeline for [" + name + "], error=" + res);
                }
                this.pipeline = pipePtr.get(0);
            } finally {
                VK12.vkDestroyShaderModule(device, shaderModule, null);
            }
        }
    }

    public void bind(VkCommandBuffer cb) {
        VK12.vkCmdBindPipeline(cb, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
    }

    public void pushBuffers(VkCommandBuffer cb, BufferBinding... bufferBindings) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(bufferBindings.length, stack);
            for (int i = 0; i < bufferBindings.length; i++) {
                BufferBinding bb = bufferBindings[i];
                VkDescriptorBufferInfo.Buffer bufInfo = VkDescriptorBufferInfo.calloc(1, stack);
                bufInfo.buffer(bb.buffer());
                bufInfo.offset(bb.offset());
                bufInfo.range(bb.range());

                VkWriteDescriptorSet write = writes.get(i).sType$Default();
                write.dstBinding(bb.binding());
                write.dstArrayElement(0);
                write.descriptorCount(1);
                write.descriptorType(bb.type());
                write.pBufferInfo(bufInfo);
            }
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0, writes);
        }
    }

    public void pushBuffers(VkCommandBuffer cb, VulkanBuffer[] buffers) {
        bind(cb);
        BufferBinding[] bbs = new BufferBinding[buffers.length];
        for (int i = 0; i < buffers.length; i++) {
            BindingDesc desc = this.bindings.get(i);
            bbs[i] = new BufferBinding(desc.binding(), desc.descriptorType(), buffers[i].vkBuffer(), 0, buffers[i].size());
        }
        pushBuffers(cb, bbs);
    }

    public void free() {
        close();
    }

    public void dispatch(VkCommandBuffer cb, int groupCountX, int groupCountY, int groupCountZ) {
        VK12.vkCmdDispatch(cb, groupCountX, groupCountY, groupCountZ);
    }

    public void dispatchIndirect(VkCommandBuffer cb, long indirectBuffer, long offset) {
        VK12.vkCmdDispatchIndirect(cb, indirectBuffer, offset);
    }

    public long pipeline() {
        return this.pipeline;
    }

    public long pipelineLayout() {
        return this.pipelineLayout;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            long pipe = this.pipeline;
            long pLayout = this.pipelineLayout;
            long dsLayout = this.descriptorSetLayout;
            VulkanBridge.instance().queueForDestroy(() -> {
                var dev = VulkanBridge.instance().getVkDevice();
                VK12.vkDestroyPipeline(dev, pipe, null);
                VK12.vkDestroyPipelineLayout(dev, pLayout, null);
                VK12.vkDestroyDescriptorSetLayout(dev, dsLayout, null);
            });
        }
    }
}
