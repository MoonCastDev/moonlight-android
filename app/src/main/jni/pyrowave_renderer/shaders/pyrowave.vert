#version 450

// Adapted from Moonlight X's video.vert (GPL-3.0). Keep the push constants in sync with
// pyrowave.frag and PushConstants in pyrowave_renderer.cpp.
layout(push_constant) uniform PushConstants {
    vec4 uvClamp;  // xy = min UV, zw = max UV (half a texel inside the picture)
    vec4 params;   // x = quarter turns clockwise to pre-rotate by, y = dither amplitude, z = frame counter,
                   // w = output mode (0 = as is, 1 = PQ HDR10 tone mapped to SDR)
    vec4 params2;  // x = content peak nits, y = SDR reference white nits
    vec4 ycbcr;    // x = Kr, y = Kb, z = chroma midpoint, w = chroma U offset
} pc;

layout(location = 0) out vec2 vUv;

void main() {
    // One triangle that covers the whole viewport: (0,0), (2,0), (0,2)
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    vUv = pos;

    // The swapchain is in the display's natural orientation, so turn the picture into it here
    // rather than leave the compositor to. Clockwise on screen, with y pointing down.
    float turn = pc.params.x * 1.5707963;
    float c = cos(turn);
    float s = sin(turn);
    vec2 clip = pos * 2.0 - 1.0;
    gl_Position = vec4(c * clip.x - s * clip.y, s * clip.x + c * clip.y, 0.0, 1.0);
}
