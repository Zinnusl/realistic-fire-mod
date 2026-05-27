#version 150

// === Realistic Fire — cinematic procedural flame fragment shader ===
// Multi-octave domain-warped FBM body, 6-stop blackbody color ramp, vertical heat
// stratification (hot mid-zone, cooler tip + base), wispy alpha falloff with tip
// dissipation, and a bloom-style white-hot core in the densest regions.

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float GameTime;

in vec4 vertexColor;
in vec2 texCoord0;
in float softMask;
in vec3 vertexPosition;
in float swayWeight;

out vec4 fragColor;

// === Noise primitives ===

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float heatNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x),
               u.y);
}

// 5-octave fractional Brownian motion — organic layered noise.
float fbm(vec2 p) {
    float v = 0.0;
    float amp = 0.55;
    mat2 rot = mat2(0.80, -0.60, 0.60, 0.80);
    for (int i = 0; i < 5; i++) {
        v += amp * heatNoise(p);
        p = rot * p * 2.04;
        amp *= 0.52;
    }
    return v;
}

// Iñigo-Quílez domain-warped FBM — fbm fed into fbm produces turbulent fluid motion,
// the same technique used in offline fluid simulators for fire/smoke shaping.
float warpedFbm(vec2 p, float t) {
    vec2 q = vec2(fbm(p + vec2(0.0, t * 0.55)),
                  fbm(p + vec2(5.2, 1.3) - vec2(t * 0.40, 0.0)));
    vec2 r = vec2(fbm(p + 3.5 * q + vec2(1.7, 9.2) + vec2(0.0, t * 0.60)),
                  fbm(p + 3.5 * q + vec2(8.3, 2.8) + vec2(t * 0.30, 0.0)));
    return fbm(p + 3.5 * r);
}

