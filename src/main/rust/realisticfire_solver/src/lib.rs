#![allow(unsafe_code)]

use std::collections::HashMap;

// ABI 5 adds setSubBlockResolution and changes the implicit cell-grid contract (sub-block cells).
// Java refuses to load a mismatched .so via the abiVersion check at startup.
const ABI_VERSION: i32 = 6;
#[cfg(test)]
const CELL_COUNT: usize = 16 * 16 * 16;
const STATE_FIELDS: usize = 6;
const MUTATION_RECORD_INTS: usize = 6;
const VISUAL_RECORD_FLOATS: usize = 8;
const ACTION_SET_CHAR: i32 = 1;
const ACTION_SET_ASH: i32 = 2;
const ACTION_SET_AIR: i32 = 3;
const ACTION_DAMAGE_ENTITY_AREA: i32 = 4;
// One Minecraft chunk section is 16x16x16 blocks; a tile always maps to one section, so per-block
// bookkeeping (the whole-block-swallowed counters) is always this size regardless of S.
const BLOCKS_PER_SECTION: usize = 16 * 16 * 16;
// Special material_id values negotiated with the Java side.
// -1 = sustained heat source (fire/lava); -2 = water (cold sink, blocks propagation).
const WATER_MATERIAL_ID: i32 = -2;
const SMOKE_MAX_CELL_DENSITY: f32 = 6.0;
const SMOKE_BURN_SCALE: f32 = 4.5;
const SMOKE_BUOYANCY_RATE: f32 = 1.35;
const SMOKE_LATERAL_DIFFUSION_RATE: f32 = 0.22;
const SMOKE_DOWNWARD_DIFFUSION_RATE: f32 = 0.035;
const SMOKE_TRANSPORT_EPSILON: f32 = 0.012;

#[derive(Clone, Copy)]
struct MaterialProps {
    fuel: f32,
    has_char_stage: bool,
    has_ash_stage: bool,
    ignition_temperature: f32,
    burn_rate: f32,
    heat_release: f32,
    smoke_yield: f32,
    insulation: f32,
}

impl Default for MaterialProps {
    fn default() -> Self {
        Self {
            fuel: 0.0,
            has_char_stage: false,
            has_ash_stage: false,
            ignition_temperature: f32::INFINITY,
            burn_rate: 0.0,
            heat_release: 0.0,
            smoke_yield: 0.0,
            insulation: 0.0,
        }
    }
}

/// Tunable solver constants. The `Default` values reproduce the hardcoded behaviour the mod
/// shipped with, so the native `.so` is unchanged until new values are baked in. The WebAssembly
/// parameter editor varies these live; the dialed-in numbers map straight back here.
#[derive(Clone, Copy)]
struct SolverConfig {
    /// Radiant preheat constant — the term that drives ignition spread across a fuel field.
    radiant_strength: f32,
    /// Radiant exposure of the four in-plane diagonal neighbours (axial = 1.0). Controls whether
    /// the burn front advances as a Manhattan diamond (low) or a circle (higher).
    diagonal_exposure: f32,
    /// Conductive bias / radiant exposure of the cell directly above (hot air rises into it).
    up_bias: f32,
    up_exposure: f32,
    /// Conductive bias / radiant exposure of the cell directly below (the ground the fire sits on).
    down_bias: f32,
    down_exposure: f32,
    /// Base conduction transfer coefficient.
    conduction_transfer: f32,
    /// Newtonian cooling: `cooling_base + insulation * cooling_insulation`.
    cooling_base: f32,
    cooling_insulation: f32,
    /// On burnout (fuel spent) the cell is knocked down to `ambient + burnout_temp_offset` so spent
    /// ground stops rendering flame; higher = flames linger longer after the fuel is gone.
    burnout_temp_offset: f32,
}

impl Default for SolverConfig {
    fn default() -> Self {
        Self {
            radiant_strength: 180.0,
            diagonal_exposure: 0.707,
            up_bias: 2.4,
            up_exposure: 1.35,
            down_bias: 0.45,
            down_exposure: 1.8,
            conduction_transfer: 0.018,
            cooling_base: 0.020,
            cooling_insulation: 0.008,
            burnout_temp_offset: 110.0,
        }
    }
}

#[derive(Clone)]
struct Tile {
    loaded: bool,
    // LIVE form: full sub-cell arrays (cell_count long). Empty while the tile is DORMANT.
    material_ids: Vec<i32>,
    temperature: Vec<f32>,
    fuel: Vec<f32>,
    oxygen: Vec<f32>,
    smoke: Vec<f32>,
    moisture: Vec<f32>,
    char_progress: Vec<f32>,
    // DORMANT form: one material + STATE_FIELDS state per BLOCK (BLOCKS_PER_SECTION). Empty while the
    // tile is LIVE. A tile is exactly one of live (sub-cells) or dormant (block-level), so a cold tile
    // far from any fire costs ~114 KB instead of ~4 MB at S=6 (lazy sub-cell allocation). conduct only
    // reads tiles in the active ring, which are always expanded to live first, so it never sees a
    // dormant tile.
    block_materials: Vec<i32>,
    block_state: Vec<f32>,
    // Per-BLOCK counts of sub-cells that have charred / burnt out (BLOCKS_PER_SECTION). Survive
    // compact/expand so a re-expanded burnt block doesn't re-emit its outcome.
    char_count: Vec<u16>,
    burnt_count: Vec<u16>,
    // Cheap "this tile has heat/fire and is worth stepping" flag (always false while dormant).
    active: bool,
}

impl Tile {
    #[inline]
    fn is_live(&self) -> bool {
        !self.temperature.is_empty()
    }

    /// True if any cell is hot enough to conduct/burn, is a sustained source, or is smoking. A
    /// dormant tile has empty sub-cell arrays, so this is trivially false (dormant => cold).
    fn is_active(&self) -> bool {
        self.temperature.iter().any(|&t| t > 330.0)
            || self.material_ids.iter().any(|&m| m == -1)
            || self.smoke.iter().any(|&s| s > 0.01)
    }

    /// Drop the sub-cell arrays, keeping a one-per-block summary (material + state of each block's
    /// first sub-cell). Only called on cold (inactive) tiles, so no live fire detail is lost.
    fn compact(&mut self, s: i32, dim: i32) {
        if !self.is_live() {
            return;
        }
        let mut bm = vec![0i32; BLOCKS_PER_SECTION];
        let mut bs = vec![0.0f32; BLOCKS_PER_SECTION * STATE_FIELDS];
        for by in 0..16 {
            for bz in 0..16 {
                for bx in 0..16 {
                    let b = idx_dim(bx, by, bz, 16);
                    let c = idx_dim(bx * s, by, bz * s, dim); // block's first sub-cell
                    bm[b] = self.material_ids[c];
                    bs[b * STATE_FIELDS] = self.temperature[c];
                    bs[b * STATE_FIELDS + 1] = self.fuel[c];
                    bs[b * STATE_FIELDS + 2] = self.oxygen[c];
                    bs[b * STATE_FIELDS + 3] = self.smoke[c];
                    bs[b * STATE_FIELDS + 4] = self.moisture[c];
                    bs[b * STATE_FIELDS + 5] = self.char_progress[c];
                }
            }
        }
        self.block_materials = bm;
        self.block_state = bs;
        self.material_ids = Vec::new();
        self.temperature = Vec::new();
        self.fuel = Vec::new();
        self.oxygen = Vec::new();
        self.smoke = Vec::new();
        self.moisture = Vec::new();
        self.char_progress = Vec::new();
        self.active = false;
    }

    /// Rebuild the sub-cell arrays from the per-block summary (every sub-cell of a block gets that
    /// block's material/state). Inverse of `compact`.
    fn expand(&mut self, s: i32, dim: i32) {
        if self.is_live() {
            return;
        }
        let cell_count = (dim as usize) * 16 * (dim as usize);
        let mut mat = vec![0i32; cell_count];
        let mut temp = vec![293.15f32; cell_count];
        let mut fuel = vec![0.0f32; cell_count];
        let mut oxy = vec![1.0f32; cell_count];
        let mut smoke = vec![0.0f32; cell_count];
        let mut moist = vec![0.0f32; cell_count];
        let mut char_p = vec![0.0f32; cell_count];
        for by in 0..16 {
            for bz in 0..16 {
                for bx in 0..16 {
                    let b = idx_dim(bx, by, bz, 16);
                    let bm = self.block_materials.get(b).copied().unwrap_or(0);
                    let base = b * STATE_FIELDS;
                    let t = self.block_state.get(base).copied().unwrap_or(293.15);
                    let f = self.block_state.get(base + 1).copied().unwrap_or(0.0);
                    let o = self.block_state.get(base + 2).copied().unwrap_or(1.0);
                    let sm = self.block_state.get(base + 3).copied().unwrap_or(0.0);
                    let mo = self.block_state.get(base + 4).copied().unwrap_or(0.0);
                    let cp = self.block_state.get(base + 5).copied().unwrap_or(0.0);
                    for sz in 0..s {
                        for sx in 0..s {
                            let c = idx_dim(bx * s + sx, by, bz * s + sz, dim);
                            mat[c] = bm;
                            temp[c] = t;
                            fuel[c] = f;
                            oxy[c] = o;
                            smoke[c] = sm;
                            moist[c] = mo;
                            char_p[c] = cp;
                        }
                    }
                }
            }
        }
        self.material_ids = mat;
        self.temperature = temp;
        self.fuel = fuel;
        self.oxygen = oxy;
        self.smoke = smoke;
        self.moisture = moist;
        self.char_progress = char_p;
        self.block_materials = Vec::new();
        self.block_state = Vec::new();
        self.active = self.is_active();
    }

    fn new(material_ids: Vec<i32>, initial_state: Vec<f32>) -> Self {
        // The cell count is taken from the material array, so a tile sizes itself to the world's
        // grid (4096 at S=1, dim*16*dim at higher resolutions). The caller supplies a correctly
        // sized material array; state may be shorter (then ambient defaults are kept).
        let cell_count = material_ids.len();
        let mut tile = Self {
            loaded: true,
            material_ids,
            temperature: vec![293.15; cell_count],
            fuel: vec![0.0; cell_count],
            oxygen: vec![1.0; cell_count],
            smoke: vec![0.0; cell_count],
            moisture: vec![0.0; cell_count],
            char_progress: vec![0.0; cell_count],
            block_materials: Vec::new(),
            block_state: Vec::new(),
            char_count: vec![0; BLOCKS_PER_SECTION],
            burnt_count: vec![0; BLOCKS_PER_SECTION],
            active: false,
        };
        // (counters are derived from cell state after a load via recompute_block_counts)
        for index in 0..cell_count {
            let base = index * STATE_FIELDS;
            if base + 5 < initial_state.len() {
                tile.temperature[index] = initial_state[base];
                tile.fuel[index] = initial_state[base + 1];
                tile.oxygen[index] = initial_state[base + 2];
                tile.smoke[index] = initial_state[base + 3];
                tile.moisture[index] = initial_state[base + 4];
                tile.char_progress[index] = initial_state[base + 5];
            }
        }
        tile.active = tile.is_active();
        tile
    }

    /// Rebuild the per-block char/burnt tallies from cell state. Called after a load (the counters
    /// aren't serialised) and whenever a block is re-uploaded, so the whole-block-swallowed gate
    /// resumes from the right partial-burn state. A sub-cell counts as charred at char_progress >=
    /// 0.45, and as burnt once its material has been cleared to 0 AND it charred (so original air,
    /// material 0 with char_progress 0, is not mistaken for burnt fuel).
    fn recompute_block_counts(&mut self, s: i32, dim: i32) {
        for c in self.char_count.iter_mut() {
            *c = 0;
        }
        for c in self.burnt_count.iter_mut() {
            *c = 0;
        }
        for by in 0..16 {
            for bz in 0..16 {
                for bx in 0..16 {
                    let block_idx = idx_dim(bx, by, bz, 16);
                    let mut cc = 0u16;
                    let mut bc = 0u16;
                    for sz in 0..s {
                        for sx in 0..s {
                            let i = idx_dim(bx * s + sx, by, bz * s + sz, dim);
                            if self.char_progress[i] >= 0.45 {
                                cc += 1;
                            }
                            if self.material_ids[i] == 0 && self.char_progress[i] >= 0.999 {
                                bc += 1;
                            }
                        }
                    }
                    self.char_count[block_idx] = cc;
                    self.burnt_count[block_idx] = bc;
                }
            }
        }
    }
}

struct World {
    _dimension_id: i32,
    _min_build_height: i32,
    _max_build_height: i32,
    seed: i32,
    /// Horizontal sub-block resolution S: each Minecraft block is split into S*S cells on the X and
    /// Z axes (Y stays 1 cell per block). 1 = the original 1-cell-per-block behaviour. A tile still
    /// maps 1:1 to a 16-block chunk section, but now holds `dim` x 16 x `dim` cells where dim = 16*S.
    cells_per_axis: i32,
    materials: Vec<MaterialProps>,
    config: SolverConfig,
    tiles: HashMap<(i32, i32, i32), Tile>,
}

impl World {
    fn new(dimension_id: i32, min_build_height: i32, max_build_height: i32, seed: i32) -> Self {
        Self {
            _dimension_id: dimension_id,
            _min_build_height: min_build_height,
            _max_build_height: max_build_height,
            seed,
            cells_per_axis: 1,
            materials: vec![MaterialProps::default()],
            config: SolverConfig::default(),
            tiles: HashMap::new(),
        }
    }

    /// Horizontal cell-grid dimension of a tile (16 blocks * S sub-cells). Vertical stays 16.
    #[inline]
    fn dim(&self) -> i32 {
        16 * self.cells_per_axis.max(1)
    }

    /// Cells per tile = dim * 16 * dim (X * Y * Z). Equals 4096 at S=1.
    #[inline]
    fn cell_count(&self) -> usize {
        let d = self.dim() as usize;
        d * 16 * d
    }

    #[cfg_attr(not(target_arch = "wasm32"), allow(dead_code))]
    fn set_config(&mut self, config: SolverConfig) {
        self.config = config;
    }

    /// Set the horizontal sub-block resolution. Must be called before any tile is created, since it
    /// changes the cell-grid size; existing tiles are dropped so nothing is left mis-sized.
    #[cfg_attr(not(target_arch = "wasm32"), allow(dead_code))]
    fn set_cells_per_axis(&mut self, cells_per_axis: i32) {
        let s = cells_per_axis.clamp(1, 16);
        if s != self.cells_per_axis {
            self.cells_per_axis = s;
            self.tiles.clear();
        }
    }

    /// Build a tile from BLOCK-level arrays (one material + STATE_FIELDS state per block, 4096 each),
    /// replicating every block across its S*S sub-cells. Java uploads block-level data (small JNI
    /// transfer); the solver expands it here. At S=1 this is a straight copy.
    #[cfg_attr(target_arch = "wasm32", allow(dead_code))]
    fn set_tile_from_blocks(
        &mut self,
        key: (i32, i32, i32),
        block_materials: &[i32],
        block_state: &[f32],
    ) {
        let s = self.cells_per_axis.max(1);
        let dim = self.dim();
        let cell_count = self.cell_count();
        let mut materials = vec![0i32; cell_count];
        let mut state = vec![0.0f32; cell_count * STATE_FIELDS];
        for by in 0..16 {
            for bz in 0..16 {
                for bx in 0..16 {
                    let b = idx_dim(bx, by, bz, 16);
                    let m = block_materials.get(b).copied().unwrap_or(0);
                    for sz in 0..s {
                        for sx in 0..s {
                            let c = idx_dim(bx * s + sx, by, bz * s + sz, dim);
                            materials[c] = m;
                            for f in 0..STATE_FIELDS {
                                state[c * STATE_FIELDS + f] = block_state
                                    .get(b * STATE_FIELDS + f)
                                    .copied()
                                    .unwrap_or(0.0);
                            }
                        }
                    }
                }
            }
        }
        self.tiles.insert(key, Tile::new(materials, state));
    }

    /// Re-upload a single BLOCK: write its material/state to all S*S sub-cells and reset that block's
    /// whole-block tally so the aggregation gate restarts cleanly. `x,y,z` are block coordinates.
    #[cfg_attr(target_arch = "wasm32", allow(dead_code))]
    fn set_cell_from_block(&mut self, x: i32, y: i32, z: i32, material_id: i32, state: &[f32]) {
        let s = self.cells_per_axis.max(1);
        let dim = self.dim();
        let key = (x >> 4, y >> 4, z >> 4);
        let block_idx = idx_dim(x & 15, y & 15, z & 15, 16);
        let Some(tile) = self.tiles.get_mut(&key) else {
            return;
        };
        if !tile.is_live() {
            tile.expand(s, dim);
        }
        let base_x = (x & 15) * s;
        let ly = y & 15;
        let base_z = (z & 15) * s;
        for sz in 0..s {
            for sx in 0..s {
                let c = idx_dim(base_x + sx, ly, base_z + sz, dim);
                tile.material_ids[c] = material_id;
                tile.temperature[c] = state[0];
                tile.fuel[c] = state[1];
                tile.oxygen[c] = state[2];
                tile.smoke[c] = state[3];
                tile.moisture[c] = state[4];
                tile.char_progress[c] = state[5];
            }
        }
        tile.char_count[block_idx] = 0;
        tile.burnt_count[block_idx] = 0;
        // A re-uploaded hot block / fire source must wake the tile (re-uploading cold grass must not).
        if state[0] > 330.0 || material_id == -1 {
            tile.active = true;
        }
    }

