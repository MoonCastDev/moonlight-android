#version 450

// Full range YCbCr in separate Y, Cb and Cr planes (4:2:0 or 4:4:4), as PyroWave decodes it, to the
// swapchain: as it is (SDR, 10-bit SDR, or PQ HDR10 on an HDR10 swapchain), or PQ HDR10 tone mapped
// to SDR where the screen can't take HDR10, with optional dithering. Adapted from Moonlight X's
// video_planar.frag and video_common.glsl (GPL-3.0).
layout(set = 0, binding = 0) uniform sampler2D uPlanes[3];

// Keep in sync with pyrowave.vert and PushConstants in pyrowave_renderer.cpp
layout(push_constant) uniform PushConstants {
    vec4 uvClamp;
    vec4 params;   // x = quarter turns, y = dither amplitude, z = frame counter, w = output mode
    vec4 params2;  // x = content peak nits, y = SDR reference white nits
    vec4 ycbcr;    // x = Kr, y = Kb, z = chroma midpoint, w = chroma U offset
} pc;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

const float OUTPUT_PQ_TO_SDR = 1.0;

// Interleaved gradient noise (Jimenez 2014): fine grain rather than blotches
float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// SMPTE ST 2084 EOTF, returning linear light where 1.0 is 10000 nits
vec3 pqToLinear(vec3 e) {
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    vec3 ep = pow(max(e, 0.0), vec3(1.0 / m2));
    return pow(max(ep - c1, 0.0) / (c2 - c3 * ep), vec3(1.0 / m1));
}

// HDR10 content on a surface that can't take it: map to SDR BT.709
vec3 pqToSdr(vec3 e) {
    float sdrWhite = pc.params2.y;
    vec3 lin = pqToLinear(e) * (10000.0 / sdrWhite);

    // BT.2020 to BT.709 primaries (column-major)
    lin = mat3(1.6605, -0.1246, -0.0182,
              -0.5876,  1.1329, -0.1006,
              -0.0728, -0.0083,  1.1187) * lin;
    lin = max(lin, 0.0);

    // Extended Reinhard on luminance, with the content peak mapped to SDR white
    float white = max(pc.params2.x / sdrWhite, 1.0);
    float l = dot(lin, vec3(0.2126, 0.7152, 0.0722));
    if (l > 0.0) {
        float lt = l * (1.0 + l / (white * white)) / (1.0 + l);
        lin *= lt / l;
    }
    return pow(clamp(lin, 0.0, 1.0), vec3(1.0 / 2.2));
}

void main() {
    vec2 uv = clamp(vUv, pc.uvClamp.xy, pc.uvClamp.zw);
    float y = texture(uPlanes[0], uv).r;

    // Some hosts site 4:2:0 chroma with the left luma sample of each pair, not between them
    vec2 chromaUv = uv + vec2(pc.ycbcr.w, 0.0);
    float cb = texture(uPlanes[1], chromaUv).r - pc.ycbcr.z;
    float cr = texture(uPlanes[2], chromaUv).r - pc.ycbcr.z;

    float kr = pc.ycbcr.x;
    float kb = pc.ycbcr.y;
    float r = y + 2.0 * (1.0 - kr) * cr;
    float b = y + 2.0 * (1.0 - kb) * cb;
    float g = (y - kr * r - kb * b) / (1.0 - kr - kb);
    vec3 color = clamp(vec3(r, g, b), 0.0, 1.0);

    if (pc.params.w == OUTPUT_PQ_TO_SDR) {
        color = pqToSdr(color);
    }

    float amplitude = pc.params.y;
    if (amplitude > 0.0) {
        // Triangular dither of +/- 1 LSB from two decorrelated noise samples, moved each frame
        vec2 p = gl_FragCoord.xy + 5.588238 * mod(pc.params.z, 64.0);
        float n = ign(p) + ign(p + vec2(47.0, 17.0)) - 1.0;
        color += n * amplitude;
    }
    outColor = vec4(color, 1.0);
}
