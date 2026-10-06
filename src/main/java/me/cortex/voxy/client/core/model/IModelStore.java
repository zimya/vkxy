package me.cortex.voxy.client.core.model;

import me.cortex.voxy.common.util.MemoryBuffer;

public interface IModelStore {
    int MODEL_SIZE = 64;

    void uploadModel(int modelId, MemoryBuffer data);

    void uploadModelColour(int biomeUploadIndex, MemoryBuffer data);

    void uploadTexture(int modelId, MemoryBuffer textureData, boolean hasMips);

    void uploadBiomes(MemoryBuffer biomeColourBuffer, MemoryBuffer modelBiomeIndexPairs);

    void flushUploads();

    void free();
}
