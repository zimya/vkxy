#version 450 core

layout(location = 0) in vec2 inUV;
layout(location = 0) out vec4 outColor;

layout(binding = 0, std140) uniform SSAOUniforms {
    mat4 Proj;
    mat4 invProj;
    vec4 screenSize; // x: width, y: height, z: 1/width, w: 1/height
    vec4 ssaoParams; // x: radius (1.5), y: maxScreenRadius (0.08), z: power (2.5), w: intensity (1.0)
    vec4 fadeParams; // x: vanillaRd, y: fadeStart, z: minAoFloor, w: unused
};

layout(binding = 1) uniform sampler2D depthTex;

// Photon SSAO algorithm from original Voxy ssao.comp
#define SSAO_STEPS 16

const float tau = 6.2831853;
const float phi1 = 1.6180339887;

float r1(int n, float seed) {
    const float alpha = 1.0 / phi1;
    return fract(seed + n * alpha);
}

vec2 get_ssao_sample_offset(int step_index, vec2 dither) {
    float a = (float(step_index) + dither.x) * (1.0f / float(SSAO_STEPS));
    float r = sqrt(r1(step_index, dither.y));
    float theta = a * tau;
    return r * vec2(cos(theta), sin(theta));
}

vec3 rev3d(mat4 matrix, vec3 clip) {
    // Vulkan NDC: x in [-1, 1], y in [-1, 1], z in [0, 1]
    vec4 view = matrix * vec4(clip.xy * 2.0f - 1.0f, clip.z, 1.0f);
    return view.xyz / view.w;
}

vec3 projProj(vec3 point) {
    vec4 aa = Proj * vec4(point, 1.0f);
    vec3 ndc = aa.xyz / aa.w;
    return vec3(ndc.xy * 0.5f + 0.5f, ndc.z);
}

mat3 get_tbn_matrix(vec3 normal) {
    vec3 tangent = abs(normal.y) > 0.999f
        ? vec3(1.0, 0.0, 0.0)
        : normalize(cross(vec3(0.0, 1.0, 0.0), normal));
    vec3 bitangent = normalize(cross(tangent, normal));
    return mat3(tangent, bitangent, normal);
}

vec2 clamp_length(vec2 a, float minLen, float maxLen) {
    float len = length(a);
    return len > 0.0f ? (a / len) * clamp(len, minLen, maxLen) : vec2(0.0f);
}

void main() {
    float depth = texture(depthTex, inUV).r;

    // In Minecraft 26.3 Reverse-Z:
    // Near is 1.0, Far (sky/void) is 0.0.
    if (depth <= 0.0001f || depth >= 0.9999f) {
        outColor = vec4(1.0f);
        return;
    }

    vec3 position_view = rev3d(invProj, vec3(inUV, depth));
    float viewDist = length(position_view);

    // Calculate LOD fade: near Vanilla terrain already has native vertex AO,
    // so SSAO fades in around the LOD boundary to avoid double-darkening near shadows.
    float fadeStart = fadeParams.y > 0.0f ? fadeParams.y : (fadeParams.x - 16.0f);
    float fadeEnd = fadeParams.x > 0.0f ? fadeParams.x : (fadeStart + 32.0f);
    float lodWeight = clamp((viewDist - fadeStart) / max(fadeEnd - fadeStart, 1.0f), 0.0f, 1.0f);
    lodWeight = smoothstep(0.0f, 1.0f, lodWeight);

    if (lodWeight <= 0.001f) {
        outColor = vec4(1.0f);
        return;
    }

    // Reconstruct view-space normal from depth derivatives
    vec3 dPdx = dFdx(position_view);
    vec3 dPdy = dFdy(position_view);
    vec3 viewNormal = cross(dPdy, dPdx);
    float normLen = length(viewNormal);
    if (normLen < 1e-6f) {
        outColor = vec4(1.0f);
        return;
    }
    viewNormal /= normLen;

    // Mathematically robust normal orientation:
    // A visible surface facing the camera must have its normal point back towards camera (dot with position_view < 0).
    // This prevents any sudden normal flips across terrain slopes and dips.
    if (dot(viewNormal, position_view) > 0.0f) {
        viewNormal = -viewNormal;
    }

    mat3 tbn_matrix = get_tbn_matrix(viewNormal);

    float radius = ssaoParams.x > 0.0f ? ssaoParams.x : 1.5f;
    float maxScreenRadius = ssaoParams.y > 0.0f ? ssaoParams.y : 0.08f;

    vec2 projT = projProj(position_view + tbn_matrix[0]).xy - inUV;
    vec2 projB = projProj(position_view + tbn_matrix[1]).xy - inUV;

    mat2 sample_matrix = radius * mat2(
        clamp_length(projT, 0.0f, maxScreenRadius),
        clamp_length(projB, 0.0f, maxScreenRadius)
    );

    float ao = 0.0f;
    float maxDist = radius * 2.5f;

    // Simple screen-position dither to reduce banding
    vec2 dither = fract(gl_FragCoord.xy * 0.5f);

    for (int i = 0; i < SSAO_STEPS; ++i) {
        vec2 offset = get_ssao_sample_offset(i, dither);
        vec2 sample_uv = clamp(inUV + sample_matrix * offset, vec2(0.0f), vec2(1.0f));

        float sample_depth = texture(depthTex, sample_uv).r;
        if (sample_depth <= 0.0001f || abs(sample_depth - depth) < 1e-6f) {
            continue;
        }

        vec3 offset_view = rev3d(invProj, vec3(sample_uv, sample_depth)) - position_view;
        float slen = dot(offset_view, offset_view);
        if (slen > maxDist * maxDist || slen < 1e-4f) {
            continue;
        }

        float rlen = inversesqrt(slen);
        // Surface bias (-0.05f) prevents flat surfaces from false self-occlusion
        float angle = dot(offset_view, viewNormal) * rlen;
        float cos_theta = clamp(angle - 0.05f, 0.0f, 1.0f);
        float distance_falloff = 1.0f / (1.0f + (1.0f / rlen) * (1.0f / radius));

        ao += cos_theta * distance_falloff;
    }

    float aoVal = clamp(1.0f - ao * (1.0f / float(SSAO_STEPS)), 0.0f, 1.0f);
    float power = ssaoParams.z > 0.0f ? ssaoParams.z : 2.5f;
    float aoFactor = pow(aoVal, power);

    // Clamp maximum darkening to prevent pitch black crevices
    float minAoFloor = fadeParams.z > 0.0f ? fadeParams.z : 0.45f;
    aoFactor = max(aoFactor, minAoFloor);

    // Apply intensity multiplier if configured
    if (ssaoParams.w > 0.0f) {
        aoFactor = mix(1.0f, aoFactor, ssaoParams.w);
    }

    // Modulate AO by LOD distance weight: near vanilla chunks keep vanilla shading, distant LOD terrain gets AO
    aoFactor = mix(1.0f, aoFactor, lodWeight);

    // Output multiplicative factor for blending
    outColor = vec4(aoFactor, aoFactor, aoFactor, 1.0f);
}