    fn step(
        &mut self,
        dt: f32,
        max_cells: usize,
        max_mutations: usize,
        max_visuals: usize,
        mutations: &mut [i32],
        visuals: &mut [f32],
    ) -> (usize, usize) {
        let mut visited_cells = 0usize;
        let mut mutation_count = 0usize;
        let mut visual_count = 0usize;
        let visual_capacity = max_visuals.min(visuals.len() / VISUAL_RECORD_FLOATS);
        let mut visual_priorities = vec![f32::INFINITY; visual_capacity];
        let mut weakest_visual_index = 0usize;
        let mut weakest_visual_priority = f32::INFINITY;
        let materials = self.materials.clone();
        // Copy the config out before the per-tile `get_mut` borrow so the loop can read it freely.
        let config = self.config;
        let seed = self.seed;
        // Grid geometry for this world. dim = horizontal cells per tile (16*S); Y stays 16. At S=1
        // dim == 16, cell_count == 4096 and every index/bound below reduces to the original.
        let dim = self.dim();
        let cell_count = self.cell_count();
        let si = self.cells_per_axis.max(1);
        let s = si as f32;
        // Sub-cells per block (horizontal-only: S*S). A block's outcome commits when this many of its
        // sub-cells have reached the stage.
        let sub_per_block = (si * si) as u16;
        // Only tiles flagged active get scored/processed. The `tile.active` check is O(1), so the
        // thousands of cold loaded tiles are skipped without touching their cells (the old scan of
        // every cell of every tile was the dominant server-tick cost at S=6).
        let mut keys: Vec<((i32, i32, i32), f32)> = self
            .tiles
            .iter()
            .filter(|(_, tile)| tile.loaded && tile.active)
            .map(|(key, tile)| (*key, tile_activity_score(tile, &materials)))
            .collect();
        keys.sort_by(|left, right| {
            right
                .1
                .total_cmp(&left.1)
                .then_with(|| left.0.cmp(&right.0))
        });
        // Snapshot only the "fire neighbourhood": tiles with any heat (score > 0) plus their 3x3x3
        // ring. Conduct only reads a neighbour tile's snapshot for cells above 330 K — which live in
        // active tiles — so cold tiles outside the ring are never read. Cloning every loaded tile
        // (as before) is fine at S=1 (~32 KB each) but ~36x larger at S=6 and would clone the whole
        // loaded world every substep, freezing the tick. The ring keeps it O(fire size).
        let mut relevant: std::collections::HashSet<(i32, i32, i32)> =
            std::collections::HashSet::new();
        for (key, score) in &keys {
            if *score > 0.0 {
                for dy in -1..=1 {
                    for dz in -1..=1 {
                        for dx in -1..=1 {
                            relevant.insert((key.0 + dx, key.1 + dy, key.2 + dz));
                        }
                    }
                }
            }
        }
        // Lazy sub-cell: any relevant (ring) tile that is dormant — a previously-burnt tile the fire
        // has spread back toward — is expanded to live so the snapshot below can read its sub-cells
        // and conduct can heat it. conduct therefore never encounters a dormant tile.
        for key in &relevant {
            if let Some(tile) = self.tiles.get_mut(key) {
                if tile.loaded && !tile.is_live() {
                    tile.expand(si, dim);
                }
            }
        }
        let temperature_snapshots: HashMap<(i32, i32, i32), Vec<f32>> = self
            .tiles
            .iter()
            .filter(|(key, tile)| tile.loaded && relevant.contains(key))
            .map(|(key, tile)| (*key, tile.temperature.clone()))
            .collect();
        let material_snapshots: HashMap<(i32, i32, i32), Vec<i32>> = self
            .tiles
            .iter()
            .filter(|(key, tile)| tile.loaded && relevant.contains(key))
            .map(|(key, tile)| (*key, tile.material_ids.clone()))
            .collect();
        let mut external_heat_delta: HashMap<(i32, i32, i32), Vec<(usize, f32)>> = HashMap::new();
        let mut external_smoke_delta: HashMap<(i32, i32, i32), Vec<(usize, f32)>> = HashMap::new();
        for (key, _score) in keys {
            if visited_cells >= max_cells || mutation_count >= max_mutations {
                break;
            }
            let Some(tile) = self.tiles.get_mut(&key) else {
                continue;
            };
            let mut heat_delta = vec![0.0f32; cell_count];
            let mut smoke_delta = vec![0.0f32; cell_count];
            // Entity damage is deduped to one per block per step. Visuals are intentionally NOT
            // deduped: the client renderer no longer grows/smooths an interpolated footprint, so it
            // needs the actual hot sub-cell samples to draw a front that matches the Rust model.
            // The Java-side maxVisualRecordsPerTick budget remains the cap for very large fires.
            let mut damage_emitted = vec![false; BLOCKS_PER_SECTION];
            'cells: for y in 0..16 {
                for z in 0..dim {
                    for x in 0..dim {
                        if visited_cells >= max_cells || mutation_count >= max_mutations {
                            break 'cells;
                        }
                        let index = idx_dim(x, y, z, dim);
                        visited_cells += 1;
                        let material_id = tile.material_ids[index];
                        // -1 = fire/lava (sustained heat source). -2 = water (cold heat sink).
                        // 0 = inert / air. Positive = fuel index into materials table.
                        let sustained_heat_source = material_id == -1;
                        let ambient = 293.15f32;

                        if material_id == WATER_MATERIAL_ID {
                            // Water cells are thermally inert: kept at ambient, never burn,
                            // never radiate. Heat that conducts INTO water from neighbors is
                            // drained at the source side (see `conduct`) and never reappears.
                            tile.temperature[index] = ambient;
                            tile.fuel[index] = 0.0;
                            tile.smoke[index] = 0.0;
                            tile.moisture[index] = 0.0;
                            tile.char_progress[index] = 0.0;
                            tile.oxygen[index] = 1.0;
                            continue;
                        }

                        let material = material_from(&materials, material_id);
                        let mut temperature = tile.temperature[index];
                        let mut fuel = tile.fuel[index];
                        let mut oxygen = tile.oxygen[index];
                        let mut smoke = tile.smoke[index];
                        let mut moisture = tile.moisture[index];
                        let mut char_progress = tile.char_progress[index];

                        if sustained_heat_source {
                            temperature = temperature.max(1200.0);
                            oxygen = oxygen.max(0.75);
                        }

                        if moisture > 0.0 && temperature > 373.15 {
                            let evaporated = moisture.min((temperature - 373.15) * 0.00025 * dt);
                            moisture -= evaporated;
                            temperature -= evaporated * 420.0;
                            smoke += evaporated * 0.15;
                        }

                        let burning = material_id > 0
                            && fuel > 0.001
                            && oxygen > 0.03
                            && temperature >= material.ignition_temperature;
                        let flame_intensity = if sustained_heat_source {
                            1.0
                        } else if burning {
                            (0.45 + (temperature - material.ignition_temperature).max(0.0) / 900.0)
                                .clamp(0.45, 1.6)
                        } else {
                            0.0
                        };
                        if burning {
                            let rate_variation = burn_rate_variation(
                                seed,
                                key.0 * dim + x,
                                key.1 * 16 + y,
                                key.2 * dim + z,
                                si,
                            );
                            let rate = material.burn_rate
                                * rate_variation
                                * dt
                                * oxygen.clamp(0.0, 1.0)
                                * (1.0
                                    + (temperature - material.ignition_temperature).max(0.0)
                                        / material.ignition_temperature.max(1.0));
                            let burn = fuel.min(rate.max(0.0));
                            fuel -= burn;
                            oxygen = (oxygen - burn * 0.45).max(0.0);
                            smoke = (smoke + burn * material.smoke_yield * SMOKE_BURN_SCALE)
                                .min(SMOKE_MAX_CELL_DENSITY);
                            temperature += burn * material.heat_release;
                            char_progress += burn / material.fuel.max(0.001);

                            // Index of this sub-cell's block within the section (dim-16 grid). At
                            // S=1 this equals the cell index; the block coords below are x/S etc.
                            let block_idx = idx_dim(x / si, y, z / si, 16);
                            let bx = x / si;
                            let bz = z / si;
                            if fuel <= 0.02 {
                                // Whole-block-swallowed: only the sub-cell that COMPLETES the block
                                // emits a mutation (and so needs budget); earlier sub-cells burn out
                                // freely. At S=1 every burnout completes its block, so this reduces
                                // exactly to the original "gate the burnout on the mutation budget".
                                let completes = tile.burnt_count[block_idx] + 1 >= sub_per_block;
                                if !(completes && mutation_count >= max_mutations) {
                                    let action = if material.has_ash_stage || smoke > 0.2 {
                                        ACTION_SET_ASH
                                    } else {
                                        ACTION_SET_AIR
                                    };
                                    tile.material_ids[index] = 0;
                                    char_progress = 1.0;
                                    tile.burnt_count[block_idx] += 1;
                                    // Spent fuel no longer combusts, so it must not keep radiating
                                    // flame-hot. Knock the burnt-out sub-cell down to a brief
                                    // smoulder so flames stop promptly and only embers linger.
                                    temperature =
                                        temperature.min(ambient + config.burnout_temp_offset);
                                    if completes {
                                        push_mutation(
                                            mutations,
                                            mutation_count,
                                            key,
                                            bx,
                                            y,
                                            bz,
                                            action,
                                            material_id,
                                            0,
                                        );
                                        mutation_count += 1;
                                    }
                                }
                            } else if char_progress >= 0.45
                                && char_progress < 1.0
                                && material.has_char_stage
                            {
                                let completes = tile.char_count[block_idx] + 1 >= sub_per_block;
                                if !(completes && mutation_count >= max_mutations) {
                                    char_progress = 1.0;
                                    tile.char_count[block_idx] += 1;
                                    if completes {
                                        push_mutation(
                                            mutations,
                                            mutation_count,
                                            key,
                                            bx,
                                            y,
                                            bz,
                                            ACTION_SET_CHAR,
                                            material_id,
                                            0,
                                        );
                                        mutation_count += 1;
                                    }
                                }
                            }
                            if temperature > 900.0
                                && !damage_emitted[block_idx]
                                && mutation_count < max_mutations
                            {
                                push_mutation(
                                    mutations,
                                    mutation_count,
                                    key,
                                    bx,
                                    y,
                                    bz,
                                    ACTION_DAMAGE_ENTITY_AREA,
                                    material_id,
                                    ((temperature - 800.0) / 4.0) as i32,
                                );
                                mutation_count += 1;
                                damage_emitted[block_idx] = true;
                            }
                        }

                        // Newtonian cooling. Nudged up from 0.012 to tighten the self-limiting
                        // radius a little, but kept modest: raising it much further measurably
                        // slows the heating of the cell ahead of the front and can stop a thin
                        // fuel layer from propagating. The real cure for a burn that "just sits
                        // there" is the burn-out temperature drop above, not stronger cooling.
                        let cooling = (temperature - ambient)
                            * (config.cooling_base
                                + material.insulation * config.cooling_insulation)
                            * dt;
                        temperature -= cooling;
                        if sustained_heat_source {
                            temperature = temperature.max(1200.0);
                        }
                        oxygen += (1.0 - oxygen) * 0.09 * dt;
                        smoke *= (1.0 - 0.025 * dt).clamp(0.0, 1.0);
                        oxygen = (oxygen - smoke * 0.018 * dt).max(0.0);

                        if temperature > 330.0 {
                            conduct(
                                key,
                                tile,
                                &temperature_snapshots,
                                &material_snapshots,
                                &materials,
                                &mut external_heat_delta,
                                &mut heat_delta,
                                index,
                                x,
                                y,
                                z,
                                temperature,
                                flame_intensity,
                                dt,
                                material.insulation,
                                config,
                                seed,
                                dim,
                                si,
                                s,
                            );
                        }
                        if smoke > SMOKE_TRANSPORT_EPSILON {
                            transport_smoke(
                                key,
                                tile,
                                &material_snapshots,
                                &mut external_smoke_delta,
                                &mut smoke_delta,
                                index,
                                x,
                                y,
                                z,
                                smoke,
                                dt,
                                dim,
                            );
                        }

                        tile.temperature[index] = temperature.max(ambient);
                        tile.fuel[index] = fuel.max(0.0);
                        tile.oxygen[index] = oxygen.clamp(0.0, 1.0);
                        tile.smoke[index] = smoke.max(0.0);
                        tile.moisture[index] = moisture.max(0.0);
                        tile.char_progress[index] = char_progress;

                        if visual_capacity > 0 && (temperature > 360.0 || smoke > 0.05) {
                            record_visual_candidate(
                                visuals,
                                &mut visual_priorities,
                                &mut visual_count,
                                visual_capacity,
                                &mut weakest_visual_index,
                                &mut weakest_visual_priority,
                                VisualCandidate {
                                    priority: visual_priority(
                                        tile.material_ids[index],
                                        temperature,
                                        fuel,
                                        flame_intensity,
                                        smoke,
                                    ),
                                    key,
                                    x,
                                    y,
                                    z,
                                    temperature,
                                    flame: flame_intensity,
                                    smoke,
                                    oxygen,
                                },
                                s,
                            );
                        }
                    }
                }
            }
            for (index, delta) in heat_delta.into_iter().enumerate() {
                if tile.material_ids[index] == WATER_MATERIAL_ID {
                    tile.temperature[index] = 293.15;
                    continue;
                }
                tile.temperature[index] = (tile.temperature[index] + delta).max(293.15);
                if tile.material_ids[index] == -1 {
                    tile.temperature[index] = tile.temperature[index].max(1200.0);
                }
            }
            for (index, delta) in smoke_delta.into_iter().enumerate() {
                if tile.material_ids[index] == WATER_MATERIAL_ID {
                    tile.smoke[index] = 0.0;
                    continue;
                }
                tile.smoke[index] = (tile.smoke[index] + delta).clamp(0.0, SMOKE_MAX_CELL_DENSITY);
            }
            // Recompute whether the tile is still worth stepping; a fully cooled tile drops out of
            // the active set next step (external heat below can put a frontier tile back in).
            tile.active = tile.is_active();
        }
        for (key, deltas) in external_heat_delta {
            let Some(tile) = self.tiles.get_mut(&key) else {
                continue;
            };
            for (index, delta) in deltas {
                if tile.material_ids[index] == WATER_MATERIAL_ID {
                    tile.temperature[index] = 293.15;
                    continue;
                }
                tile.temperature[index] = (tile.temperature[index] + delta).max(293.15);
            }
            // A tile that received cross-tile heat is the spreading frontier — keep it active so it
            // is processed (and can ignite) next step even if it isn't hot enough yet this step.
            tile.active = true;
        }
        for (key, deltas) in external_smoke_delta {
            let Some(tile) = self.tiles.get_mut(&key) else {
                continue;
            };
            for (index, delta) in deltas {
                if tile.material_ids[index] == WATER_MATERIAL_ID {
                    tile.smoke[index] = 0.0;
                    continue;
                }
                tile.smoke[index] = (tile.smoke[index] + delta).clamp(0.0, SMOKE_MAX_CELL_DENSITY);
            }
            tile.active = true;
        }
        // Lazy sub-cell: compact any live tile that is now cold AND outside the active ring (the burnt
        // body the fire has moved on from) back to its ~114 KB block-level form, freeing the sub-cell
        // arrays. Active tiles and ring tiles stay live (they're stepped / read by conduct).
        let to_compact: Vec<(i32, i32, i32)> = self
            .tiles
            .iter()
            .filter(|(key, tile)| tile.is_live() && !tile.active && !relevant.contains(*key))
            .map(|(key, _)| *key)
            .collect();
        for key in to_compact {
            if let Some(tile) = self.tiles.get_mut(&key) {
                tile.compact(si, dim);
            }
        }
        (mutation_count, visual_count)
    }

    // Takes BLOCK coordinates (and a block-space radius) from Java and lights every S*S sub-cell of
    // each block in range, so a one-block spark is a full block of fire at any resolution.
    fn ignite(&mut self, x: i32, y: i32, z: i32, temperature: f32, radius: i32) {
        let radius = radius.max(0);
        let radius_sq = radius * radius;
        let s = self.cells_per_axis.max(1);
        let dim = self.dim();
        for bx in (x - radius)..=(x + radius) {
            for by in (y - radius)..=(y + radius) {
                for bz in (z - radius)..=(z + radius) {
                    let dx = bx - x;
                    let dy = by - y;
                    let dz = bz - z;
                    let distance_sq = dx * dx + dy * dy + dz * dz;
                    if distance_sq > radius_sq {
                        continue;
                    }
                    let key = (bx >> 4, by >> 4, bz >> 4);
                    let Some(tile) = self.tiles.get_mut(&key) else {
                        continue;
                    };
                    if !tile.is_live() {
                        tile.expand(s, dim);
                    }
                    let falloff = if radius == 0 {
                        1.0
                    } else {
                        1.0 - (distance_sq as f32).sqrt() / (radius as f32 + 1.0)
                    };
                    let target_temperature = temperature * (0.65 + falloff.clamp(0.0, 1.0) * 0.35);
                    let base_x = (bx & 15) * s;
                    let ly = by & 15;
                    let base_z = (bz & 15) * s;
                    for sz in 0..s {
                        for sx in 0..s {
                            let index = idx_dim(base_x + sx, ly, base_z + sz, dim);
                            tile.temperature[index] =
                                tile.temperature[index].max(target_temperature);
                            tile.oxygen[index] = tile.oxygen[index].max(0.75);
                        }
                    }
                    tile.active = true; // ignited cells: step this tile
                }
            }
        }
    }

    fn extinguish(
        &mut self,
        min_x: i32,
        min_y: i32,
        min_z: i32,
        max_x: i32,
        max_y: i32,
        max_z: i32,
    ) -> usize {
        if max_x < min_x || max_y < min_y || max_z < min_z {
            return 0;
        }
        let mut changed = 0usize;
        let s = self.cells_per_axis.max(1);
        let dim = self.dim();
        for (key, tile) in &mut self.tiles {
            let base_x = key.0 * 16;
            let base_y = key.1 * 16;
            let base_z = key.2 * 16;
            if max_x < base_x
                || min_x > base_x + 15
                || max_y < base_y
                || min_y > base_y + 15
                || max_z < base_z
                || min_z > base_z + 15
            {
                continue;
            }
            // Local BLOCK ranges within the section; each block expands to its S*S sub-cell column.
            let local_min_x = min_x.max(base_x) - base_x;
            let local_min_y = min_y.max(base_y) - base_y;
            let local_min_z = min_z.max(base_z) - base_z;
            let local_max_x = max_x.min(base_x + 15) - base_x;
            let local_max_y = max_y.min(base_y + 15) - base_y;
            let local_max_z = max_z.min(base_z + 15) - base_z;
            for y in local_min_y..=local_max_y {
                for z in local_min_z..=local_max_z {
                    for x in local_min_x..=local_max_x {
                        let mut block_changed = false;
                        for sz in 0..s {
                            for sx in 0..s {
                                let index = idx_dim(x * s + sx, y, z * s + sz, dim);
                                if tile.temperature[index] > 294.0 || tile.smoke[index] > 0.01 {
                                    block_changed = true;
                                }
                                tile.temperature[index] = 293.15;
                                tile.smoke[index] = 0.0;
                                tile.oxygen[index] = 1.0;
                            }
                        }
                        if block_changed {
                            changed += 1; // counted in BLOCKS
                        }
                    }
                }
            }
        }
        changed
    }

    // Returns the hottest of a block's S*S sub-cells ("is this block on fire?").
    fn query_temperature(&self, x: i32, y: i32, z: i32) -> f32 {
        let key = (x >> 4, y >> 4, z >> 4);
        let Some(tile) = self.tiles.get(&key) else {
            return 293.15;
        };
        if !tile.is_live() {
            return 293.15; // dormant tiles are cold by construction
        }
        let s = self.cells_per_axis.max(1);
        let dim = self.dim();
        let base_x = (x & 15) * s;
        let ly = y & 15;
        let base_z = (z & 15) * s;
        let mut max_t = f32::MIN;
        for sz in 0..s {
            for sx in 0..s {
                max_t = max_t.max(tile.temperature[idx_dim(base_x + sx, ly, base_z + sz, dim)]);
            }
        }
        max_t
    }

    // Returns the smokiest of a block's S*S sub-cells. Gameplay smoke exposure samples this at and
    // below entity head height so choking is tied to the authoritative solver, not client particles.
    fn query_smoke(&self, x: i32, y: i32, z: i32) -> f32 {
        let key = (x >> 4, y >> 4, z >> 4);
        let Some(tile) = self.tiles.get(&key) else {
            return 0.0;
        };
        if !tile.is_live() {
            return 0.0;
        }
        let s = self.cells_per_axis.max(1);
        let dim = self.dim();
        let base_x = (x & 15) * s;
        let ly = y & 15;
        let base_z = (z & 15) * s;
        let mut max_s = 0.0f32;
        for sz in 0..s {
            for sx in 0..s {
                max_s = max_s.max(tile.smoke[idx_dim(base_x + sx, ly, base_z + sz, dim)]);
            }
        }
        max_s
    }

    /// A tile is worth persisting only if it has live fire — any cell above ambient or any smoke.
    /// Pristine fuel tiles and fully burnt-and-cooled tiles reconstruct from the world's blocks on
    /// reload, so persisting them (each ~4 MB at S=6) would bloat the snapshot past the 2 GB JNI
    /// array limit and crash the save.
    fn tile_has_live_fire(tile: &Tile) -> bool {
        tile.temperature.iter().any(|&t| t > 294.15) || tile.smoke.iter().any(|&s| s > 0.01)
    }

    /// Keys of all tiles currently held (used after a load to resync the Java-side uploaded-section
    /// set to exactly what was restored, so cold sections re-upload from the world).
    #[cfg_attr(target_arch = "wasm32", allow(dead_code))]
    fn tile_keys(&self) -> Vec<(i32, i32, i32)> {
        self.tiles.keys().copied().collect()
    }

    /// Keys of tiles that currently have fire/heat. Java uses these to upload the ring of sections
    /// the fire could spread into (lazy upload) and to free tiles far from any fire.
    #[cfg_attr(target_arch = "wasm32", allow(dead_code))]
    fn active_tile_keys(&self) -> Vec<(i32, i32, i32)> {
        self.tiles
            .iter()
            .filter(|(_, tile)| tile.active)
            .map(|(key, _)| *key)
            .collect()
    }

    fn save(&self) -> Vec<u8> {
        let mut out = Vec::new();
        // Format 3 adds the sub-block resolution after the version word; per-tile arrays are
        // cell_count (= dim*16*dim) long. Versions 1/2 predate sub-block and are implicitly S=1.
        write_u32(&mut out, 3);
        write_u32(&mut out, self.cells_per_axis.max(1) as u32);
        let active: Vec<_> = self
            .tiles
            .iter()
            .filter(|(_, tile)| Self::tile_has_live_fire(tile))
            .collect();
        write_u32(&mut out, active.len() as u32);
        for (key, tile) in active {
            write_i32(&mut out, key.0);
            write_i32(&mut out, key.1);
            write_i32(&mut out, key.2);
            write_u32(&mut out, u32::from(tile.loaded));
            for value in &tile.material_ids {
                write_i32(&mut out, *value);
            }
            for array in [
                &tile.temperature,
                &tile.fuel,
                &tile.oxygen,
                &tile.smoke,
                &tile.moisture,
                &tile.char_progress,
            ] {
                for value in array {
                    write_f32(&mut out, *value);
                }
            }
        }
        out
    }

    fn load(&mut self, bytes: &[u8]) -> bool {
        let mut cursor = 0usize;
        let Some(version) = read_u32(bytes, &mut cursor) else {
            return false;
        };
        if version != 1 && version != 2 && version != 3 {
            return false;
        }
        // Resolution the snapshot was written at. v1/v2 predate sub-block and are always S=1.
        let stored_s = if version >= 3 {
            let Some(v) = read_u32(bytes, &mut cursor) else {
                return false;
            };
            v as i32
        } else {
            1
        };
        // Fail safe: refuse a snapshot whose grid resolution differs from this world's — the binary
        // layout would be mis-sized. The caller cold-starts instead of loading corrupt state.
        if stored_s != self.cells_per_axis.max(1) {
            return false;
        }
        let cell_count = self.cell_count();
        let dim = self.dim();
        let s = self.cells_per_axis.max(1);
        let Some(tile_count) = read_u32(bytes, &mut cursor) else {
            return false;
        };
        let mut tiles = HashMap::new();
        for _ in 0..tile_count {
            let Some(sx) = read_i32(bytes, &mut cursor) else {
                return false;
            };
            let Some(sy) = read_i32(bytes, &mut cursor) else {
                return false;
            };
            let Some(sz) = read_i32(bytes, &mut cursor) else {
                return false;
            };
            let loaded = if version >= 2 {
                let Some(value) = read_u32(bytes, &mut cursor) else {
                    return false;
                };
                value != 0
            } else {
                false
            };
            let mut material_ids = Vec::with_capacity(cell_count);
            for _ in 0..cell_count {
                let Some(value) = read_i32(bytes, &mut cursor) else {
                    return false;
                };
                material_ids.push(value);
            }
            let mut initial_state = vec![0.0f32; cell_count * STATE_FIELDS];
            for field in 0..STATE_FIELDS {
                for index in 0..cell_count {
                    let Some(value) = read_f32(bytes, &mut cursor) else {
                        return false;
                    };
                    initial_state[index * STATE_FIELDS + field] = value;
                }
            }
            let mut tile = Tile::new(material_ids, initial_state);
            tile.loaded = loaded;
            tile.recompute_block_counts(s, dim);
            tiles.insert((sx, sy, sz), tile);
        }
        if cursor != bytes.len() {
            return false;
        }
        self.tiles = tiles;
        true
    }
}

