# Realistic Fire — parameter editor

A browser tool for dialing in the fire simulation's parameters with **live visual feedback**,
instead of guessing constants and doing full game restarts.

It runs the **exact same Rust solver** the mod uses (`src/main/rust/realisticfire_solver`),
compiled to WebAssembly. So whatever you dial in here transfers straight back into the mod:

- the **Solver config** sliders map to `SolverConfig::default()` in `lib.rs`
- the **Material** sliders map to a material JSON under
  `data/realisticfire/realistic_fire/materials/` (default values match `default_grass`)
- **Resolution (cells / block)** now uses the solver's real sub-block mode, matching the mod's
  `cellsPerBlockAxis` behaviour instead of approximating it with a larger block grid.

## Run

```sh
./build.sh                       # compiles the WASM and copies it here
python3 -m http.server 8777      # from this folder
# open http://localhost:8777/
```

`build.sh` needs the `wasm32-unknown-unknown` target:
`rustup target add wasm32-unknown-unknown` (already installed here).

## What you see

Top-down view of one ground layer. The whole patch starts as fuel (green); the fire spreads from
the centre, leaving a burnt scar sampled from `scorch_ash.png`. Warm spent cells blend in the
same `ember_overlay.png` speckles used by the mod. Flame glows orange→white by temperature.

The **stats** row gives objective readouts — including `roundness` (diagonal reach ÷ axial reach):
~100 % = circular front, well under = a diamond/"+", well over = a square. Use it to tune
`diagonal_exposure` until the front reads round.

- **Ignition: Point** — a brief spark the fuel must carry (the realistic case).
- **Ignition: Sustained source** — a flame pinned at the centre (isolates the front shape).
- **Water firebreak** — drop a water line/ring to check the fire stops at it.

## Export

**Generate config JSON** dumps everything as JSON (also Copy / Download).

**Apply to instance** asks for a Minecraft instance folder and writes:

- `moonlight-global-datapacks/realisticfire-editor-tuning/pack.mcmeta`
- `moonlight-global-datapacks/realisticfire-editor-tuning/data/realisticfire/realistic_fire/materials/editor_grass.json`
- `config/realisticfire-editor-config.json`

The datapack overrides the default grass-like material tuning immediately after a datapack reload
or instance restart. Solver values are native-code constants unless this mod is rebuilt, so the
full editor snapshot is also saved under `config/` for reference.
