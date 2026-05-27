#!/usr/bin/env python3
"""Offline preview of realistic_fire.fsh's FLAME path.

Renders one upright flame quad with the same noise / blackbody-ramp / compose math as the
GLSL fragment shader, composited (with the shader's alpha) over a dark-green grass backdrop so
the orange-vs-yellow-vs-lime-vs-white-blowout failure modes are all visible at a glance.

This is a FRAGMENT-shader preview of a single quad — it does NOT reproduce the assembled scene
geometry (ribbons, block char, embers). Use it to judge flame colour/flicker, not layout.

Usage:  python3 scripts/preview_flame.py [gametime]   ->  writes scripts/preview_flame.png
"""
import sys
import numpy as np
from PIL import Image

W, H = 320, 480
GAME_TIME = float(sys.argv[1]) if len(sys.argv) > 1 else 0.5

# --- noise primitives (match GLSL) ---
def hash2(px, py):
    x = np.sin(px * 127.1 + py * 311.7) * 43758.5453
    return x - np.floor(x)  # GLSL fract: always [0,1), unlike np.modf which keeps sign

def heat_noise(px, py):
    ix, iy = np.floor(px), np.floor(py)
    fx, fy = px - ix, py - iy
    ux = fx * fx * (3.0 - 2.0 * fx)
    uy = fy * fy * (3.0 - 2.0 * fy)
    a = hash2(ix, iy);           b = hash2(ix + 1, iy)
    c = hash2(ix, iy + 1);       d = hash2(ix + 1, iy + 1)
    return (a * (1 - ux) + b * ux) * (1 - uy) + (c * (1 - ux) + d * ux) * uy

def fbm(px, py):
    v = np.zeros_like(px); amp = 0.55
    # rot = [[0.80,-0.60],[0.60,0.80]] * 2.04
    for _ in range(5):
        v += amp * heat_noise(px, py)
        nx = (0.80 * px - 0.60 * py) * 2.04
        ny = (0.60 * px + 0.80 * py) * 2.04
        px, py = nx, ny
        amp *= 0.52
    return v

def warped_fbm(px, py, t):
    qx = fbm(px, py + t * 0.55)
    qy = fbm(px + 5.2 - t * 0.40, py + 1.3)
    rx = fbm(px + 3.5 * qx + 1.7, py + 3.5 * qy + 9.2 + t * 0.60)
    ry = fbm(px + 3.5 * qx + 8.3 + t * 0.30, py + 3.5 * qy + 2.8)
    return fbm(px + 3.5 * rx, py + 3.5 * ry)

def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)

# --- blackbody ramp (keep in sync with realistic_fire.fsh fireColor) ---
STOPS = [
    (0.030, 0.005, 0.000), (0.62, 0.060, 0.012), (0.96, 0.18, 0.022),
    (1.00, 0.33, 0.040),   (1.00, 0.43, 0.058),  (1.00, 0.56, 0.110),
]
EDGES = [0.16, 0.34, 0.55, 0.78, 1.0]

def fire_color(h):
    h = np.clip(h, 0.0, 1.0)
    out = np.zeros(h.shape + (3,))
    lo_edge = 0.0
    for i, hi_edge in enumerate(EDGES):
        c0, c1 = np.array(STOPS[i]), np.array(STOPS[i + 1])
        seg = (h - lo_edge) / (hi_edge - lo_edge)
        mask = (h >= lo_edge) & ((h < hi_edge) | (i == len(EDGES) - 1))
        for k in range(3):
            out[..., k] = np.where(mask, c0[k] + (c1[k] - c0[k]) * np.clip(seg, 0, 1), out[..., k])
        lo_edge = hi_edge
    return out

# --- main (flame path) ---
u = np.linspace(0, 1, W)[None, :].repeat(H, 0)
v = np.linspace(0, 1, H)[:, None].repeat(W, 1)   # v: 0 top .. 1 bottom == texCoord0.y
height_from_base = 1.0 - v
t = GAME_TIME * 1200.0

flow_u = u * 2.0 + np.sin(t * 0.014 + v * 3.4) * 0.12
flow_v = v * 2.6 - t * 0.030
body = warped_fbm(flow_u + u * 0.20, flow_v + v * 0.20, t * 0.0055 + 0.14)
fine = heat_noise(u * W * 0.10 + t * 0.7, v * H * 0.10 - t * 0.42)
low = heat_noise(u * 0.72 + t * 0.06, v * 0.72 - t * 0.05)

flicker = 0.84 + 0.16 * np.sin(t * 0.78 + v * 18.0 + body * 5.4 + fine * 2.5)
heat_profile = smoothstep(0.0, 0.22, height_from_base) * (1.0 - smoothstep(0.42, 1.0, height_from_base))
density = body * 0.88 + fine * 0.16 - low * 0.10
heat_field = np.clip(0.34 + heat_profile * 0.55 + density * 0.40 + flicker * 0.08 - height_from_base * 0.18, 0.0, 1.1)
incand = np.clip(heat_field * 0.96, 0.0, 0.68)
flame_rgb = fire_color(incand)

# soft oval mask (vsh) + base-anchored hot core
xf = 1.0 - np.abs(u - 0.5) * 2.0
yf = 1.0 - np.abs(v - 0.5) * 2.0
soft = smoothstep(0.0, 0.9, np.clip(xf * yf, 0, 1))
base_glow = 1.0 - smoothstep(0.0, 0.42, height_from_base)
core_spot = smoothstep(0.74, 1.0, soft + body * 0.20 + density * 0.18) * base_glow
white_hot = np.stack([np.full_like(u, 1.0), np.full_like(u, 0.70), np.full_like(u, 0.30)], -1) * (core_spot * core_spot)[..., None]

color = flame_rgb + white_hot * 0.14
color *= flicker[..., None]
tip_fade = smoothstep(0.78, 1.0, height_from_base)
color = color * (1 - tip_fade[..., None]) + (color * 0.45 + np.array([0.06, 0.04, 0.025])) * tip_fade[..., None]

# alpha (flame path)
porous = smoothstep(0.06, 0.86, soft + density * 0.22 + fine * 0.10)
alpha = 1.0
alpha = alpha * (0.55 + (1.28 - 0.55) * porous)
alpha = alpha * (smoothstep(0.0, 0.30, v) * 0.65 + 0.35)
alpha = alpha * ((1.0 - smoothstep(0.90, 1.0, v)) * 0.4 + 0.6)
alpha = alpha * (0.70 + (1.10 - 0.70) * smoothstep(0.20, 0.72, density))
alpha = np.clip(alpha, 0.0, 1.0)

# ADDITIVE composite (flame render type is additive). Two panels so both real cases show:
#   left  = over dark ash/char (where the dense flame body actually sits) -> should read ORANGE
#   right = over green grass    (worst-case perimeter)                     -> watch for lime/yellow
contrib = color * alpha[..., None]
char_bg = np.array([0.16, 0.15, 0.14])
grass_bg = np.array([0.22, 0.42, 0.16])
left = np.clip(char_bg + contrib, 0.0, 1.0)
right = np.clip(grass_bg + contrib, 0.0, 1.0)
gap = np.ones((H, 4, 3))
combined = np.concatenate([left, gap, right], axis=1)
img = (combined * 255).astype(np.uint8)
Image.fromarray(img, "RGB").save("scripts/preview_flame.png")
print(f"wrote scripts/preview_flame.png (gametime={GAME_TIME})  [left=char, right=grass]")
