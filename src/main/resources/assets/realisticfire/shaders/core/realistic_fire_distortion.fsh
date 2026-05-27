#version 150

uniform sampler2D Sampler0;     // captured scene framebuffer copy
uniform vec2 ScreenSize;
uniform float GameTime;

in vec4 vertexColor;
in vec3 vertexWorld;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x),
               u.y);
}

void main() {
    // vertexColor.a encodes the per-vertex distortion intensity (0..1).
    // vertexColor.rgb encodes a faint warm tint to add over the distorted scene.
    float intensity = vertexColor.a;
    if (intensity <= 0.003) {
        discard;
    }

    vec2 screenUv = gl_FragCoord.xy / ScreenSize;
    float t = GameTime * 1200.0;

    // Two-axis turbulent displacement based on world XZ + time.
    float nx = noise2(vertexWorld.xz * 5.4 + vec2(t * 0.42, 0.0));
    float nz = noise2(vertexWorld.xz * 5.7 + vec2(0.0, t * 0.46) + vec2(13.7, 0.0));
    // Vertical-only bias for the rising-air feel — heat haze drifts upward.
    float ny = noise2(vertexWorld.xz * 3.2 + vec2(t * 0.35, t * 0.18));
    vec2 displace = vec2(nx - 0.5, (nz - 0.5) + (ny - 0.5) * 0.6);

    // Distortion strength scales with intensity (fades out at the edges of the haze billboard).
    float strength = 0.014 * intensity;
    vec2 sampleUv = clamp(screenUv + displace * strength, vec2(0.001), vec2(0.999));

    vec3 sceneColor = texture(Sampler0, sampleUv).rgb;
    // Faint warm wash so the haze reads as "hot air" rather than invisible refraction.
    sceneColor += vertexColor.rgb * (0.12 + nx * 0.05) * intensity;

    fragColor = vec4(sceneColor, intensity);
}