/// Linear cell index for the default S=1 grid (dim = 16). Kept as the fast path and used directly
/// by the tests and the editor bridge, which work at block resolution.
#[cfg(test)]
#[inline]
fn idx(x: i32, y: i32, z: i32) -> usize {
    ((y as usize) << 8) | ((z as usize) << 4) | x as usize
}

/// General linear cell index for a tile of horizontal dimension `dim` (= 16*S): X and Z run
/// `0..dim`, Y runs `0..16`. At dim == 16 this equals `idx` exactly. Layout is (y, z, x) so that
/// rows of constant Y/Z are contiguous in X, matching the original bit-packed order.
#[inline]
fn idx_dim(x: i32, y: i32, z: i32, dim: i32) -> usize {
    ((y * dim + z) * dim + x) as usize
}

fn material_from(materials: &[MaterialProps], id: i32) -> MaterialProps {
    if id <= 0 {
        return MaterialProps::default();
    }
    materials.get(id as usize).copied().unwrap_or_default()
}

fn spread_heat_variation(
    seed: i32,
    world_cell_x: i32,
    world_y: i32,
    world_cell_z: i32,
    cells_per_axis: i32,
) -> f32 {
    (1.0 + coherent_noise(
        seed,
        world_cell_x,
        world_y,
        world_cell_z,
        cells_per_axis,
        0x90a4_8424_b4e9_c8a7,
    ) * 0.22)
        .clamp(0.78, 1.22)
}

fn burn_rate_variation(
    seed: i32,
    world_cell_x: i32,
    world_y: i32,
    world_cell_z: i32,
    cells_per_axis: i32,
) -> f32 {
    (1.0 + coherent_noise(
        seed,
        world_cell_x,
        world_y,
        world_cell_z,
        cells_per_axis,
        0x663d_56f8_11dd_3c2b,
    ) * 0.12)
        .clamp(0.88, 1.12)
}

fn coherent_noise(
    seed: i32,
    world_cell_x: i32,
    world_y: i32,
    world_cell_z: i32,
    cells_per_axis: i32,
    salt: u64,
) -> f32 {
    let cells_per_axis = cells_per_axis.max(1);
    let block_x = world_cell_x.div_euclid(cells_per_axis);
    let block_z = world_cell_z.div_euclid(cells_per_axis);
    let patch = signed_noise(
        seed,
        block_x.div_euclid(4),
        world_y.div_euclid(2),
        block_z.div_euclid(4),
        salt,
    );
    let block = signed_noise(
        seed,
        block_x,
        world_y,
        block_z,
        salt ^ 0xbab0_c0de_f00d_1337,
    );
    let cell = signed_noise(
        seed,
        world_cell_x,
        world_y,
        world_cell_z,
        salt ^ 0x6d2b_79f5_aa35_1c49,
    );
    (patch * 0.55 + block * 0.30 + cell * 0.15).clamp(-1.0, 1.0)
}

fn signed_noise(seed: i32, x: i32, y: i32, z: i32, salt: u64) -> f32 {
    unit_noise(seed, x, y, z, salt) * 2.0 - 1.0
}

fn unit_noise(seed: i32, x: i32, y: i32, z: i32, salt: u64) -> f32 {
    let mut value = salt ^ u64::from(seed as u32).wrapping_mul(0x9e37_79b9_7f4a_7c15);
    value ^= u64::from(x as u32).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    value ^= u64::from(y as u32).wrapping_mul(0x94d0_49bb_1331_11eb);
    value ^= u64::from(z as u32).wrapping_mul(0xd6e8_feb8_6659_fd93);
    value ^= value >> 30;
    value = value.wrapping_mul(0xbf58_476d_1ce4_e5b9);
    value ^= value >> 27;
    value = value.wrapping_mul(0x94d0_49bb_1331_11eb);
    value ^= value >> 31;
    ((value >> 40) as f32) * (1.0 / ((1u32 << 24) as f32))
}

fn tile_activity_score(tile: &Tile, materials: &[MaterialProps]) -> f32 {
    let mut score = 0.0f32;
    for index in 0..tile.temperature.len() {
        let heat = (tile.temperature[index] - 330.0).max(0.0);
        let smoke = tile.smoke[index].max(0.0);
        let material = material_from(materials, tile.material_ids[index]);
        if smoke > 0.0 {
            score += smoke * 0.25;
        }
        if heat > 0.0 {
            score += heat * 0.01;
        }
        if tile.fuel[index] > 0.001 && tile.temperature[index] >= material.ignition_temperature {
            score += 1000.0 + tile.fuel[index] * 10.0;
        }
    }
    score
}

