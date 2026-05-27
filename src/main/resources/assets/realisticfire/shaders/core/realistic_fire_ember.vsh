#version 150

// Ember-oval shader (additive). The renderer draws the embers as the SAME filled oval as the ash
// (so they follow the fire front identically), feeding UV0 = world (x, z) and Color.a = the
// footprint's ember glow. The fragment shader tiles the sparse ember-spark texture by fract(world)
// and adds only the spark texels, scaled by the glow.

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
