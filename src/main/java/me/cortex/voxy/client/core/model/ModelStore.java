package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.common.util.GlobalCleaner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

import java.lang.ref.Cleaner;

import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBDirectStateAccess.nglTextureSubImage2D;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LOD;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MIN_LOD;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;

public class ModelStore implements IModelStore {
    public static final int MODEL_SIZE = 64;
    private Cleaner.Cleanable ref;
    final GlBuffer modelBuffer;
    final GlBuffer modelColourBuffer;
    final GlTexture textures;
    public final int blockSampler = glGenSamplers();

    public ModelStore() {
        this.modelBuffer = new GlBuffer(MODEL_SIZE * (1<<16)).name("ModelData");
        this.modelColourBuffer = new GlBuffer(4 * (1<<16)).name("ModelColour");
        var tex = this.textures = RenderResourceReuse.getOrCreateModelStoreTextureAtlas();
        this.ref = GlobalCleaner.CLEANER.register(this, ()->RenderResourceReuse.giveBackModelStoreTextureAtlas(tex));

        //Limit the mips of the texture to match that of the terrain atlas
        int mipLvl = ((TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")))
                .maxMipLevel;

        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_LOD, 0);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAX_LOD, mipLvl);//Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE)
    }

    @Override
    public void uploadModel(int modelId, MemoryBuffer data) {
        data.cpyTo(UploadStream.INSTANCE.upload(this.modelBuffer, (long) modelId * MODEL_SIZE, MODEL_SIZE));
    }

    @Override
    public void uploadModelColour(int biomeUploadIndex, MemoryBuffer data) {
        data.cpyTo(UploadStream.INSTANCE.upload(this.modelColourBuffer, biomeUploadIndex * 4L, data.size));
    }

    @Override
    public void uploadTexture(int modelId, MemoryBuffer texture, boolean hasMips) {
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);

        int X = (modelId & 0xFF) * ModelFactory.MODEL_TEXTURE_SIZE * 3;
        int Y = ((modelId >> 8) & 0xFF) * ModelFactory.MODEL_TEXTURE_SIZE * 2;
        long cAddr = texture.address;
        int layers = hasMips ? ModelFactory.LAYERS : 1;
        for (int lvl = 0; lvl < layers; lvl++) {
            nglTextureSubImage2D(this.textures.id, lvl, X >> lvl, Y >> lvl,
                    (ModelFactory.MODEL_TEXTURE_SIZE * 3) >> lvl,
                    (ModelFactory.MODEL_TEXTURE_SIZE * 2) >> lvl,
                    GL_RGBA, GL_UNSIGNED_BYTE, cAddr);
            cAddr += (ModelFactory.MODEL_TEXTURE_SIZE * ModelFactory.MODEL_TEXTURE_SIZE * 3 * 2 * 4) >> (lvl << 1);
        }
    }

    @Override
    public void uploadBiomes(MemoryBuffer biomeColourBuffer, MemoryBuffer modelBiomeIndexPairs) {
        biomeColourBuffer.cpyTo(UploadStream.INSTANCE.upload(this.modelColourBuffer, 0, biomeColourBuffer.size));
        long ptr = modelBiomeIndexPairs.address;
        for (long offset = 0; offset < modelBiomeIndexPairs.size; offset += 8) {
            long v = MemoryUtil.memGetLong(ptr); ptr += 8;
            MemoryUtil.memPutInt(UploadStream.INSTANCE.upload(this.modelBuffer, (MODEL_SIZE * (v & ((1L << 32) - 1))) + 4 * 6 + 4, 4), (int) (v >>> 32));
        }
    }

    @Override
    public void flushUploads() {
        if (UploadStream.INSTANCE != null) {
            UploadStream.INSTANCE.commit();
        }
    }

    @Override
    public void free() {
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        this.ref.clean();
        glDeleteSamplers(this.blockSampler);
    }


    public void bind(int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, modelBindingIndex, this.modelBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, colourBindingIndex, this.modelColourBuffer.id);
        glBindTextureUnit(textureBindingIndex, this.textures.id);
        glBindSampler(textureBindingIndex, this.blockSampler);
    }
}