// === Blackbody-inspired color ramp ===
// 6 stops, normalized temperature [0..1]: smoulder ember → deep red → bright red →
// orange → yellow-orange → near-yellow. White-blue plasma intentionally omitted; the
// dedicated whiteHotCore additive term covers the brightest highlights.
vec3 fireColor(float h) {
    h = clamp(h, 0.0, 1.0);
    vec3 c0 = vec3(0.030, 0.005, 0.000);
    vec3 c1 = vec3(0.62, 0.060, 0.012);
    vec3 c2 = vec3(0.96, 0.18, 0.022);
    vec3 c3 = vec3(1.00, 0.33, 0.040);
    vec3 c4 = vec3(1.00, 0.43, 0.058);
    vec3 c5 = vec3(1.00, 0.56, 0.110);
    if (h < 0.16) return mix(c0, c1, h / 0.16);
    if (h < 0.34) return mix(c1, c2, (h - 0.16) / 0.18);
    if (h < 0.55) return mix(c2, c3, (h - 0.34) / 0.21);
    if (h < 0.78) return mix(c3, c4, (h - 0.55) / 0.23);
    return mix(c4, c5, (h - 0.78) / 0.22);
}

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    float heatHint = clamp(max(vertexColor.r, vertexColor.g * 0.92), 0.0, 1.0);
    float flameHint = smoothstep(0.30, 0.82, heatHint);
    // Exact S>1 flame-front quads are intentionally small. Sampling the vanilla flame texture's
    // transparent pockets on those tiny quads can make an entire sub-cell tongue blink out as the
    // procedural motion shifts. Floor the texture alpha for hot flame geometry; smoke/char keep the
    // original texture alpha path.
    float stableTexAlpha = mix(texel.a, max(texel.a, 0.62), flameHint);
    float baseAlpha = stableTexAlpha * vertexColor.a * ColorModulator.a;
    if (baseAlpha <= 0.003) {
        discard;
    }

    // Soft oval mask — rounds the corners of each flame quad.
    vec2 centeredUv = texCoord0 * 2.0 - 1.0;
    float radialFeather = 1.0 - clamp(length(centeredUv), 0.0, 1.0);
    float softOvalMask = max(softMask, smoothstep(0.0, 0.42, radialFeather));

    // Vertex color classifies pixel as flame / smoke / char based on its red intensity.
    float heat = heatHint;
    float flameWeight = smoothstep(0.30, 0.82, heat);
    float smokeWeight = smoothstep(0.18, 0.46, max(max(vertexColor.r, vertexColor.g), vertexColor.b)) * (1.0 - flameWeight);

    // Renderer convention: texCoord0.y == 1.0 at the BASE of a flame quad, == 0.0 at the TIP.
    float heightFromBase = 1.0 - texCoord0.y;

    float t = GameTime * 1200.0;

    // === Animated procedural body ===
    // Flow UV scrolls upward over time so the noise pattern visibly rises like a real flame.
    vec2 flowUv = vec2(
        texCoord0.x * 2.0 + sin(t * 0.014 + texCoord0.y * 3.4) * 0.12,
        texCoord0.y * 2.6 - t * 0.030
    );

    // Skip the heavy warpedFbm path (25+ noise samples per fragment) for non-flame
    // pixels — char, smoke, and atmospheric halo billboards don't read the body term
    // and are often large quads. A cheap noise sample stands in for the unused terms
    // so the downstream porousMask / density logic still gets some variation.
    float body;
    float fineDetail;
    float lowNoise;
    if (flameWeight > 0.05) {
        body = warpedFbm(flowUv + vertexPosition.xz * 0.20, t * 0.0055 + swayWeight * 0.14);
        fineDetail = heatNoise(gl_FragCoord.xy * 0.10 + vec2(t * 0.7, -t * 0.42));
        lowNoise = heatNoise(vertexPosition.xz * 0.72 + vec2(t * 0.06, -t * 0.05));
    } else {
        // Cheap stand-in: a single noise sample reused for the porous edge break-up.
        float n = heatNoise(vertexPosition.xz * 0.55 + vec2(t * 0.04, -t * 0.05));
        body = n * 0.5;
        fineDetail = n;
        lowNoise = 0.5;
    }

    // Fast intensity flicker.
    float flicker = 0.84 + 0.16 * sin(t * 0.78 + texCoord0.y * 18.0 + body * 5.4 + fineDetail * 2.5);

    // === Vertical heat stratification ===
    // Real flames are hottest a third of the way up (where pyrolysed fuel meets oxygen),
    // dimmer at the very base and at the tip. heatProfile peaks around heightFromBase ≈ 0.35.
    // Note: second factor is (1.0 - smoothstep(0.42, 1.0, ...)) rather than smoothstep with
    // reversed edges — the latter has implementation-defined behaviour on some GLSL drivers.
    float heatProfile = smoothstep(0.0, 0.22, heightFromBase) * (1.0 - smoothstep(0.42, 1.0, heightFromBase));

    // Density combines large turbulent body + fine detail - slow undulation.
    float density = body * 0.88 + fineDetail * 0.16 - lowNoise * 0.10;

    // Heat field drives the blackbody ramp. Combine vertex-color heat with vertical profile,
    // density brightness, and a flicker hint; subtract a small tip-fade term so flame tips
    // stop short of the brightest yellow stop.
    float heatField = clamp(
            heat * 0.34
            + heatProfile * 0.55
            + density * 0.40
            + flicker * 0.08
            - heightFromBase * 0.18,
            0.0, 1.1);

    float incandescence = clamp(heatField * 0.96, 0.0, 0.68);
    vec3 flameRgb = fireColor(incandescence);

    // === Char / smoke paths ===
    float texLum = (texel.r + texel.g + texel.b) * 0.3333;
    vec3 charBase = vertexColor.rgb * ColorModulator.rgb * (0.78 + texLum * 0.55);
    vec3 smokeBase = vertexColor.rgb * ColorModulator.rgb * (0.95 + texLum * 0.50);
    float smokeNoise = 0.5;
    float smokeBreakup = 1.0;
    if (smokeWeight > 0.01 && flameWeight < 0.08) {
        vec2 smokeUv = texCoord0 * vec2(2.2, 3.0) + vertexPosition.xz * 0.38 + vec2(t * 0.012, -t * 0.018);
        smokeNoise = heatNoise(smokeUv) * 0.65 + heatNoise(smokeUv * 2.1 + vec2(4.7, 1.9)) * 0.35;
        smokeBreakup = smoothstep(0.05, 0.92, softOvalMask + smokeNoise * 0.33 - heightFromBase * 0.10);
        smokeBase *= 0.76 + smokeNoise * 0.25 + heightFromBase * 0.10;
        smokeBase = mix(smokeBase * vec3(0.86), smokeBase * vec3(1.04, 1.04, 1.02), heightFromBase);
    }

    // === Base-anchored hot core ===
    // Real flames are brightest near the base where fuel meets oxygen. baseGlow restricts
    // the bright yellow-white highlight to the lower part of each tongue (heightFromBase
    // near 0), so the base reads incandescent while the rest of the tongue stays orange —
    // the highlight can no longer blow out the whole flame the way a global core did.
    float baseGlow = 1.0 - smoothstep(0.0, 0.42, heightFromBase);
    float coreSpot = smoothstep(0.74, 1.0, softOvalMask + body * 0.20 + density * 0.18) * baseGlow;
    vec3 whiteHotCore = vec3(1.00, 0.70, 0.30) * coreSpot * coreSpot;

    // === Compose ===
    vec3 color = mix(charBase, smokeBase, smokeWeight);
    color = mix(color, flameRgb, flameWeight);
    color += whiteHotCore * flameWeight * 0.14;
    color *= mix(0.94, flicker, flameWeight);

    // Tip dissipation in color space — extreme tip fades toward dark smoke-red.
    float tipFade = smoothstep(0.78, 1.0, heightFromBase);
    color = mix(color, color * 0.45 + vec3(0.06, 0.04, 0.025), tipFade * flameWeight);

    // === Alpha ===
    float alpha = baseAlpha;

    // Porous mask gives organic edge breakup driven by the body noise.
    float porousMask = smoothstep(0.06, 0.86, softOvalMask + density * 0.22 + fineDetail * 0.10);

    // Bright edges where heat is high — fragments at the perimeter of dense regions glow brighter.
    float edgeLift = smoothstep(0.35, 1.0, flameWeight) * (0.95 + fineDetail * 0.22);
    alpha *= mix(0.55, 1.28, porousMask) * mix(1.0, edgeLift, flameWeight);

    // Wispy tip — flames thin out as they rise.
    alpha *= mix(1.0, smoothstep(0.0, 0.30, texCoord0.y) * 0.65 + 0.35, flameWeight);

    // Soft base attachment — flame still solid where it meets the fuel.
    // (1.0 - smoothstep) form for portability across GLSL drivers.
    alpha *= mix(1.0, (1.0 - smoothstep(0.90, 1.0, texCoord0.y)) * 0.4 + 0.6, flameWeight);

    // Density modulation — wispy where noise density is low, opaque where dense.
    alpha *= mix(0.70, 1.10, smoothstep(0.20, 0.72, density)) * (flameWeight * 0.55 + 0.45);

    // Do not let the animated porous mask erase a whole exact sub-cell flame ribbon. The ribbon's
    // geometry is already the Rust model boundary; shader motion should lick inside it, not toggle
    // the boundary off.
    alpha = max(alpha, baseAlpha * flameWeight * softOvalMask * 0.34);

    if (flameWeight < 0.08) {
        alpha *= mix(0.55, 1.0, softOvalMask);
        alpha *= mix(1.0, 0.82 + smokeBreakup * 0.35, smokeWeight);
        alpha *= mix(1.0, 0.95 + smokeNoise * 0.18, smokeWeight);
    }

    if (alpha <= 0.006) {
        discard;
    }
    fragColor = vec4(color, alpha);
}
