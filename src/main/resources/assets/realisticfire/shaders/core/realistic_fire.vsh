#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform float GameTime;

out vec4 vertexColor;
out vec2 texCoord0;
out float softMask;
out vec3 vertexPosition;
out float swayWeight;

void main() {
    // Normal.x carries a sway weight assigned by the renderer:
    //   0.0 → static geometry (ground char, ember veins) — no displacement.
    //   >0.0 → flame ribbons / billboards — top vertices sway and rise.
    float sway = clamp(abs(Normal.x), 0.0, 1.0);
    swayWeight = sway;

    // Top of the quad has UV0.y == 0 in this renderer, so (1 - UV0.y) is the "tip" weight.
    float topWeight = clamp(1.0 - UV0.y, 0.0, 1.0);
    float t = GameTime * 1200.0;
    // Flames stand VERTICAL: only a subtle multi-octave horizontal shimmer (so tips quiver, not
    // lean over), with the bulk of the motion in the VERTICAL lick/bob so they dance upward like
    // real fire. The 3x-finer ribbon subdivision gives each sub-quad a distinct Position.x/z, so
    // neighbouring tongues flicker out of sync for free.
    float phase  = Position.x * 2.6 + Position.z * 1.9 + t * 0.42;
    float phase2 = Position.x * 5.7 - Position.z * 4.3 + t * 0.97;
    float phase3 = t * 1.7 + Position.x * 9.1;
    float licking = sway * topWeight;

    vec3 displaced = Position;
    displaced.x += (sin(phase) * 0.020 + sin(phase2) * 0.012 + sin(phase3) * 0.007) * licking;
    displaced.z += (cos(phase * 0.83 + 1.31) * 0.018 + cos(phase2 * 1.21 + 0.6) * 0.010) * licking;
    displaced.y += licking * (0.022 + 0.034 * sin(phase * 1.7) + 0.018 * sin(phase2 * 0.9));

    gl_Position = ProjMat * ModelViewMat * vec4(displaced, 1.0);
    vertexColor = Color;
    texCoord0 = UV0;
    vertexPosition = displaced;

    float xFeather = 1.0 - abs(UV0.x - 0.5) * 2.0;
    float yFeather = 1.0 - abs(UV0.y - 0.5) * 2.0;
    float rounded = smoothstep(0.0, 0.9, clamp(xFeather * yFeather, 0.0, 1.0));
    softMask = rounded;
}
