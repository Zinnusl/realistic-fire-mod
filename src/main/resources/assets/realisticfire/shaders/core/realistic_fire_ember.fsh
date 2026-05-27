#version 150

// Ember-oval shader (additive) — see realistic_fire_ember.vsh.
//   worldUv       = world (x, z); fract() tiles the sparse ember-spark texture per block.
//   vertexColor.a = the footprint's ember glow (pulses, fades as the scar cools).
// Render type is ADDITIVE (GL_ONE, GL_ONE): the output rgb is ADDED to the framebuffer, so we
// emit only the spark texels (weighted by their own alpha) scaled by the glow — the transparent
// ash gaps add nothing, and the sparks glow over the ash beneath.

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;

in vec4 vertexColor;
in vec2 worldUv;

out vec4 fragColor;

void main() {
    vec4 spark = texture(Sampler0, fract(worldUv));
    vec3 add = spark.rgb * spark.a * vertexColor.a * ColorModulator.rgb;
    fragColor = vec4(add, 1.0);
}
