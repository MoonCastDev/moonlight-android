#version 450

// Full range YCbCr 4:2:0 in separate Y, Cb and Cr planes, as PyroWave decodes it. Adapted from
// Moonlight X's video_planar.frag (GPL-3.0), without its HDR and dither output stages.
layout(set = 0, binding = 0) uniform sampler2D uPlanes[3];

layout(push_constant) uniform PushConstants {
    vec4 uvClamp;
    vec4 params;
    vec4 ycbcr;
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv = clamp(vUv, pc.uvClamp.xy, pc.uvClamp.zw);
    float y = texture(uPlanes[0], uv).r;

    // Some hosts site chroma with the left luma sample of each pair, not between them
    vec2 chromaUv = uv + vec2(pc.ycbcr.w, 0.0);
    float cb = texture(uPlanes[1], chromaUv).r - pc.ycbcr.z;
    float cr = texture(uPlanes[2], chromaUv).r - pc.ycbcr.z;

    float kr = pc.ycbcr.x;
    float kb = pc.ycbcr.y;
    float r = y + 2.0 * (1.0 - kr) * cr;
    float b = y + 2.0 * (1.0 - kb) * cb;
    float g = (y - kr * r - kb * b) / (1.0 - kr - kb);
    outColor = vec4(clamp(vec3(r, g, b), 0.0, 1.0), 1.0);
}
