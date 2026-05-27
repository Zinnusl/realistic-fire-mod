#version 150

// Ash-ground mask shader. The renderer feeds UV0 = the vertex's WORLD (x, z) and Color.a = the
// per-corner reveal mask (how far inside the burning contour this corner is). The fragment shader
// tiles the ash texture by fract(world) and discards fragments the fire front hasn't reached yet,
// so the ash is uncovered like a mask wiped away behind the front — fully opaque, never blended.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;
out vec2 worldUv;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    worldUv = UV0;
}
