#version 150

// Ash-ground mask shader — see realistic_fire_scorch.vsh.
//   worldUv          = world (x, z); fract() tiles the ash texture once per block (pixel-art,
//                      seamless across blocks, no GL wrap-mode dependency).
//   vertexColor.a    = reveal mask: how far inside the burning contour this fragment is, smoothly
//                      interpolated from the quad corners. Below the cutoff the fire front has not
//                      reached here yet, so the ash stays hidden (discarded) and the grass shows.
// The kept fragments are written FULLY OPAQUE — a hard masked reveal, never an alpha blend.

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;

in vec4 vertexColor;
in vec2 worldUv;

out vec4 fragColor;

void main() {
    // The ash is drawn as a filled oval that exactly matches the flame contour, so the GEOMETRY
    // defines the burnt shape (no per-fragment mask needed). UV0 carries world (x, z); fract()
    // tiles the ash texture once per block — pixelated and seamless, never stretched. Opaque, so
    // it reads as solid ground with no grass blended through. vertexColor tints (e.g. a darker
    // rim at the charred edge).
    vec3 ash = texture(Sampler0, fract(worldUv)).rgb;
    fragColor = vec4(ash * vertexColor.rgb * ColorModulator.rgb, 1.0);
}
