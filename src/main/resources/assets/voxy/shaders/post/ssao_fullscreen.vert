#version 450 core
layout(location = 0) out vec2 outUV;

void main() {
    // Generate fullscreen triangle covering NDC [-1, 1] without vertex buffers
    outUV = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(outUV * 2.0f - 1.0f, 0.0f, 1.0f);
}