#[allow(clippy::too_many_arguments)]
fn conduct(
    key: (i32, i32, i32),
    tile: &Tile,
    temperature_snapshots: &HashMap<(i32, i32, i32), Vec<f32>>,
    material_snapshots: &HashMap<(i32, i32, i32), Vec<i32>>,
    materials: &[MaterialProps],
    external_heat_delta: &mut HashMap<(i32, i32, i32), Vec<(usize, f32)>>,
    heat_delta: &mut [f32],
    index: usize,
    x: i32,
    y: i32,
    z: i32,
    temperature: f32,
    flame_intensity: f32,
    dt: f32,
    insulation: f32,
    config: SolverConfig,
    seed: i32,
    dim: i32,
    cells_per_axis: i32,
    s: f32,
) {
    let transfer = config.conduction_transfer * dt * (1.0 - insulation.clamp(0.0, 0.95));
    // Radiant preheat (the term that actually drives ignition spread) is delivered to the whole
    // in-plane 8-neighbourhood, not just the four faces. With faces only, a fire grows as a
    // Manhattan diamond (the "blob out to the 4 edge-connected blocks" look) that the renderer then
    // has to smooth into an oval. Adding the four diagonals — weighted by 1/sqrt(2) for their
    // greater centre-to-centre distance — makes the preheat field isotropic, so the burn front
    // advances as a circle and reads as a gradual radial creep. Diagonals carry NO conduction
    // (conductive_bias 0.0): cells that share only an edge have no conductive face contact, and a
    // zero bias also keeps the diagonal radius at sqrt(2) < 2 so it can never reach across a
    // one-cell water gap and defeat the water firebreak.
    // Horizontal (in-plane) radiant exposures are scaled by S = `s`: with cells 1/S of a block, the
    // front must cross S* more cells to advance the same world distance, so radiant*S holds the
    // world spread-speed constant (validated in the editor). Vertical (up/down) exposures are NOT
    // scaled — Y is not subdivided, so those neighbours are still one block away. At S=1, s == 1.0
    // and every weight below is identical to the original kernel.
    let diag = config.diagonal_exposure;
    for (nx, ny, nz, conductive_bias, exposure) in [
        (x + 1, y, z, 1.0, s),
        (x - 1, y, z, 1.0, s),
        (x, y, z + 1, 1.0, s),
        (x, y, z - 1, 1.0, s),
        (x + 1, y, z + 1, 0.0, diag * s),
        (x + 1, y, z - 1, 0.0, diag * s),
        (x - 1, y, z + 1, 0.0, diag * s),
        (x - 1, y, z - 1, 0.0, diag * s),
        (x, y + 1, z, config.up_bias, config.up_exposure),
        (x, y - 1, z, config.down_bias, config.down_exposure),
    ] {
        let (neighbor_key, lx, ly, lz, neighbor_temperature, neighbor_material_id) =
            if (0..dim).contains(&nx) && (0..16).contains(&ny) && (0..dim).contains(&nz) {
                let neighbor = idx_dim(nx, ny, nz, dim);
                (
                    key,
                    nx,
                    ny,
                    nz,
                    tile.temperature[neighbor],
                    tile.material_ids[neighbor],
                )
            } else {
                // Cross-tile: horizontal axes have `dim` cells per tile, vertical has 16. div_euclid
                // / rem_euclid handle negative wrap and a non-power-of-two `dim` (e.g. 96 at S=6).
                let neighbor_cell_x = key.0 * dim + nx;
                let neighbor_cell_y = key.1 * 16 + ny;
                let neighbor_cell_z = key.2 * dim + nz;
                let neighbor_key = (
                    neighbor_cell_x.div_euclid(dim),
                    neighbor_cell_y >> 4,
                    neighbor_cell_z.div_euclid(dim),
                );
                let lx = neighbor_cell_x.rem_euclid(dim);
                let ly = neighbor_cell_y & 15;
                let lz = neighbor_cell_z.rem_euclid(dim);
                let Some(snapshot) = temperature_snapshots.get(&neighbor_key) else {
                    continue;
                };
                let Some(materials_snapshot) = material_snapshots.get(&neighbor_key) else {
                    continue;
                };
                let neighbor = idx_dim(lx, ly, lz, dim);
                (
                    neighbor_key,
                    lx,
                    ly,
                    lz,
                    snapshot[neighbor],
                    materials_snapshot[neighbor],
                )
            };
        let neighbor = idx_dim(lx, ly, lz, dim);
        let conductive_delta =
            (temperature - neighbor_temperature) * transfer * conductive_bias * 0.35;
        let mut neighbor_delta = 0.0f32;
        if conductive_delta > 0.0 {
            heat_delta[index] -= conductive_delta;
            // Water absorbs the drained heat unconditionally (acts as a perfect heat sink);
            // do NOT accumulate it on the water cell — the main loop forces water to ambient.
            if neighbor_material_id != WATER_MATERIAL_ID {
                neighbor_delta += conductive_delta;
            }
        }
        // Water quench: a water neighbour actively extinguishes this cell, pulling it toward
        // ambient far faster than passive conduction. ~20%/step (dt=0.05) drops a 1200 K
        // flame below typical ignition temperature in well under a second. Accumulates per
        // water neighbour (more surrounding water => faster); the main loop clamps to ambient.
        if neighbor_material_id == WATER_MATERIAL_ID {
            let quench = (temperature - 293.15).max(0.0) * (4.0 * dt).min(1.0);
            heat_delta[index] -= quench;
        }
        if flame_intensity > 0.0 && neighbor_material_id > 0 {
            let neighbor_material = material_from(materials, neighbor_material_id);
            let preheat_factor =
                if neighbor_temperature <= neighbor_material.ignition_temperature + 250.0 {
                    1.0
                } else {
                    0.35
                };
            let variation = spread_heat_variation(
                seed,
                neighbor_key.0 * dim + lx,
                neighbor_key.1 * 16 + ly,
                neighbor_key.2 * dim + lz,
                cells_per_axis,
            );
            // Held at 180. A burning cell must raise its neighbour to ignition WITHIN its own
            // burn window (it turns to ash and stops radiating once its fuel is spent); drop this
            // much below ~160 and a thin fuel layer self-extinguishes instead of propagating
            // (regression-guarded by surface_heat_source_ignites_and_spreads_through_grass). The
            // gradual-radial look comes from the isotropic 8-neighbour kernel above, not from
            // starving the front — the diagonals round the diamond off without extending its reach.
            let radiant_delta = flame_intensity
                * dt
                * config.radiant_strength
                * exposure
                * preheat_factor
                * variation
                * (1.0 - neighbor_material.insulation.clamp(0.0, 0.9) * 0.45);
            neighbor_delta += radiant_delta.max(0.0);
        }
        if neighbor_delta > 0.0 {
            if neighbor_key == key {
                heat_delta[neighbor] += neighbor_delta;
            } else {
                external_heat_delta
                    .entry(neighbor_key)
                    .or_default()
                    .push((neighbor, neighbor_delta));
            }
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn transport_smoke(
    key: (i32, i32, i32),
    tile: &Tile,
    material_snapshots: &HashMap<(i32, i32, i32), Vec<i32>>,
    external_smoke_delta: &mut HashMap<(i32, i32, i32), Vec<(usize, f32)>>,
    smoke_delta: &mut [f32],
    index: usize,
    x: i32,
    y: i32,
    z: i32,
    smoke: f32,
    dt: f32,
    dim: i32,
) {
    let up_fraction = (SMOKE_BUOYANCY_RATE * dt).clamp(0.0, 0.34);
    let lateral_fraction = (SMOKE_LATERAL_DIFFUSION_RATE * dt).clamp(0.0, 0.055);
    let down_fraction = if smoke > 1.5 {
        (SMOKE_DOWNWARD_DIFFUSION_RATE * dt).clamp(0.0, 0.018)
    } else {
        0.0
    };
    let max_move = smoke * 0.55;
    let mut moved = 0.0f32;

    for (nx, ny, nz, fraction) in [
        (x, y + 1, z, up_fraction),
        (x + 1, y, z, lateral_fraction),
        (x - 1, y, z, lateral_fraction),
        (x, y, z + 1, lateral_fraction),
        (x, y, z - 1, lateral_fraction),
        (x, y - 1, z, down_fraction),
    ] {
        if fraction <= 0.0 || moved >= max_move {
            continue;
        }
        let amount = (smoke * fraction).min(max_move - moved);
        if amount <= 0.0001 {
            continue;
        }
        let Some((neighbor_key, neighbor, neighbor_material_id)) =
            smoke_neighbor(key, tile, material_snapshots, nx, ny, nz, dim)
        else {
            continue;
        };
        if !smoke_can_enter(neighbor_material_id) {
            continue;
        }
        moved += amount;
        if neighbor_key == key {
            smoke_delta[neighbor] += amount;
        } else {
            external_smoke_delta
                .entry(neighbor_key)
                .or_default()
                .push((neighbor, amount));
        }
    }

    if moved > 0.0 {
        smoke_delta[index] -= moved;
    }
}

fn smoke_neighbor(
    key: (i32, i32, i32),
    tile: &Tile,
    material_snapshots: &HashMap<(i32, i32, i32), Vec<i32>>,
    nx: i32,
    ny: i32,
    nz: i32,
    dim: i32,
) -> Option<((i32, i32, i32), usize, i32)> {
    if (0..dim).contains(&nx) && (0..16).contains(&ny) && (0..dim).contains(&nz) {
        let neighbor = idx_dim(nx, ny, nz, dim);
        return Some((key, neighbor, tile.material_ids[neighbor]));
    }

    let neighbor_cell_x = key.0 * dim + nx;
    let neighbor_cell_y = key.1 * 16 + ny;
    let neighbor_cell_z = key.2 * dim + nz;
    let neighbor_key = (
        neighbor_cell_x.div_euclid(dim),
        neighbor_cell_y.div_euclid(16),
        neighbor_cell_z.div_euclid(dim),
    );
    let lx = neighbor_cell_x.rem_euclid(dim);
    let ly = neighbor_cell_y.rem_euclid(16);
    let lz = neighbor_cell_z.rem_euclid(dim);
    let snapshot = material_snapshots.get(&neighbor_key)?;
    let neighbor = idx_dim(lx, ly, lz, dim);
    Some((neighbor_key, neighbor, snapshot[neighbor]))
}

fn smoke_can_enter(material_id: i32) -> bool {
    material_id == 0 || material_id == -1
}

#[derive(Clone, Copy)]
struct VisualCandidate {
    priority: f32,
    key: (i32, i32, i32),
    x: i32,
    y: i32,
    z: i32,
    temperature: f32,
    flame: f32,
    smoke: f32,
    oxygen: f32,
}

#[allow(clippy::too_many_arguments)]
fn record_visual_candidate(
    out: &mut [f32],
    priorities: &mut [f32],
    visual_count: &mut usize,
    visual_capacity: usize,
    weakest_index: &mut usize,
    weakest_priority: &mut f32,
    candidate: VisualCandidate,
    s: f32,
) {
    if visual_capacity == 0 {
        return;
    }
    if *visual_count < visual_capacity {
        let slot = *visual_count;
        push_visual_candidate(out, slot, candidate, s);
        priorities[slot] = candidate.priority;
        if candidate.priority < *weakest_priority {
            *weakest_priority = candidate.priority;
            *weakest_index = slot;
        }
        *visual_count += 1;
        return;
    }

    if candidate.priority <= *weakest_priority {
        return;
    }
    push_visual_candidate(out, *weakest_index, candidate, s);
    priorities[*weakest_index] = candidate.priority;
    let (new_weakest_index, new_weakest_priority) = weakest_visual(priorities);
    *weakest_index = new_weakest_index;
    *weakest_priority = new_weakest_priority;
}

fn weakest_visual(priorities: &[f32]) -> (usize, f32) {
    let mut weakest_index = 0usize;
    let mut weakest_priority = f32::INFINITY;
    for (index, priority) in priorities.iter().copied().enumerate() {
        if priority < weakest_priority {
            weakest_priority = priority;
            weakest_index = index;
        }
    }
    (weakest_index, weakest_priority)
}

fn push_visual_candidate(out: &mut [f32], record: usize, candidate: VisualCandidate, s: f32) {
    push_visual(
        out,
        record,
        candidate.key,
        candidate.x,
        candidate.y,
        candidate.z,
        candidate.temperature,
        candidate.flame,
        candidate.smoke,
        candidate.oxygen,
        s,
    );
}

fn visual_priority(material_id: i32, temperature: f32, fuel: f32, flame: f32, smoke: f32) -> f32 {
    let live_fuel = material_id > 0 && fuel > 0.001;
    let sustained_source = material_id == -1;
    let live_flame = if live_fuel || sustained_source {
        flame
    } else {
        flame * 0.18
    };
    let heat = ((temperature - 360.0) / 4.0).clamp(0.0, 220.0);
    let smoke_hint = smoke.clamp(0.0, SMOKE_MAX_CELL_DENSITY) * 5.0;
    let fuel_bonus = if live_fuel { 420.0 } else { 0.0 };
    let source_bonus = if sustained_source { 260.0 } else { 0.0 };
    let residue_penalty = if material_id <= 0 && flame <= 0.0 {
        180.0
    } else {
        0.0
    };

    live_flame * 1400.0 + heat + smoke_hint + fuel_bonus + source_bonus - residue_penalty
}

fn push_mutation(
    out: &mut [i32],
    record: usize,
    key: (i32, i32, i32),
    x: i32,
    y: i32,
    z: i32,
    action: i32,
    material_id: i32,
    aux: i32,
) {
    let base = record * MUTATION_RECORD_INTS;
    if base + 5 >= out.len() {
        return;
    }
    out[base] = key.0 * 16 + x;
    out[base + 1] = key.1 * 16 + y;
    out[base + 2] = key.2 * 16 + z;
    out[base + 3] = action;
    out[base + 4] = material_id;
    out[base + 5] = aux;
}

#[allow(clippy::too_many_arguments)]
fn push_visual(
    out: &mut [f32],
    record: usize,
    key: (i32, i32, i32),
    x: i32,
    y: i32,
    z: i32,
    temperature: f32,
    flame: f32,
    smoke: f32,
    oxygen: f32,
    s: f32,
) {
    let base = record * VISUAL_RECORD_FLOATS;
    if base + 7 >= out.len() {
        return;
    }
    // Sub-cell-accurate world position so the client can render a fine flame field: X/Z are split
    // into S cells per block, so the cell centre is block-origin + (cell + 0.5)/S. Y is one cell per
    // block. At S=1 this is exactly the original block-centre (+0.5).
    out[base] = (key.0 * 16) as f32 + (x as f32 + 0.5) / s;
    out[base + 1] = (key.1 * 16 + y) as f32 + 0.5;
    out[base + 2] = (key.2 * 16) as f32 + (z as f32 + 0.5) / s;
    out[base + 3] = temperature;
    out[base + 4] = flame;
    out[base + 5] = smoke;
    out[base + 6] = oxygen;
    out[base + 7] = ((temperature - 293.15) / 1000.0).clamp(0.0, 4.0);
}

#[cfg(not(target_arch = "wasm32"))]
mod jni_bridge {
    //! JNI entry points the mod's native loader binds to. Built only for the native target; the
    //! WebAssembly parameter-editor build skips this module and the `jni` dependency entirely.
    use super::*;
    use jni::JNIEnv;
    use jni::objects::{JByteArray, JClass, JFloatArray, JIntArray};
    use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jfloat, jint, jintArray, jlong};

    unsafe fn world_from_handle<'a>(handle: jlong) -> Option<&'a mut World> {
        if handle == 0 {
            return None;
        }
        Some(unsafe { &mut *(handle as *mut World) })
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_abiVersionNative(
        _env: JNIEnv,
        _class: JClass,
    ) -> jint {
        ABI_VERSION
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_createWorldNative(
        _env: JNIEnv,
        _class: JClass,
        dimension_id: jint,
        min_build_height: jint,
        max_build_height: jint,
        seed: jint,
    ) -> jlong {
        Box::into_raw(Box::new(World::new(
            dimension_id,
            min_build_height,
            max_build_height,
            seed,
        ))) as jlong
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_destroyWorldNative(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) {
        if handle != 0 {
            unsafe {
                drop(Box::from_raw(handle as *mut World));
            }
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_setSubBlockResolutionNative(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
        cells_per_axis: jint,
    ) {
        let Some(world) = (unsafe { world_from_handle(handle) }) else {
            return;
        };
        // Must be called right after createWorld, before any tile is uploaded: it resizes the cell grid
        // and drops any existing tiles.
        world.set_cells_per_axis(cells_per_axis);
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_setMaterialNative(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
        id: jint,
        fuel: jfloat,
        has_char_stage: jboolean,
        has_ash_stage: jboolean,
        ignition_temperature: jfloat,
        burn_rate: jfloat,
        heat_release: jfloat,
        smoke_yield: jfloat,
        insulation: jfloat,
    ) {
        let Some(world) = (unsafe { world_from_handle(handle) }) else {
            return;
        };
        let index = id.max(0) as usize;
        if world.materials.len() <= index {
            world.materials.resize(index + 1, MaterialProps::default());
        }
        world.materials[index] = MaterialProps {
            fuel,
            has_char_stage: has_char_stage != JNI_FALSE,
            has_ash_stage: has_ash_stage != JNI_FALSE,
            ignition_temperature,
            burn_rate,
            heat_release,
            smoke_yield,
            insulation,
        };
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_setTileNative(
        env: JNIEnv,
        _class: JClass,
        handle: jlong,
        section_x: jint,
        section_y: jint,
        section_z: jint,
        material_ids_array: JIntArray,
        initial_state_array: JFloatArray,
    ) {
        let Some(world) = (unsafe { world_from_handle(handle) }) else {
            return;
        };
        // Java uploads BLOCK-level arrays (one entry per block, 4096); the solver expands them across
        // S*S sub-cells. This keeps the JNI transfer small regardless of resolution.
        let mut block_materials = vec![0i32; BLOCKS_PER_SECTION];
        if env
            .get_int_array_region(&material_ids_array, 0, &mut block_materials)
            .is_err()
        {
            return;
        }
        let mut block_state = vec![0.0f32; BLOCKS_PER_SECTION * STATE_FIELDS];
        if env
            .get_float_array_region(&initial_state_array, 0, &mut block_state)
            .is_err()
        {
            return;
        }
        world.set_tile_from_blocks(
            (section_x, section_y, section_z),
            &block_materials,
            &block_state,
        );
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_setCellNative(
        env: JNIEnv,
        _class: JClass,
        handle: jlong,
        x: jint,
        y: jint,
        z: jint,
        material_id: jint,
        initial_state_array: JFloatArray,
    ) {
        let Some(world) = (unsafe { world_from_handle(handle) }) else {
            return;
        };
        let mut initial_state = vec![0.0f32; STATE_FIELDS];
        if env
            .get_float_array_region(&initial_state_array, 0, &mut initial_state)
            .is_err()
        {
            return;
        }
        // x,y,z are BLOCK coordinates; the solver writes all S*S sub-cells and resets the block tally.
        world.set_cell_from_block(x, y, z, material_id, &initial_state);
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_removeTileNative(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
        section_x: jint,
        section_y: jint,
        section_z: jint,
    ) {
        let Some(world) = (unsafe { world_from_handle(handle) }) else {
            return;
        };
        world.tiles.remove(&(section_x, section_y, section_z));
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_setTileLoadedNative(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
        section_x: jint,
        section_y: jint,
        section_z: jint,
        loaded: jint,
    ) {
        let Some(world) = (unsafe { world_from_handle(handle) }) else {
            return;
        };
        if let Some(tile) = world.tiles.get_mut(&(section_x, section_y, section_z)) {
            tile.loaded = loaded != 0;
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_igniteNative(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
        x: jint,
        y: jint,
        z: jint,
        temperature: jfloat,
        radius: jint,
    ) {
        let Some(world) = (unsafe { world_from_handle(handle) }) else {
            return;
        };
        world.ignite(x, y, z, temperature, radius);
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_extinguishNative(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
        min_x: jint,
        min_y: jint,
        min_z: jint,
        max_x: jint,
        max_y: jint,
        max_z: jint,
    ) -> jint {
        let Some(world) = (unsafe { world_from_handle(handle) }) else {
            return 0;
        };
        world.extinguish(min_x, min_y, min_z, max_x, max_y, max_z) as jint
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_stepNative(
        env: JNIEnv,
        _class: JClass,
        handle: jlong,
        dt_seconds: jfloat,
        max_cells: jint,
        max_mutations: jint,
        max_visuals: jint,
        out_mutations_array: JIntArray,
        out_visuals_array: JFloatArray,
    ) -> jint {
        let Some(world) = (unsafe { world_from_handle(handle) }) else {
            return 0;
        };
        let mutation_capacity = max_mutations.max(0) as usize;
        let visual_capacity = max_visuals.max(0) as usize;
        let mut mutations = vec![0i32; mutation_capacity * MUTATION_RECORD_INTS];
        let mut visuals = vec![0.0f32; visual_capacity * VISUAL_RECORD_FLOATS];
        let (mutation_count, visual_count) = world.step(
            dt_seconds.max(0.0),
            max_cells.max(0) as usize,
            mutation_capacity,
            visual_capacity,
            &mut mutations,
            &mut visuals,
        );
        let _ = env.set_int_array_region(&out_mutations_array, 0, &mutations);
        let _ = env.set_float_array_region(&out_visuals_array, 0, &visuals);
        ((visual_count as i32) << 16) | (mutation_count as i32 & 0xffff)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_queryTemperatureNative(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
        x: jint,
        y: jint,
        z: jint,
    ) -> jfloat {
        let Some(world) = (unsafe { world_from_handle(handle) }) else {
            return 293.15;
        };
        world.query_temperature(x, y, z)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_querySmokeNative(
        _env: JNIEnv,
        _class: JClass,
        handle: jlong,
        x: jint,
        y: jint,
        z: jint,
    ) -> jfloat {
        let Some(world) = (unsafe { world_from_handle(handle) }) else {
            return 0.0;
        };
        world.query_smoke(x, y, z)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_saveNative(
        env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jbyteArray {
        let Some(world) = (unsafe { world_from_handle(handle) }) else {
            return std::ptr::null_mut();
        };
        env.byte_array_from_slice(&world.save())
            .map(JByteArray::into_raw)
            .unwrap_or(std::ptr::null_mut())
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_loadedTileKeysNative(
        env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jintArray {
        // Returns the current tile keys as a flat int array [x0,y0,z0, x1,y1,z1, ...]. Java uses this
        // right after a load to set its uploaded-section set to exactly what was restored.
        let Some(world) = (unsafe { world_from_handle(handle) }) else {
            return std::ptr::null_mut();
        };
        let mut flat: Vec<i32> = Vec::with_capacity(world.tiles.len() * 3);
        for key in world.tile_keys() {
            flat.push(key.0);
            flat.push(key.1);
            flat.push(key.2);
        }
        let Ok(array) = env.new_int_array(flat.len() as i32) else {
            return std::ptr::null_mut();
        };
        let _ = env.set_int_array_region(&array, 0, &flat);
        array.into_raw()
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_activeTileKeysNative(
        env: JNIEnv,
        _class: JClass,
        handle: jlong,
    ) -> jintArray {
        // Flat [x0,y0,z0, ...] of tiles that currently have fire/heat. Drives lazy upload (which sections
        // the fire might reach) and lazy freeing (tiles far from any of these can be dropped).
        let Some(world) = (unsafe { world_from_handle(handle) }) else {
            return std::ptr::null_mut();
        };
        let keys = world.active_tile_keys();
        let mut flat: Vec<i32> = Vec::with_capacity(keys.len() * 3);
        for key in keys {
            flat.push(key.0);
            flat.push(key.1);
            flat.push(key.2);
        }
        let Ok(array) = env.new_int_array(flat.len() as i32) else {
            return std::ptr::null_mut();
        };
        let _ = env.set_int_array_region(&array, 0, &flat);
        array.into_raw()
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_zinnusl_realisticfire_nativebridge_RealisticFireNativeSolver_loadNative(
        env: JNIEnv,
        _class: JClass,
        handle: jlong,
        snapshot: JByteArray,
    ) -> jboolean {
        let Some(world) = (unsafe { world_from_handle(handle) }) else {
            return JNI_FALSE;
        };
        if let Ok(bytes) = env.convert_byte_array(snapshot) {
            if world.load(&bytes) {
                return JNI_TRUE;
            }
        }
        JNI_FALSE
    }
} // mod jni_bridge

fn write_u32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn write_i32(out: &mut Vec<u8>, value: i32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn write_f32(out: &mut Vec<u8>, value: f32) {
    out.extend_from_slice(&value.to_le_bytes());
}

fn read_u32(bytes: &[u8], cursor: &mut usize) -> Option<u32> {
    let end = *cursor + 4;
    let slice = bytes.get(*cursor..end)?;
    *cursor = end;
    Some(u32::from_le_bytes(slice.try_into().ok()?))
}

fn read_i32(bytes: &[u8], cursor: &mut usize) -> Option<i32> {
    let end = *cursor + 4;
    let slice = bytes.get(*cursor..end)?;
    *cursor = end;
    Some(i32::from_le_bytes(slice.try_into().ok()?))
}

fn read_f32(bytes: &[u8], cursor: &mut usize) -> Option<f32> {
    let end = *cursor + 4;
    let slice = bytes.get(*cursor..end)?;
    *cursor = end;
    Some(f32::from_le_bytes(slice.try_into().ok()?))
}

#[cfg(target_arch = "wasm32")]
mod wasm_bridge {
    //! Raw-WASM entry points for the browser parameter editor. There is no wasm-bindgen: every
    //! function is a plain `extern "C"` export the page calls through the standard `WebAssembly`
    //! API, sharing the module's linear `memory`. The simulation core is byte-for-byte the one the
    //! mod ships, so any parameter set dialed in here maps straight back to `SolverConfig::default()`
    //! and the material table.
    use super::*;

    // The editor visualises one horizontal ground layer of at most (2*MAX_HALF+1)^2 cells. Sized
    // generously so the sub-block-resolution mode (each block split into S*S finer cells) still
    // fits a useful block-area: e.g. a 20-block radius at S=4 needs a cell radius of 80.
    const MAX_HALF: i32 = 90;
    const FIELD_DIM: usize = (MAX_HALF * 2 + 1) as usize;
    const FIELD_FLOATS: usize = FIELD_DIM * FIELD_DIM * 3;

    static mut WORLD: Option<World> = None;
    static mut FIELD: [f32; FIELD_FLOATS] = [0.0; FIELD_FLOATS];

    // Single-threaded wasm: a process-global World is safe. `&raw mut` avoids materialising a
    // reference to the mutable static (the borrow lives only inside these calls).
    fn world() -> &'static mut World {
        unsafe { (*(&raw mut WORLD)).get_or_insert_with(|| World::new(0, -64, 320, 1)) }
    }

    #[unsafe(no_mangle)]
    pub extern "C" fn rf_init(seed: i32) {
        unsafe {
            *(&raw mut WORLD) = Some(World::new(0, -64, 320, seed));
        }
    }

    #[unsafe(no_mangle)]
    pub extern "C" fn rf_set_subblock_resolution(cells_per_axis: i32) {
        world().set_cells_per_axis(cells_per_axis);
    }

    #[unsafe(no_mangle)]
    pub extern "C" fn rf_set_config(
        radiant_strength: f32,
        diagonal_exposure: f32,
        up_bias: f32,
        up_exposure: f32,
        down_bias: f32,
        down_exposure: f32,
        conduction_transfer: f32,
        cooling_base: f32,
        cooling_insulation: f32,
        burnout_temp_offset: f32,
    ) {
        world().set_config(SolverConfig {
            radiant_strength,
            diagonal_exposure,
            up_bias,
            up_exposure,
            down_bias,
            down_exposure,
            conduction_transfer,
            cooling_base,
            cooling_insulation,
            burnout_temp_offset,
        });
    }

    #[unsafe(no_mangle)]
    pub extern "C" fn rf_set_material(
        id: i32,
        fuel: f32,
        has_char_stage: i32,
        has_ash_stage: i32,
        ignition_temperature: f32,
        burn_rate: f32,
        heat_release: f32,
        smoke_yield: f32,
        insulation: f32,
    ) {
        let w = world();
        let index = id.max(0) as usize;
        if w.materials.len() <= index {
            w.materials.resize(index + 1, MaterialProps::default());
        }
        w.materials[index] = MaterialProps {
            fuel,
            has_char_stage: has_char_stage != 0,
            has_ash_stage: has_ash_stage != 0,
            ignition_temperature,
            burn_rate,
            heat_release,
            smoke_yield,
            insulation,
        };
    }

    /// Rebuild the world as a flat fuel field in CELL coordinates: a square ground patch of
    /// `material_id` at world height `ground_y`, every other cell ambient air. Clears any previous
    /// tiles.
    #[unsafe(no_mangle)]
    pub extern "C" fn rf_setup_ground(
        half: i32,
        ground_y: i32,
        material_id: i32,
        fuel: f32,
        moisture: f32,
    ) {
        let half = half.clamp(0, MAX_HALF);
        let w = world();
        w.tiles.clear();
        let dim = w.dim();
        let cell_count = w.cell_count();
        for cell_z in -half..=half {
            for cell_x in -half..=half {
                let key = (
                    cell_x.div_euclid(dim),
                    ground_y >> 4,
                    cell_z.div_euclid(dim),
                );
                let lx = cell_x.rem_euclid(dim);
                let ly = ground_y & 15;
                let lz = cell_z.rem_euclid(dim);
                // Empty initial-state => Tile::new keeps ambient defaults (293.15 K, oxygen 1.0, no
                // fuel) for every cell; we then paint just the ground layer.
                let tile = w
                    .tiles
                    .entry(key)
                    .or_insert_with(|| Tile::new(vec![0; cell_count], Vec::new()));
                let i = idx_dim(lx, ly, lz, dim);
                tile.material_ids[i] = material_id;
                tile.fuel[i] = fuel;
                tile.moisture[i] = moisture;
                tile.oxygen[i] = 1.0;
                tile.temperature[i] = 293.15;
            }
        }
    }

    /// Overwrite one cell's material (e.g. -1 sustained source, -2 water). A sustained source is
    /// seeded hot so it lights neighbours at once.
    #[unsafe(no_mangle)]
    pub extern "C" fn rf_set_cell_material(x: i32, y: i32, z: i32, material_id: i32) {
        let w = world();
        let dim = w.dim();
        let cell_count = w.cell_count();
        let key = (x.div_euclid(dim), y >> 4, z.div_euclid(dim));
        let tile = w
            .tiles
            .entry(key)
            .or_insert_with(|| Tile::new(vec![0; cell_count], Vec::new()));
        let lx = x.rem_euclid(dim);
        let ly = y & 15;
        let lz = z.rem_euclid(dim);
        let i = idx_dim(lx, ly, lz, dim);
        tile.material_ids[i] = material_id;
        if material_id == -1 {
            tile.temperature[i] = 1200.0;
            tile.oxygen[i] = 1.0;
            tile.active = true;
        }
    }

    #[unsafe(no_mangle)]
    pub extern "C" fn rf_ignite(x: i32, y: i32, z: i32, temperature: f32, radius: i32) {
        let radius = radius.max(0);
        let radius_sq = radius * radius;
        let w = world();
        let dim = w.dim();
        let cell_count = w.cell_count();
        for cell_z in (z - radius)..=(z + radius) {
            for cell_x in (x - radius)..=(x + radius) {
                let dx = cell_x - x;
                let dz = cell_z - z;
                let distance_sq = dx * dx + dz * dz;
                if distance_sq > radius_sq {
                    continue;
                }
                let key = (cell_x.div_euclid(dim), y >> 4, cell_z.div_euclid(dim));
                let tile = w
                    .tiles
                    .entry(key)
                    .or_insert_with(|| Tile::new(vec![0; cell_count], Vec::new()));
                let lx = cell_x.rem_euclid(dim);
                let ly = y & 15;
                let lz = cell_z.rem_euclid(dim);
                let i = idx_dim(lx, ly, lz, dim);
                let falloff = if radius == 0 {
                    1.0
                } else {
                    1.0 - (distance_sq as f32).sqrt() / (radius as f32 + 1.0)
                };
                let target_temperature = temperature * (0.65 + falloff.clamp(0.0, 1.0) * 0.35);
                tile.temperature[i] = tile.temperature[i].max(target_temperature);
                tile.oxygen[i] = tile.oxygen[i].max(0.75);
                tile.active = true;
            }
        }
    }

    /// Advance the simulation by `substeps` sub-ticks of `dt` seconds each.
    #[unsafe(no_mangle)]
    pub extern "C" fn rf_step(dt: f32, substeps: i32) {
        let w = world();
        // Cap sized for the finest field: a full (2*MAX_HALF+1)^2 layer could burn out in one go.
        const MUT_CAP: usize = 33000;
        let mut mutations = vec![0i32; MUT_CAP * MUTATION_RECORD_INTS];
        let mut visuals: Vec<f32> = Vec::new();
        for _ in 0..substeps.max(1) {
            w.step(dt, usize::MAX, MUT_CAP, 0, &mut mutations, &mut visuals);
        }
    }

    /// Fill the shared field buffer with `[temperature, fuel, material]` per ground cell — row-major
    /// over `bz` (outer) then `bx` (inner) across `-half..=half` — and return a pointer the page
    /// reads from linear memory. Re-read after any call that may have grown memory.
    #[unsafe(no_mangle)]
    pub extern "C" fn rf_read_layer(half: i32, ground_y: i32) -> *const f32 {
        let half = half.clamp(0, MAX_HALF);
        let w = world();
        let dim = w.dim();
        let s = w.cells_per_axis.max(1);
        unsafe {
            let buf = &mut *(&raw mut FIELD);
            let mut p = 0usize;
            for cell_z in -half..=half {
                for cell_x in -half..=half {
                    let key = (
                        cell_x.div_euclid(dim),
                        ground_y >> 4,
                        cell_z.div_euclid(dim),
                    );
                    let lx = cell_x.rem_euclid(dim);
                    let ly = ground_y & 15;
                    let lz = cell_z.rem_euclid(dim);
                    let i = idx_dim(lx, ly, lz, dim);
                    let block = idx_dim(lx / s, ly, lz / s, 16);
                    let (t, f, m) = match w.tiles.get(&key) {
                        Some(tile) if tile.is_live() => (
                            tile.temperature[i],
                            tile.fuel[i],
                            tile.material_ids[i] as f32,
                        ),
                        // Dormant (compacted) tile: read the cheap per-block summary.
                        Some(tile) => (
                            tile.block_state
                                .get(block * STATE_FIELDS)
                                .copied()
                                .unwrap_or(293.15),
                            tile.block_state
                                .get(block * STATE_FIELDS + 1)
                                .copied()
                                .unwrap_or(0.0),
                            tile.block_materials.get(block).copied().unwrap_or(0) as f32,
                        ),
                        None => (293.15, 0.0, 0.0),
                    };
                    buf[p] = t;
                    buf[p + 1] = f;
                    buf[p + 2] = m;
                    p += 3;
                }
            }
            buf.as_ptr()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ambient_state() -> Vec<f32> {
        let mut state = vec![0.0; CELL_COUNT * STATE_FIELDS];
        for index in 0..CELL_COUNT {
            state[index * STATE_FIELDS] = 293.15;
            state[index * STATE_FIELDS + 2] = 1.0;
        }
        state
    }

    fn cardinal_neighbor_temperatures(seed: i32) -> [f32; 4] {
        let mut world = World::new(0, -64, 320, seed);
        world.materials.push(MaterialProps {
            fuel: 1.0,
            has_char_stage: false,
            has_ash_stage: true,
            ignition_temperature: 460.0,
            burn_rate: 0.18,
            heat_release: 620.0,
            smoke_yield: 0.12,
            insulation: 0.05,
        });
        let mut materials = vec![0; CELL_COUNT];
        let mut state = ambient_state();
        let source = idx(8, 8, 8);
        materials[source] = -1;
        state[source * STATE_FIELDS] = 1200.0;
        for cell in [idx(9, 8, 8), idx(7, 8, 8), idx(8, 8, 9), idx(8, 8, 7)] {
            materials[cell] = 1;
            state[cell * STATE_FIELDS + 1] = 1.0;
        }
        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mutations = vec![0; 64 * MUTATION_RECORD_INTS];
        let mut visuals = Vec::new();
        world.step(0.05, CELL_COUNT, 64, 0, &mut mutations, &mut visuals);

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        [
            tile.temperature[idx(9, 8, 8)],
            tile.temperature[idx(7, 8, 8)],
            tile.temperature[idx(8, 8, 9)],
            tile.temperature[idx(8, 8, 7)],
        ]
    }

    #[test]
    fn radiant_preheat_has_seeded_spatial_variation() {
        let first = cardinal_neighbor_temperatures(77);
        let second = cardinal_neighbor_temperatures(77);
        assert_eq!(first, second);

        let min = first.into_iter().fold(f32::INFINITY, f32::min);
        let max = first.into_iter().fold(f32::NEG_INFINITY, f32::max);
        assert!(
            max - min > 0.5,
            "symmetric neighbours should heat at different rates: {first:?}"
        );
    }

    #[test]
    fn ignition_consumes_fuel_and_outputs_visuals() {
        let mut world = World::new(0, -64, 320, 1);
        world.materials.push(MaterialProps {
            fuel: 1.0,
            has_char_stage: true,
            has_ash_stage: true,
            ignition_temperature: 573.0,
            burn_rate: 1.0,
            heat_release: 1800.0,
            smoke_yield: 0.35,
            insulation: 0.3,
        });
        let mut materials = vec![0; CELL_COUNT];
        let mut state = vec![0.0; CELL_COUNT * STATE_FIELDS];
        let center = idx(8, 8, 8);
        materials[center] = 1;
        state[center * STATE_FIELDS] = 1200.0;
        state[center * STATE_FIELDS + 1] = 1.0;
        state[center * STATE_FIELDS + 2] = 1.0;
        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mutations = vec![0; 32 * MUTATION_RECORD_INTS];
        let mut visuals = vec![0.0; 32 * VISUAL_RECORD_FLOATS];
        let (_mutation_count, visual_count) =
            world.step(0.05, CELL_COUNT, 32, 32, &mut mutations, &mut visuals);

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        assert!(tile.fuel[center] < 1.0);
        assert!(visual_count > 0);
    }

    #[test]
    fn save_load_preserves_temperature() {
        let mut world = World::new(0, -64, 320, 1);
        let mut materials = vec![0; CELL_COUNT];
        materials[idx(1, 2, 3)] = 1;
        let mut state = vec![0.0; CELL_COUNT * STATE_FIELDS];
        state[idx(1, 2, 3) * STATE_FIELDS] = 900.0;
        world.tiles.insert((4, 5, 6), Tile::new(materials, state));

        let bytes = world.save();
        let mut loaded = World::new(0, -64, 320, 1);
        assert!(loaded.load(&bytes));

        assert_eq!(
            loaded.query_temperature(4 * 16 + 1, 5 * 16 + 2, 6 * 16 + 3),
            900.0
        );
    }

    #[test]
    fn load_rejects_trailing_bytes() {
        let mut world = World::new(0, -64, 320, 1);
        let mut state = ambient_state();
        state[idx(2, 3, 4) * STATE_FIELDS] = 900.0;
        world
            .tiles
            .insert((0, 0, 0), Tile::new(vec![0; CELL_COUNT], state));
        let mut bytes = world.save();
        bytes.push(7);

        let mut loaded = World::new(0, -64, 320, 1);

        assert!(!loaded.load(&bytes));
        assert_eq!(loaded.query_temperature(2, 3, 4), 293.15);
    }

    #[test]
    fn hot_tile_is_prioritized_when_cell_budget_is_limited() {
        let mut world = World::new(0, -64, 320, 1);
        world.materials.push(MaterialProps {
            fuel: 1.0,
            has_char_stage: true,
            has_ash_stage: true,
            ignition_temperature: 573.0,
            burn_rate: 1.0,
            heat_release: 1800.0,
            smoke_yield: 0.35,
            insulation: 0.3,
        });

        world.tiles.insert(
            (0, 0, 0),
            Tile::new(vec![0; CELL_COUNT], vec![0.0; CELL_COUNT * STATE_FIELDS]),
        );

        let mut materials = vec![0; CELL_COUNT];
        let mut state = vec![0.0; CELL_COUNT * STATE_FIELDS];
        let hot = idx(8, 8, 8);
        materials[hot] = 1;
        state[hot * STATE_FIELDS] = 1200.0;
        state[hot * STATE_FIELDS + 1] = 1.0;
        state[hot * STATE_FIELDS + 2] = 1.0;
        world.tiles.insert((1, 0, 0), Tile::new(materials, state));

        let mut mutations = vec![0; 32 * MUTATION_RECORD_INTS];
        let mut visuals = vec![0.0; 32 * VISUAL_RECORD_FLOATS];
        world.step(0.05, CELL_COUNT, 32, 32, &mut mutations, &mut visuals);

        let hot_tile = world.tiles.get(&(1, 0, 0)).unwrap();
        assert!(hot_tile.fuel[hot] < 1.0);
    }

    #[test]
    fn mutation_budget_stops_state_advancement() {
        let mut world = World::new(0, -64, 320, 1);
        world.materials.push(MaterialProps {
            fuel: 1.0,
            has_char_stage: true,
            has_ash_stage: true,
            ignition_temperature: 573.0,
            burn_rate: 1.0,
            heat_release: 1800.0,
            smoke_yield: 0.35,
            insulation: 0.3,
        });
        let mut materials = vec![0; CELL_COUNT];
        let mut state = ambient_state();
        let first = idx(0, 0, 0);
        let second = idx(1, 0, 0);
        for index in [first, second] {
            materials[index] = 1;
            state[index * STATE_FIELDS] = 1200.0;
            state[index * STATE_FIELDS + 1] = 1.0;
            state[index * STATE_FIELDS + 2] = 1.0;
            state[index * STATE_FIELDS + 5] = 0.44;
        }
        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mutations = vec![0; MUTATION_RECORD_INTS];
        let mut visuals = Vec::new();
        let (mutation_count, _visual_count) =
            world.step(0.05, CELL_COUNT, 1, 0, &mut mutations, &mut visuals);

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        assert_eq!(mutation_count, 1);
        assert!(tile.fuel[first] < 1.0);
        assert_eq!(tile.fuel[second], 1.0);
        assert_eq!(tile.char_progress[second], 0.44);
    }

    #[test]
    fn final_burnout_mutation_takes_priority_over_char_when_budget_is_tight() {
        let mut world = World::new(0, -64, 320, 1);
        world.materials.push(MaterialProps {
            fuel: 1.0,
            has_char_stage: true,
            has_ash_stage: true,
            ignition_temperature: 573.0,
            burn_rate: 1.0,
            heat_release: 1800.0,
            smoke_yield: 10.0,
            insulation: 0.3,
        });
        let mut materials = vec![0; CELL_COUNT];
        let mut state = ambient_state();
        let index = idx(0, 0, 0);
        materials[index] = 1;
        state[index * STATE_FIELDS] = 1200.0;
        state[index * STATE_FIELDS + 1] = 0.03;
        state[index * STATE_FIELDS + 2] = 1.0;
        state[index * STATE_FIELDS + 5] = 0.44;
        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mutations = vec![0; MUTATION_RECORD_INTS];
        let mut visuals = Vec::new();
        let (mutation_count, _visual_count) =
            world.step(0.05, CELL_COUNT, 1, 0, &mut mutations, &mut visuals);

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        assert_eq!(mutation_count, 1);
        assert_eq!(mutations[3], ACTION_SET_ASH);
        assert_eq!(tile.material_ids[index], 0);
    }

    #[test]
    fn low_fuel_material_can_char_before_burnout() {
        let mut world = World::new(0, -64, 320, 1);
        world.materials.push(MaterialProps {
            fuel: 0.28,
            has_char_stage: true,
            has_ash_stage: true,
            ignition_temperature: 470.0,
            burn_rate: 0.12,
            heat_release: 650.0,
            smoke_yield: 0.4,
            insulation: 0.25,
        });
        let mut materials = vec![0; CELL_COUNT];
        let mut state = ambient_state();
        let index = idx(0, 0, 0);
        materials[index] = 1;
        state[index * STATE_FIELDS] = 650.0;
        state[index * STATE_FIELDS + 1] = 0.28;
        state[index * STATE_FIELDS + 2] = 1.0;
        state[index * STATE_FIELDS + 5] = 0.44;
        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mutations = vec![0; MUTATION_RECORD_INTS];
        let mut visuals = Vec::new();
        let (mutation_count, _visual_count) =
            world.step(0.05, CELL_COUNT, 1, 0, &mut mutations, &mut visuals);

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        assert_eq!(mutation_count, 1);
        assert_eq!(mutations[3], ACTION_SET_CHAR);
        assert_eq!(tile.material_ids[index], 1);
        assert!(tile.fuel[index] > 0.02);
        assert_eq!(tile.char_progress[index], 1.0);
    }

    #[test]
    fn material_without_char_stage_does_not_emit_char_mutation() {
        let mut world = World::new(0, -64, 320, 1);
        world.materials.push(MaterialProps {
            fuel: 0.35,
            has_char_stage: false,
            has_ash_stage: false,
            ignition_temperature: 430.0,
            burn_rate: 0.12,
            heat_release: 900.0,
            smoke_yield: 0.12,
            insulation: 0.05,
        });
        let mut materials = vec![0; CELL_COUNT];
        let mut state = ambient_state();
        let index = idx(0, 0, 0);
        materials[index] = 1;
        state[index * STATE_FIELDS] = 650.0;
        state[index * STATE_FIELDS + 1] = 0.35;
        state[index * STATE_FIELDS + 2] = 1.0;
        state[index * STATE_FIELDS + 5] = 0.44;
        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mutations = vec![0; MUTATION_RECORD_INTS];
        let mut visuals = Vec::new();
        let (mutation_count, _visual_count) =
            world.step(0.05, CELL_COUNT, 1, 0, &mut mutations, &mut visuals);

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        assert_eq!(mutation_count, 0);
        assert!(tile.fuel[index] > 0.02);
        assert!(tile.char_progress[index] > 0.45);
    }

    #[test]
    fn explicit_ash_stage_burns_to_ash_even_with_low_smoke() {
        let mut world = World::new(0, -64, 320, 1);
        world.materials.push(MaterialProps {
            fuel: 0.18,
            has_char_stage: false,
            has_ash_stage: true,
            ignition_temperature: 500.0,
            burn_rate: 1.0,
            heat_release: 450.0,
            smoke_yield: 0.05,
            insulation: 0.25,
        });
        let mut materials = vec![0; CELL_COUNT];
        let mut state = ambient_state();
        let index = idx(0, 0, 0);
        materials[index] = 1;
        state[index * STATE_FIELDS] = 650.0;
        state[index * STATE_FIELDS + 1] = 0.021;
        state[index * STATE_FIELDS + 2] = 1.0;
        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mutations = vec![0; MUTATION_RECORD_INTS];
        let mut visuals = Vec::new();
        let (mutation_count, _visual_count) =
            world.step(0.05, CELL_COUNT, 1, 0, &mut mutations, &mut visuals);

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        assert_eq!(mutation_count, 1);
        assert_eq!(mutations[3], ACTION_SET_ASH);
        assert_eq!(tile.material_ids[index], 0);
    }

    #[test]
    fn spent_hot_cell_outputs_scorch_visual_without_flame() {
        let mut world = World::new(0, -64, 320, 1);
        let mut materials = vec![0; CELL_COUNT];
        let mut state = ambient_state();
        let index = idx(0, 0, 0);
        materials[index] = 0;
        state[index * STATE_FIELDS] = 620.0;
        state[index * STATE_FIELDS + 2] = 1.0;
        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mutations = vec![0; MUTATION_RECORD_INTS];
        let mut visuals = vec![0.0; VISUAL_RECORD_FLOATS];
        let (_mutation_count, visual_count) =
            world.step(0.05, CELL_COUNT, 1, 1, &mut mutations, &mut visuals);

        assert_eq!(visual_count, 1);
        assert_eq!(visuals[4], 0.0);
        assert!(visuals[7] > 0.0);
    }

    #[test]
    fn visual_budget_prefers_active_flame_front_over_old_smoke() {
        let mut world = World::new(0, -64, 320, 1);
        world.materials.push(MaterialProps {
            fuel: 1.0,
            has_char_stage: false,
            has_ash_stage: true,
            ignition_temperature: 430.0,
            burn_rate: 0.18,
            heat_release: 900.0,
            smoke_yield: 0.12,
            insulation: 0.05,
        });
        let mut materials = vec![0; CELL_COUNT];
        let mut state = ambient_state();
        let old_smoke = idx(0, 0, 0);
        let active_front = idx(15, 15, 15);
        state[old_smoke * STATE_FIELDS + 3] = 3.0;
        materials[active_front] = 1;
        state[active_front * STATE_FIELDS] = 650.0;
        state[active_front * STATE_FIELDS + 1] = 1.0;
        state[active_front * STATE_FIELDS + 2] = 1.0;
        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mutations = vec![0; 32 * MUTATION_RECORD_INTS];
        let mut visuals = vec![0.0; VISUAL_RECORD_FLOATS];
        let (_mutation_count, visual_count) =
            world.step(0.05, CELL_COUNT, 32, 1, &mut mutations, &mut visuals);

        assert_eq!(visual_count, 1);
        assert!(
            (visuals[0] - 15.5).abs() < 0.001 && (visuals[1] - 15.5).abs() < 0.001,
            "the single visual slot should show the active flame front, got ({}, {}, {})",
            visuals[0],
            visuals[1],
            visuals[2]
        );
        assert!(
            visuals[4] > 0.0,
            "active front visual should carry flame intensity"
        );
    }

    #[test]
    fn sustained_heat_source_does_not_cool_away() {
        let mut world = World::new(0, -64, 320, 1);
        let mut materials = vec![0; CELL_COUNT];
        let mut state = ambient_state();
        let source = idx(8, 8, 8);
        materials[source] = -1;
        state[source * STATE_FIELDS] = 1200.0;
        state[source * STATE_FIELDS + 2] = 1.0;
        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mutations = Vec::new();
        let mut visuals = Vec::new();
        world.step(1.0, CELL_COUNT, 32, 0, &mut mutations, &mut visuals);

        assert_eq!(world.query_temperature(8, 8, 8), 1200.0);
    }

    #[test]
    fn point_ignition_heats_only_one_cell() {
        let mut world = World::new(0, -64, 320, 1);
        world
            .tiles
            .insert((0, 0, 0), Tile::new(vec![0; CELL_COUNT], ambient_state()));

        world.ignite(8, 8, 8, 1200.0, 0);

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        let hot_cells = tile
            .temperature
            .iter()
            .filter(|temperature| **temperature > 400.0)
            .count();
        assert_eq!(hot_cells, 1);
        assert_eq!(world.query_temperature(8, 8, 8), 1200.0);
        assert_eq!(world.query_temperature(7, 8, 8), 293.15);
    }

    #[test]
    fn radius_ignition_is_spherical_not_cubic() {
        let mut world = World::new(0, -64, 320, 1);
        world
            .tiles
            .insert((0, 0, 0), Tile::new(vec![0; CELL_COUNT], ambient_state()));

        world.ignite(8, 8, 8, 1200.0, 1);

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        let hot_cells = tile
            .temperature
            .iter()
            .filter(|temperature| **temperature > 400.0)
            .count();
        assert_eq!(hot_cells, 7);
        assert_eq!(world.query_temperature(9, 9, 8), 293.15);
    }

    #[test]
    fn surface_heat_source_ignites_and_spreads_through_grass() {
        let mut world = World::new(0, -64, 320, 1);
        world.materials.push(MaterialProps {
            fuel: 0.35,
            has_char_stage: false,
            has_ash_stage: true,
            ignition_temperature: 430.0,
            burn_rate: 0.18,
            heat_release: 900.0,
            smoke_yield: 0.15,
            insulation: 0.05,
        });
        let mut materials = vec![0; CELL_COUNT];
        let mut state = ambient_state();
        let source = idx(8, 9, 8);
        let first_grass = idx(8, 8, 8);
        let second_grass = idx(9, 8, 8);
        materials[source] = -1;
        state[source * STATE_FIELDS] = 1200.0;
        state[source * STATE_FIELDS + 2] = 1.0;
        for grass in [first_grass, second_grass] {
            materials[grass] = 1;
            state[grass * STATE_FIELDS + 1] = 0.35;
            state[grass * STATE_FIELDS + 2] = 1.0;
        }
        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mutations = vec![0; 64 * MUTATION_RECORD_INTS];
        let mut visuals = vec![0.0; 64 * VISUAL_RECORD_FLOATS];
        for _ in 0..80 {
            world.step(0.05, CELL_COUNT, 64, 64, &mut mutations, &mut visuals);
        }

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        assert!(
            tile.fuel[first_grass] < 0.35 || tile.material_ids[first_grass] == 0,
            "grass under the source should burn"
        );
        assert!(
            tile.fuel[second_grass] < 0.35 || tile.material_ids[second_grass] == 0,
            "grass fire should propagate outward to adjacent fuel"
        );
    }

    #[test]
    fn radial_spread_ignites_diagonal_fuel_not_just_faces() {
        // The point of the diagonal radiant term is a CIRCULAR front: an in-plane diagonal fuel
        // neighbour must actually CATCH FIRE, not merely warm up. The only fuel besides a sustained
        // flame sits diagonally, and every face/edge cell between them is inert air that carries no
        // radiant heat — so ignition of the diagonal cell can only have come from diagonal radiant
        // preheat. A sustained source pins flame_intensity so the test is timing-stable.
        let mut world = World::new(0, -64, 320, 1);
        world.materials.push(MaterialProps {
            fuel: 1.0,
            has_char_stage: false,
            has_ash_stage: true,
            ignition_temperature: 460.0,
            burn_rate: 0.18,
            heat_release: 620.0,
            smoke_yield: 0.12,
            insulation: 0.05,
        });
        let mut materials = vec![0; CELL_COUNT];
        let mut state = ambient_state();
        let source = idx(8, 8, 8);
        let diagonal = idx(9, 8, 9);
        materials[source] = -1; // sustained flame
        state[source * STATE_FIELDS] = 1200.0;
        materials[diagonal] = 1;
        state[diagonal * STATE_FIELDS + 1] = 1.0; // full fuel (oxygen already 1.0 from ambient_state)

        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mutations = vec![0; 64 * MUTATION_RECORD_INTS];
        let mut visuals = vec![0.0; 64 * VISUAL_RECORD_FLOATS];
        for _ in 0..160 {
            world.step(0.0125, CELL_COUNT, 64, 64, &mut mutations, &mut visuals);
        }

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        assert!(
            tile.temperature[diagonal] >= 460.0,
            "diagonal fuel should reach ignition for a circular front — got {} K",
            tile.temperature[diagonal]
        );
        assert!(
            tile.fuel[diagonal] < 1.0,
            "ignited diagonal fuel should begin to burn — got {} fuel left",
            tile.fuel[diagonal]
        );
    }

    #[test]
    fn burnt_out_cell_drops_below_flame_threshold_immediately() {
        // When a cell exhausts its fuel it stops combusting, so it must shed the accumulated
        // heat of combustion at once rather than glowing flame-hot for tens of seconds (the
        // "just sits there burning" complaint). One step burns the tiny remaining fuel to ash and
        // the cell must land in the smoulder band, well under the client's ~420 K flame cutoff.
        let mut world = World::new(0, -64, 320, 1);
        world.materials.push(MaterialProps {
            fuel: 1.0,
            has_char_stage: false,
            has_ash_stage: true,
            ignition_temperature: 573.0,
            burn_rate: 1.0,
            heat_release: 1800.0,
            smoke_yield: 0.35,
            insulation: 0.3,
        });
        let mut materials = vec![0; CELL_COUNT];
        let mut state = ambient_state();
        let index = idx(0, 0, 0);
        materials[index] = 1;
        state[index * STATE_FIELDS] = 1200.0;
        state[index * STATE_FIELDS + 1] = 0.03; // about to burn out
        state[index * STATE_FIELDS + 2] = 1.0;
        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mutations = vec![0; MUTATION_RECORD_INTS];
        let mut visuals = Vec::new();
        let (mutation_count, _visual_count) =
            world.step(0.0125, CELL_COUNT, 1, 0, &mut mutations, &mut visuals);

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        assert_eq!(mutation_count, 1);
        assert_eq!(mutations[3], ACTION_SET_ASH);
        assert_eq!(tile.material_ids[index], 0);
        assert!(
            tile.temperature[index] < 420.0,
            "burnt-out cell should stop reading as flame-hot — got {} K",
            tile.temperature[index]
        );
    }

    #[test]
    fn extinguish_cools_native_cells() {
        let mut world = World::new(0, -64, 320, 1);
        let mut state = vec![0.0; CELL_COUNT * STATE_FIELDS];
        state[idx(3, 4, 5) * STATE_FIELDS] = 900.0;
        world
            .tiles
            .insert((2, 0, 0), Tile::new(vec![0; CELL_COUNT], state));

        let changed = world.extinguish(2 * 16 + 3, 4, 5, 2 * 16 + 3, 4, 5);

        assert_eq!(changed, 1);
        assert_eq!(world.query_temperature(2 * 16 + 3, 4, 5), 293.15);
    }

    #[test]
    fn unloaded_tiles_are_not_stepped() {
        let mut world = World::new(0, -64, 320, 1);
        world.materials.push(MaterialProps {
            fuel: 1.0,
            has_char_stage: true,
            has_ash_stage: true,
            ignition_temperature: 573.0,
            burn_rate: 1.0,
            heat_release: 1800.0,
            smoke_yield: 0.35,
            insulation: 0.3,
        });
        let mut materials = vec![0; CELL_COUNT];
        let mut state = ambient_state();
        let hot = idx(8, 8, 8);
        materials[hot] = 1;
        state[hot * STATE_FIELDS] = 1200.0;
        state[hot * STATE_FIELDS + 1] = 1.0;
        let mut tile = Tile::new(materials, state);
        tile.loaded = false;
        world.tiles.insert((0, 0, 0), tile);

        let mut mutations = vec![0; 32 * MUTATION_RECORD_INTS];
        let mut visuals = vec![0.0; 32 * VISUAL_RECORD_FLOATS];
        let (_mutation_count, visual_count) =
            world.step(0.05, CELL_COUNT, 32, 32, &mut mutations, &mut visuals);

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        assert_eq!(tile.fuel[hot], 1.0);
        assert_eq!(visual_count, 0);
    }

    #[test]
    fn conduction_crosses_section_boundaries_without_creating_heat() {
        let mut world = World::new(0, -64, 320, 1);
        let mut hot_state = ambient_state();
        hot_state[idx(15, 8, 8) * STATE_FIELDS] = 900.0;
        world
            .tiles
            .insert((0, 0, 0), Tile::new(vec![0; CELL_COUNT], hot_state));
        world
            .tiles
            .insert((1, 0, 0), Tile::new(vec![0; CELL_COUNT], ambient_state()));

        let before_total = world.query_temperature(15, 8, 8) + world.query_temperature(16, 8, 8);
        let mut mutations = Vec::new();
        let mut visuals = Vec::new();
        world.step(0.05, CELL_COUNT * 2, 32, 0, &mut mutations, &mut visuals);
        let after_hot = world.query_temperature(15, 8, 8);
        let after_cold = world.query_temperature(16, 8, 8);
        let after_total = after_hot + after_cold;

        assert!(after_hot < 900.0);
        assert!(after_cold > 293.15);
        assert!(after_total <= before_total);
    }

    #[test]
    fn water_blocks_heat_propagation_to_fuel_across_a_one_cell_gap() {
        let mut world = World::new(0, -64, 320, 1);
        world.materials.push(MaterialProps {
            fuel: 0.5,
            has_char_stage: false,
            has_ash_stage: true,
            ignition_temperature: 430.0,
            burn_rate: 0.18,
            heat_release: 900.0,
            smoke_yield: 0.15,
            insulation: 0.05,
        });
        let mut materials = vec![0; CELL_COUNT];
        let mut state = ambient_state();
        let source = idx(7, 8, 8);
        let water = idx(8, 8, 8);
        let downstream_fuel = idx(9, 8, 8);
        materials[source] = -1;
        state[source * STATE_FIELDS] = 1200.0;
        state[source * STATE_FIELDS + 2] = 1.0;
        materials[water] = WATER_MATERIAL_ID;
        materials[downstream_fuel] = 1;
        state[downstream_fuel * STATE_FIELDS + 1] = 0.5;
        state[downstream_fuel * STATE_FIELDS + 2] = 1.0;
        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mutations = vec![0; 64 * MUTATION_RECORD_INTS];
        let mut visuals = vec![0.0; 64 * VISUAL_RECORD_FLOATS];
        for _ in 0..200 {
            world.step(0.05, CELL_COUNT, 64, 64, &mut mutations, &mut visuals);
        }

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        // Water cell forced to ambient every tick.
        assert_eq!(tile.temperature[water], 293.15);
        // Fuel on the far side of the water never reaches its ignition temperature.
        assert!(
            tile.temperature[downstream_fuel] < 430.0,
            "fuel across water should not ignite — got {} K",
            tile.temperature[downstream_fuel]
        );
        // And therefore retains its full fuel value.
        assert!(
            (tile.fuel[downstream_fuel] - 0.5).abs() < 0.001,
            "fuel across water should not burn — got {} fuel left",
            tile.fuel[downstream_fuel]
        );
    }

    #[test]
    fn water_neighbor_extinguishes_a_burning_cell() {
        let mut world = World::new(0, -64, 320, 1);
        world.materials.push(MaterialProps {
            fuel: 1.0,
            has_char_stage: false,
            has_ash_stage: true,
            ignition_temperature: 430.0,
            burn_rate: 0.18,
            heat_release: 900.0,
            smoke_yield: 0.15,
            insulation: 0.05,
        });
        let mut materials = vec![0; CELL_COUNT];
        let mut state = ambient_state();
        let burning = idx(8, 8, 8);
        let water = idx(8, 9, 8); // directly above the flame
        materials[burning] = 1;
        state[burning * STATE_FIELDS] = 1200.0; // already ignited
        state[burning * STATE_FIELDS + 1] = 1.0; // full fuel
        state[burning * STATE_FIELDS + 2] = 1.0; // oxygen
        materials[water] = WATER_MATERIAL_ID;
        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mutations = vec![0; 64 * MUTATION_RECORD_INTS];
        let mut visuals = vec![0.0; 64 * VISUAL_RECORD_FLOATS];
        // The quench should drop the cell below its ignition temperature within ~1 second.
        for _ in 0..20 {
            world.step(0.05, CELL_COUNT, 64, 64, &mut mutations, &mut visuals);
        }

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        assert!(
            tile.temperature[burning] < 430.0,
            "a water neighbour should extinguish the burning cell — got {} K",
            tile.temperature[burning]
        );
    }

    #[test]
    fn conduction_does_not_heat_unloaded_neighbor_tiles() {
        let mut world = World::new(0, -64, 320, 1);
        let mut hot_state = ambient_state();
        hot_state[idx(15, 8, 8) * STATE_FIELDS] = 900.0;
        world
            .tiles
            .insert((0, 0, 0), Tile::new(vec![0; CELL_COUNT], hot_state));
        let mut neighbor = Tile::new(vec![0; CELL_COUNT], ambient_state());
        neighbor.loaded = false;
        world.tiles.insert((1, 0, 0), neighbor);

        let mut mutations = Vec::new();
        let mut visuals = Vec::new();
        world.step(0.05, CELL_COUNT * 2, 32, 0, &mut mutations, &mut visuals);

        assert_eq!(world.query_temperature(16, 8, 8), 293.15);
    }

    // Build one tile whose y=8 block layer is full grass with a sustained-flame block at the centre,
    // at horizontal resolution `s`, then return the block radius the ignition front has reached
    // (measured at each block's centre sub-cell ≥ ignition temperature) after `steps` game substeps.
    #[cfg(test)]
    fn grass_front_radius_blocks(s: i32, steps: usize) -> f32 {
        let mut world = World::new(0, -64, 320, 1);
        world.set_cells_per_axis(s);
        world.materials.push(MaterialProps {
            fuel: 1.0,
            has_char_stage: false,
            has_ash_stage: true,
            ignition_temperature: 460.0,
            burn_rate: 0.18,
            heat_release: 620.0,
            smoke_yield: 0.12,
            insulation: 0.05,
        });
        let dim = world.dim();
        let cell_count = world.cell_count();
        let mut materials = vec![0i32; cell_count];
        let mut state = vec![0.0f32; cell_count * STATE_FIELDS];
        for i in 0..cell_count {
            state[i * STATE_FIELDS] = 293.15; // ambient temperature
            state[i * STATE_FIELDS + 2] = 1.0; // oxygen
        }
        let gy = 8;
        for bz in 0..16 {
            for bx in 0..16 {
                for sz in 0..s {
                    for sx in 0..s {
                        let i = idx_dim(bx * s + sx, gy, bz * s + sz, dim);
                        materials[i] = 1;
                        state[i * STATE_FIELDS + 1] = 1.0; // fuel
                    }
                }
            }
        }
        // sustained flame across the whole centre block (8,8,8)
        for sz in 0..s {
            for sx in 0..s {
                let i = idx_dim(8 * s + sx, gy, 8 * s + sz, dim);
                materials[i] = -1;
                state[i * STATE_FIELDS] = 1200.0;
            }
        }
        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mut_buf = vec![0i32; 4096 * MUTATION_RECORD_INTS];
        let mut vis_buf: Vec<f32> = Vec::new();
        for _ in 0..steps {
            world.step(0.0125, usize::MAX, 4096, 0, &mut mut_buf, &mut vis_buf);
        }

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        let half = s / 2;
        let mut max_r = 0.0f32;
        for bz in 0..16 {
            for bx in 0..16 {
                let center = idx_dim(bx * s + half, gy, bz * s + half, dim);
                if tile.temperature[center] >= 460.0 {
                    let dx = (bx - 8) as f32;
                    let dz = (bz - 8) as f32;
                    max_r = max_r.max((dx * dx + dz * dz).sqrt());
                }
            }
        }
        max_r
    }

    #[test]
    fn subblock_spread_rate_matches_block_resolution() {
        // The horizontal radiant*S scaling must hold the WORLD spread-speed constant: the grass
        // front should reach about the same block radius after the same time at 1 vs 6 cells/block.
        // (Finer cells make the front rounder/smoother, not faster — the editor validates roundness.)
        let r1 = grass_front_radius_blocks(1, 200);
        let r6 = grass_front_radius_blocks(6, 200);
        assert!(r6 > 2.0, "fire must actually spread at S=6 (got r6={r6})");
        assert!(
            (r1 - r6).abs() <= 1.5,
            "world spread radius should match across resolutions: S1={r1} S6={r6}"
        );
    }

    #[test]
    fn subblock_query_temperature_returns_block_hottest_subcell() {
        let mut world = World::new(0, -64, 320, 1);
        world.set_cells_per_axis(4);
        let dim = world.dim();
        let cell_count = world.cell_count();
        let mut state = vec![0.0f32; cell_count * STATE_FIELDS];
        for i in 0..cell_count {
            state[i * STATE_FIELDS] = 293.15;
            state[i * STATE_FIELDS + 2] = 1.0;
        }
        // heat exactly one sub-cell of block (2,3,4); the block query should still report it.
        let hot = idx_dim(2 * 4 + 1, 3, 4 * 4 + 2, dim);
        state[hot * STATE_FIELDS] = 925.0;
        world
            .tiles
            .insert((0, 0, 0), Tile::new(vec![0; cell_count], state));

        assert_eq!(world.query_temperature(2, 3, 4), 925.0);
        assert_eq!(world.query_temperature(5, 3, 4), 293.15);
    }

    #[test]
    fn subblock_query_smoke_returns_block_smokiest_subcell() {
        let mut world = World::new(0, -64, 320, 1);
        world.set_cells_per_axis(4);
        let dim = world.dim();
        let cell_count = world.cell_count();
        let mut state = vec![0.0f32; cell_count * STATE_FIELDS];
        for i in 0..cell_count {
            state[i * STATE_FIELDS] = 293.15;
            state[i * STATE_FIELDS + 2] = 1.0;
        }
        let smoky = idx_dim(2 * 4 + 1, 3, 4 * 4 + 2, dim);
        state[smoky * STATE_FIELDS + 3] = 2.75;
        world
            .tiles
            .insert((0, 0, 0), Tile::new(vec![0; cell_count], state));

        assert_eq!(world.query_smoke(2, 3, 4), 2.75);
        assert_eq!(world.query_smoke(5, 3, 4), 0.0);
    }

    #[test]
    fn smoke_rises_into_open_air_cells() {
        let mut world = World::new(0, -64, 320, 1);
        let mut state = ambient_state();
        let source = idx(8, 8, 8);
        let above = idx(8, 9, 8);
        state[source * STATE_FIELDS + 3] = 2.0;
        world
            .tiles
            .insert((0, 0, 0), Tile::new(vec![0; CELL_COUNT], state));

        let mut mutations = vec![0; 32 * MUTATION_RECORD_INTS];
        let mut visuals = Vec::new();
        world.step(0.05, CELL_COUNT, 32, 0, &mut mutations, &mut visuals);

        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        assert!(
            tile.smoke[source] < 2.0,
            "source smoke should advect upward"
        );
        assert!(
            tile.smoke[above] > 0.05,
            "smoke should accumulate in the air cell above, got {}",
            tile.smoke[above]
        );
        assert!(world.query_smoke(8, 9, 8) > 0.05);
    }

    #[test]
    fn smoke_transport_crosses_loaded_section_boundaries() {
        let mut world = World::new(0, -64, 320, 1);
        let mut state = ambient_state();
        let source = idx(8, 15, 8);
        state[source * STATE_FIELDS + 3] = 2.0;
        world
            .tiles
            .insert((0, 0, 0), Tile::new(vec![0; CELL_COUNT], state));
        world
            .tiles
            .insert((0, 1, 0), Tile::new(vec![0; CELL_COUNT], ambient_state()));

        let mut mutations = vec![0; 32 * MUTATION_RECORD_INTS];
        let mut visuals = Vec::new();
        world.step(0.05, CELL_COUNT * 2, 32, 0, &mut mutations, &mut visuals);

        assert!(
            world.query_smoke(8, 16, 8) > 0.05,
            "smoke should rise into the loaded section above"
        );
    }

    #[test]
    fn subblock_block_ashes_only_after_all_subcells_burn() {
        // Whole-block-swallowed: a block of S*S sub-cells must emit exactly ONE ash mutation, at the
        // BLOCK coordinate, and only once the final sub-cell is consumed — not on the first.
        let mut world = World::new(0, -64, 320, 1);
        world.set_cells_per_axis(3); // 9 sub-cells per block
        world.materials.push(MaterialProps {
            fuel: 0.5,
            has_char_stage: false,
            has_ash_stage: true,
            ignition_temperature: 460.0,
            burn_rate: 1.0,
            heat_release: 120.0, // keep temp under 900 so no DAMAGE records confuse the count
            smoke_yield: 0.4,
            insulation: 0.05,
        });
        let s = 3;
        let dim = world.dim();
        let cell_count = world.cell_count();
        let mut materials = vec![0i32; cell_count];
        let mut state = vec![0.0f32; cell_count * STATE_FIELDS];
        for i in 0..cell_count {
            state[i * STATE_FIELDS] = 293.15;
            state[i * STATE_FIELDS + 2] = 1.0;
        }
        let (bx, by, bz) = (5, 5, 5);
        let mut subcells = Vec::new();
        for sz in 0..s {
            for sx in 0..s {
                let i = idx_dim(bx * s + sx, by, bz * s + sz, dim);
                subcells.push(i);
                materials[i] = 1;
                state[i * STATE_FIELDS] = 650.0; // ignited, below 900
                state[i * STATE_FIELDS + 1] = 0.03; // nearly spent: burns out on step 1
                state[i * STATE_FIELDS + 2] = 1.0;
            }
        }
        // one sub-cell lingers with much more fuel
        state[subcells[0] * STATE_FIELDS + 1] = 0.5;
        world.tiles.insert((0, 0, 0), Tile::new(materials, state));

        let mut mut_buf = vec![0i32; 64 * MUTATION_RECORD_INTS];
        let mut vis: Vec<f32> = Vec::new();

        // Step 1: 8 of 9 sub-cells burn out, but the block is incomplete -> no mutation yet.
        let (m1, _) = world.step(0.0125, usize::MAX, 64, 0, &mut mut_buf, &mut vis);
        assert_eq!(m1, 0, "no block mutation until every sub-cell is consumed");

        // Keep stepping; when the lingering sub-cell finally burns out, exactly one ASH appears.
        let mut total = 0usize;
        let mut last = [0i32; MUTATION_RECORD_INTS];
        for _ in 0..160 {
            let (m, _) = world.step(0.0125, usize::MAX, 64, 0, &mut mut_buf, &mut vis);
            if m > 0 {
                total += m;
                last.copy_from_slice(&mut_buf[0..MUTATION_RECORD_INTS]);
            }
        }
        assert_eq!(
            total, 1,
            "exactly one block-level ash mutation for the whole block"
        );
        assert_eq!(last[3], ACTION_SET_ASH);
        assert_eq!(
            (last[0], last[1], last[2]),
            (bx, by, bz),
            "mutation is at BLOCK coordinates, not sub-cell coordinates"
        );
    }

    #[test]
    fn subblock_save_load_roundtrip_preserves_temperature() {
        let mut world = World::new(0, -64, 320, 1);
        world.set_cells_per_axis(6);
        let dim = world.dim();
        let cell_count = world.cell_count();
        let mut state = vec![0.0f32; cell_count * STATE_FIELDS];
        for i in 0..cell_count {
            state[i * STATE_FIELDS] = 293.15;
            state[i * STATE_FIELDS + 2] = 1.0;
        }
        let hot = idx_dim(1 * 6 + 2, 2, 3 * 6 + 4, dim); // a sub-cell of block (1,2,3)
        state[hot * STATE_FIELDS] = 900.0;
        world
            .tiles
            .insert((4, 5, 6), Tile::new(vec![0; cell_count], state));

        let bytes = world.save();
        let mut loaded = World::new(0, -64, 320, 1);
        loaded.set_cells_per_axis(6);
        assert!(loaded.load(&bytes));
        assert_eq!(
            loaded.query_temperature(4 * 16 + 1, 5 * 16 + 2, 6 * 16 + 3),
            900.0
        );
    }

    #[test]
    fn subblock_transient_ignition_spreads_and_emits_visuals() {
        // Mimic the real in-game path: upload a grass section via BLOCK-level arrays (as Java does),
        // ignite ONE block transiently (no sustained source — the vanilla fire block is gone), and
        // confirm the fire both spreads to a neighbour block AND emits visuals at S=6.
        let mut world = World::new(0, -64, 320, 1);
        world.set_cells_per_axis(6);
        world.materials.push(MaterialProps {
            fuel: 0.22,
            has_char_stage: false,
            has_ash_stage: true,
            ignition_temperature: 460.0,
            burn_rate: 0.18,
            heat_release: 620.0,
            smoke_yield: 0.12,
            insulation: 0.05,
        });
        let mut block_mats = vec![0i32; BLOCKS_PER_SECTION];
        let mut block_state = vec![0.0f32; BLOCKS_PER_SECTION * STATE_FIELDS];
        for b in 0..BLOCKS_PER_SECTION {
            block_state[b * STATE_FIELDS] = 293.15;
            block_state[b * STATE_FIELDS + 2] = 1.0;
        }
        for bz in 0..16 {
            for bx in 0..16 {
                let b = idx_dim(bx, 8, bz, 16);
                block_mats[b] = 1;
                block_state[b * STATE_FIELDS + 1] = 0.22;
            }
        }
        world.set_tile_from_blocks((0, 0, 0), &block_mats, &block_state);
        world.ignite(8, 8, 8, 1200.0, 0); // transient, radius 0, no sustained source

        let dim = world.dim();
        let mut mut_buf = vec![0i32; 512 * MUTATION_RECORD_INTS];
        let mut vis_buf = vec![0.0f32; 2048 * VISUAL_RECORD_FLOATS];
        let mut max_visuals_seen = 0usize;
        for _ in 0..160 {
            let (_m, v) = world.step(0.0125, usize::MAX, 512, 2048, &mut mut_buf, &mut vis_buf);
            max_visuals_seen = max_visuals_seen.max(v);
        }
        let tile = world.tiles.get(&(0, 0, 0)).unwrap();
        let mut max_r = 0.0f32;
        for bx in 0..16 {
            for bz in 0..16 {
                if tile.temperature[idx_dim(bx * 6 + 3, 8, bz * 6 + 3, dim)] >= 460.0 {
                    let dx = (bx - 8) as f32;
                    let dz = (bz - 8) as f32;
                    max_r = max_r.max((dx * dx + dz * dz).sqrt());
                }
            }
        }
        assert!(
            max_r >= 1.0,
            "fire should spread at least 1 block at S=6 (got {max_r})"
        );
        assert!(
            max_visuals_seen > 0,
            "fire should emit visual records at S=6"
        );
    }

    #[test]
    fn fire_spreads_into_an_initially_cold_neighbour_tile() {
        // The active-flag optimisation skips cold tiles, so spread across a tile boundary relies on a
        // burning tile waking its cold neighbour via cross-tile heat. Source + grass in tile (0,0,0)
        // at the boundary must ignite the grass in the initially-inactive tile (1,0,0).
        let mut world = World::new(0, -64, 320, 1);
        world.materials.push(MaterialProps {
            fuel: 0.5,
            has_char_stage: false,
            has_ash_stage: true,
            ignition_temperature: 460.0,
            burn_rate: 0.18,
            heat_release: 900.0,
            smoke_yield: 0.15,
            insulation: 0.05,
        });
        // tile (0,0,0): grass row at y=8, sustained source at the +x boundary (x=15)
        let mut m0 = vec![0; CELL_COUNT];
        let mut s0 = ambient_state();
        for x in 8..16 {
            let i = idx(x, 8, 8);
            m0[i] = 1;
            s0[i * STATE_FIELDS + 1] = 0.5;
        }
        m0[idx(15, 8, 8)] = -1;
        s0[idx(15, 8, 8) * STATE_FIELDS] = 1200.0;
        world.tiles.insert((0, 0, 0), Tile::new(m0, s0));
        // tile (1,0,0): cold grass row at y=8 — starts INACTIVE
        let mut m1 = vec![0; CELL_COUNT];
        let mut s1 = ambient_state();
        for x in 0..8 {
            let i = idx(x, 8, 8);
            m1[i] = 1;
            s1[i * STATE_FIELDS + 1] = 0.5;
        }
        let cold = Tile::new(m1, s1);
        assert!(!cold.active, "the grass-only neighbour starts inactive");
        world.tiles.insert((1, 0, 0), cold);

        let mut mutations = vec![0; 64 * MUTATION_RECORD_INTS];
        let mut visuals = vec![0.0; 64 * VISUAL_RECORD_FLOATS];
        for _ in 0..200 {
            world.step(0.05, CELL_COUNT * 4, 64, 64, &mut mutations, &mut visuals);
        }

        let tile1 = world.tiles.get(&(1, 0, 0)).unwrap();
        assert!(
            tile1.fuel[idx(0, 8, 8)] < 0.5 || tile1.material_ids[idx(0, 8, 8)] == 0,
            "fire should cross the boundary and burn grass in the initially-cold tile"
        );
    }

    #[test]
    fn compact_expand_roundtrip_preserves_block_state() {
        let mut world = World::new(0, -64, 320, 1);
        world.set_cells_per_axis(6);
        let dim = world.dim();
        let cell_count = world.cell_count();
        let mut materials = vec![0i32; cell_count];
        let mut state = vec![0.0f32; cell_count * STATE_FIELDS];
        for i in 0..cell_count {
            state[i * STATE_FIELDS] = 293.15;
            state[i * STATE_FIELDS + 2] = 1.0;
        }
        for sz in 0..6 {
            for sx in 0..6 {
                let c = idx_dim(1 * 6 + sx, 2, 3 * 6 + sz, dim);
                materials[c] = 7;
                state[c * STATE_FIELDS + 1] = 0.5;
            }
        }
        let mut tile = Tile::new(materials, state);
        assert!(tile.is_live());
        tile.compact(6, dim);
        assert!(!tile.is_live(), "compacted tile is dormant");
        assert!(tile.temperature.is_empty());
        assert_eq!(tile.block_materials[idx_dim(1, 2, 3, 16)], 7);
        assert!((tile.block_state[idx_dim(1, 2, 3, 16) * STATE_FIELDS + 1] - 0.5).abs() < 1e-6);
        tile.expand(6, dim);
        assert!(tile.is_live(), "expanded tile is live again");
        let c = idx_dim(1 * 6 + 2, 2, 3 * 6 + 4, dim);
        assert_eq!(tile.material_ids[c], 7);
        assert!((tile.fuel[c] - 0.5).abs() < 1e-6);
        assert!(tile.block_materials.is_empty());
    }

    #[test]
    fn far_cold_tile_compacts_during_step() {
        let mut world = World::new(0, -64, 320, 1);
        world.set_cells_per_axis(6);
        let dim = world.dim();
        let cell_count = world.cell_count();
        let mut mats = vec![0i32; cell_count];
        let mut state = vec![0.0f32; cell_count * STATE_FIELDS];
        for i in 0..cell_count {
            state[i * STATE_FIELDS] = 293.15;
            state[i * STATE_FIELDS + 2] = 1.0;
        }
        let src = idx_dim(8 * 6, 8, 8 * 6, dim);
        mats[src] = -1;
        state[src * STATE_FIELDS] = 1200.0;
        world.tiles.insert((0, 0, 0), Tile::new(mats, state));
        let mut s2 = vec![0.0f32; cell_count * STATE_FIELDS];
        for i in 0..cell_count {
            s2[i * STATE_FIELDS] = 293.15;
            s2[i * STATE_FIELDS + 2] = 1.0;
        }
        world
            .tiles
            .insert((10, 0, 0), Tile::new(vec![0i32; cell_count], s2));
        assert!(world.tiles.get(&(10, 0, 0)).unwrap().is_live());

        let mut mb = vec![0; 64 * MUTATION_RECORD_INTS];
        let mut vb: Vec<f32> = Vec::new();
        world.step(0.0125, usize::MAX, 64, 0, &mut mb, &mut vb);

        assert!(
            !world.tiles.get(&(10, 0, 0)).unwrap().is_live(),
            "a cold tile far from the fire's ring should compact to dormant"
        );
        assert!(
            world.tiles.get(&(0, 0, 0)).unwrap().is_live(),
            "the active fire tile stays live"
        );
    }

    #[test]
    fn save_skips_cold_tiles() {
        // Only tiles with live fire are persisted; cold tiles (pristine fuel or burnt-and-cooled)
        // reconstruct from the world on reload. This is what keeps the S=6 snapshot under the 2 GB
        // JNI limit instead of serialising every uploaded grass section.
        let mut world = World::new(0, -64, 320, 1);
        world.set_cells_per_axis(6);
        let cell_count = world.cell_count();
        let dim = world.dim();
        world
            .tiles
            .insert((0, 0, 0), Tile::new(vec![0; cell_count], Vec::new())); // cold, ambient
        let mut state = vec![0.0f32; cell_count * STATE_FIELDS];
        for i in 0..cell_count {
            state[i * STATE_FIELDS] = 293.15;
            state[i * STATE_FIELDS + 2] = 1.0;
        }
        state[idx_dim(0, 0, 0, dim) * STATE_FIELDS] = 900.0; // one hot cell
        world
            .tiles
            .insert((5, 5, 5), Tile::new(vec![0; cell_count], state));

        let bytes = world.save();
        let mut loaded = World::new(0, -64, 320, 1);
        loaded.set_cells_per_axis(6);
        assert!(loaded.load(&bytes));
        assert_eq!(
            loaded.tiles.len(),
            1,
            "only the live-fire tile is persisted"
        );
        assert!(loaded.tiles.contains_key(&(5, 5, 5)));
        assert!(!loaded.tiles.contains_key(&(0, 0, 0)));
    }

    #[test]
    fn save_rejects_mismatched_resolution() {
        // An S=6 snapshot must not load into an S=1 world (or vice-versa): fail safe, never corrupt.
        let mut world = World::new(0, -64, 320, 1);
        world.set_cells_per_axis(6);
        let cell_count = world.cell_count();
        world
            .tiles
            .insert((0, 0, 0), Tile::new(vec![0; cell_count], Vec::new()));
        let bytes = world.save();
        let mut s1 = World::new(0, -64, 320, 1);
        assert!(!s1.load(&bytes));
        assert!(s1.tiles.is_empty());

        let mut plain = World::new(0, -64, 320, 1);
        plain
            .tiles
            .insert((0, 0, 0), Tile::new(vec![0; CELL_COUNT], Vec::new()));
        let s1_bytes = plain.save();
        let mut s6 = World::new(0, -64, 320, 1);
        s6.set_cells_per_axis(6);
        assert!(!s6.load(&s1_bytes));
    }
}
