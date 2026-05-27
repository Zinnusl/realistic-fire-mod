#![allow(unsafe_code)]

use std::collections::HashMap;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JFloatArray, JIntArray};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jfloat, jint, jlong};

const ABI_VERSION: jint = 4;
const CELL_COUNT: usize = 16 * 16 * 16;
const STATE_FIELDS: usize = 6;
const MUTATION_RECORD_INTS: usize = 6;
const VISUAL_RECORD_FLOATS: usize = 8;
const ACTION_SET_CHAR: i32 = 1;
const ACTION_SET_ASH: i32 = 2;
const ACTION_SET_AIR: i32 = 3;
const ACTION_DAMAGE_ENTITY_AREA: i32 = 4;

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

#[derive(Clone)]
struct Tile {
    loaded: bool,
    material_ids: Vec<i32>,
    temperature: Vec<f32>,
    fuel: Vec<f32>,
    oxygen: Vec<f32>,
    smoke: Vec<f32>,
    moisture: Vec<f32>,
    char_progress: Vec<f32>,
}

impl Tile {
    fn new(material_ids: Vec<i32>, initial_state: Vec<f32>) -> Self {
        let mut tile = Self {
            loaded: true,
            material_ids,
            temperature: vec![293.15; CELL_COUNT],
            fuel: vec![0.0; CELL_COUNT],
            oxygen: vec![1.0; CELL_COUNT],
            smoke: vec![0.0; CELL_COUNT],
            moisture: vec![0.0; CELL_COUNT],
            char_progress: vec![0.0; CELL_COUNT],
        };
        for index in 0..CELL_COUNT {
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
        tile
    }
}

struct World {
    _dimension_id: i32,
    _min_build_height: i32,
    _max_build_height: i32,
    _seed: i32,
    materials: Vec<MaterialProps>,
    tiles: HashMap<(i32, i32, i32), Tile>,
}

impl World {
    fn new(dimension_id: i32, min_build_height: i32, max_build_height: i32, seed: i32) -> Self {
        Self {
            _dimension_id: dimension_id,
            _min_build_height: min_build_height,
            _max_build_height: max_build_height,
            _seed: seed,
            materials: vec![MaterialProps::default()],
            tiles: HashMap::new(),
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
        let materials = self.materials.clone();
        let mut keys: Vec<((i32, i32, i32), f32)> = self
            .tiles
            .iter()
            .filter(|(_, tile)| tile.loaded)
            .map(|(key, tile)| (*key, tile_activity_score(tile, &materials)))
            .collect();
        keys.sort_by(|left, right| {
            right
                .1
                .total_cmp(&left.1)
                .then_with(|| left.0.cmp(&right.0))
        });
        let temperature_snapshots: HashMap<(i32, i32, i32), Vec<f32>> = self
            .tiles
            .iter()
            .filter(|(_, tile)| tile.loaded)
            .map(|(key, tile)| (*key, tile.temperature.clone()))
            .collect();
        let material_snapshots: HashMap<(i32, i32, i32), Vec<i32>> = self
            .tiles
            .iter()
            .filter(|(_, tile)| tile.loaded)
            .map(|(key, tile)| (*key, tile.material_ids.clone()))
            .collect();
        let mut external_heat_delta: HashMap<(i32, i32, i32), Vec<(usize, f32)>> = HashMap::new();
        for (key, _) in keys {
            if visited_cells >= max_cells || mutation_count >= max_mutations {
                break;
            }
            let Some(tile) = self.tiles.get_mut(&key) else {
                continue;
            };
            let mut heat_delta = vec![0.0f32; CELL_COUNT];
            'cells: for y in 0..16 {
                for z in 0..16 {
                    for x in 0..16 {
                        if visited_cells >= max_cells || mutation_count >= max_mutations {
                            break 'cells;
                        }
                        let index = idx(x, y, z);
                        visited_cells += 1;
                        let material_id = tile.material_ids[index];
                        let sustained_heat_source = material_id < 0;
                        let material = material_from(&materials, material_id);
                        let ambient = 293.15f32;
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
                            let rate = material.burn_rate
                                * dt
                                * oxygen.clamp(0.0, 1.0)
                                * (1.0
                                    + (temperature - material.ignition_temperature).max(0.0)
                                        / material.ignition_temperature.max(1.0));
                            let burn = fuel.min(rate.max(0.0));
                            fuel -= burn;
                            oxygen = (oxygen - burn * 0.45).max(0.0);
                            smoke = (smoke + burn * material.smoke_yield).min(6.0);
                            temperature += burn * material.heat_release;
                            char_progress += burn / material.fuel.max(0.001);

                            if fuel <= 0.02 && mutation_count < max_mutations {
                                let action = if material.has_ash_stage || smoke > 0.2 {
                                    ACTION_SET_ASH
                                } else {
                                    ACTION_SET_AIR
                                };
                                push_mutation(
                                    mutations,
                                    mutation_count,
                                    key,
                                    x,
                                    y,
                                    z,
                                    action,
                                    material_id,
                                    0,
                                );
                                mutation_count += 1;
                                tile.material_ids[index] = 0;
                                char_progress = 1.0;
                            } else if char_progress >= 0.45
                                && char_progress < 1.0
                                && material.has_char_stage
                                && mutation_count < max_mutations
                            {
                                push_mutation(
                                    mutations,
                                    mutation_count,
                                    key,
                                    x,
                                    y,
                                    z,
                                    ACTION_SET_CHAR,
                                    material_id,
                                    0,
                                );
                                mutation_count += 1;
                                char_progress = 1.0;
                            }
                            if temperature > 900.0 && mutation_count < max_mutations {
                                push_mutation(
                                    mutations,
                                    mutation_count,
                                    key,
                                    x,
                                    y,
                                    z,
                                    ACTION_DAMAGE_ENTITY_AREA,
                                    material_id,
                                    ((temperature - 800.0) / 4.0) as i32,
                                );
                                mutation_count += 1;
                            }
                        }

                        let cooling =
                            (temperature - ambient) * (0.012 + material.insulation * 0.008) * dt;
                        temperature -= cooling;
                        if sustained_heat_source {
                            temperature = temperature.max(1200.0);
                        }
                        oxygen += (1.0 - oxygen) * 0.09 * dt;
                        smoke *= (1.0 - 0.025 * dt).clamp(0.0, 1.0);

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
                            );
                        }

                        tile.temperature[index] = temperature.max(ambient);
                        tile.fuel[index] = fuel.max(0.0);
                        tile.oxygen[index] = oxygen.clamp(0.0, 1.0);
                        tile.smoke[index] = smoke.max(0.0);
                        tile.moisture[index] = moisture.max(0.0);
                        tile.char_progress[index] = char_progress;

                        if visual_count < max_visuals && (temperature > 360.0 || smoke > 0.05) {
                            push_visual(
                                visuals,
                                visual_count,
                                key,
                                x,
                                y,
                                z,
                                temperature,
                                flame_intensity,
                                smoke,
                                oxygen,
                            );
                            visual_count += 1;
                        }
                    }
                }
            }
            for (index, delta) in heat_delta.into_iter().enumerate() {
                tile.temperature[index] = (tile.temperature[index] + delta).max(293.15);
                if tile.material_ids[index] < 0 {
                    tile.temperature[index] = tile.temperature[index].max(1200.0);
                }
            }
        }
        for (key, deltas) in external_heat_delta {
            let Some(tile) = self.tiles.get_mut(&key) else {
                continue;
            };
            for (index, delta) in deltas {
                tile.temperature[index] = (tile.temperature[index] + delta).max(293.15);
            }
        }
        (mutation_count, visual_count)
    }

    fn ignite(&mut self, x: i32, y: i32, z: i32, temperature: f32, radius: i32) {
        let radius = radius.max(0);
        let radius_sq = radius * radius;
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
                    let lx = bx & 15;
                    let ly = by & 15;
                    let lz = bz & 15;
                    let index = idx(lx, ly, lz);
                    let falloff = if radius == 0 {
                        1.0
                    } else {
                        1.0 - (distance_sq as f32).sqrt() / (radius as f32 + 1.0)
                    };
                    let target_temperature = temperature * (0.65 + falloff.clamp(0.0, 1.0) * 0.35);
                    tile.temperature[index] = tile.temperature[index].max(target_temperature);
                    tile.oxygen[index] = tile.oxygen[index].max(0.75);
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
            let local_min_x = min_x.max(base_x) - base_x;
            let local_min_y = min_y.max(base_y) - base_y;
            let local_min_z = min_z.max(base_z) - base_z;
            let local_max_x = max_x.min(base_x + 15) - base_x;
            let local_max_y = max_y.min(base_y + 15) - base_y;
            let local_max_z = max_z.min(base_z + 15) - base_z;
            for y in local_min_y..=local_max_y {
                for z in local_min_z..=local_max_z {
                    for x in local_min_x..=local_max_x {
                        let index = idx(x, y, z);
                        if tile.temperature[index] > 294.0 || tile.smoke[index] > 0.01 {
                            changed += 1;
                        }
                        tile.temperature[index] = 293.15;
                        tile.smoke[index] = 0.0;
                        tile.oxygen[index] = 1.0;
                    }
                }
            }
        }
        changed
    }

    fn query_temperature(&self, x: i32, y: i32, z: i32) -> f32 {
        let key = (x >> 4, y >> 4, z >> 4);
        let Some(tile) = self.tiles.get(&key) else {
            return 293.15;
        };
        tile.temperature[idx(x & 15, y & 15, z & 15)]
    }

    fn save(&self) -> Vec<u8> {
        let mut out = Vec::new();
        write_u32(&mut out, 2);
        write_u32(&mut out, self.tiles.len() as u32);
        for (key, tile) in &self.tiles {
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
        if version != 1 && version != 2 {
            return false;
        }
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
            let mut material_ids = Vec::with_capacity(CELL_COUNT);
            for _ in 0..CELL_COUNT {
                let Some(value) = read_i32(bytes, &mut cursor) else {
                    return false;
                };
                material_ids.push(value);
            }
            let mut initial_state = vec![0.0f32; CELL_COUNT * STATE_FIELDS];
            for field in 0..STATE_FIELDS {
                for index in 0..CELL_COUNT {
                    let Some(value) = read_f32(bytes, &mut cursor) else {
                        return false;
                    };
                    initial_state[index * STATE_FIELDS + field] = value;
                }
            }
            let mut tile = Tile::new(material_ids, initial_state);
            tile.loaded = loaded;
            tiles.insert((sx, sy, sz), tile);
        }
        if cursor != bytes.len() {
            return false;
        }
        self.tiles = tiles;
        true
    }
}

fn idx(x: i32, y: i32, z: i32) -> usize {
    ((y as usize) << 8) | ((z as usize) << 4) | x as usize
}

fn material_from(materials: &[MaterialProps], id: i32) -> MaterialProps {
    if id <= 0 {
        return MaterialProps::default();
    }
    materials.get(id as usize).copied().unwrap_or_default()
}

fn tile_activity_score(tile: &Tile, materials: &[MaterialProps]) -> f32 {
    let mut score = 0.0f32;
    for index in 0..CELL_COUNT {
        let heat = (tile.temperature[index] - 330.0).max(0.0);
        let smoke = tile.smoke[index].max(0.0);
        let material = material_from(materials, tile.material_ids[index]);
        if heat > 0.0 {
            score += heat * 0.01 + smoke * 0.25;
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
) {
    let transfer = 0.018 * dt * (1.0 - insulation.clamp(0.0, 0.95));
    for (nx, ny, nz, conductive_bias, exposure) in [
        (x + 1, y, z, 1.0, 1.0),
        (x - 1, y, z, 1.0, 1.0),
        (x, y, z + 1, 1.0, 1.0),
        (x, y, z - 1, 1.0, 1.0),
        (x, y + 1, z, 2.4, 1.35),
        (x, y - 1, z, 0.45, 1.8),
    ] {
        let (neighbor_key, lx, ly, lz, neighbor_temperature, neighbor_material_id) =
            if (0..16).contains(&nx) && (0..16).contains(&ny) && (0..16).contains(&nz) {
                let neighbor = idx(nx, ny, nz);
                (
                    key,
                    nx,
                    ny,
                    nz,
                    tile.temperature[neighbor],
                    tile.material_ids[neighbor],
                )
            } else {
                let neighbor_block_x = key.0 * 16 + nx;
                let neighbor_block_y = key.1 * 16 + ny;
                let neighbor_block_z = key.2 * 16 + nz;
                let neighbor_key = (
                    neighbor_block_x >> 4,
                    neighbor_block_y >> 4,
                    neighbor_block_z >> 4,
                );
                let lx = neighbor_block_x & 15;
                let ly = neighbor_block_y & 15;
                let lz = neighbor_block_z & 15;
                let Some(snapshot) = temperature_snapshots.get(&neighbor_key) else {
                    continue;
                };
                let Some(materials_snapshot) = material_snapshots.get(&neighbor_key) else {
                    continue;
                };
                let neighbor = idx(lx, ly, lz);
                (
                    neighbor_key,
                    lx,
                    ly,
                    lz,
                    snapshot[neighbor],
                    materials_snapshot[neighbor],
                )
            };
        let neighbor = idx(lx, ly, lz);
        let conductive_delta =
            (temperature - neighbor_temperature) * transfer * conductive_bias * 0.35;
        let mut neighbor_delta = 0.0f32;
        if conductive_delta > 0.0 {
            heat_delta[index] -= conductive_delta;
            neighbor_delta += conductive_delta;
        }
        if flame_intensity > 0.0 && neighbor_material_id > 0 {
            let neighbor_material = material_from(materials, neighbor_material_id);
            let preheat_factor =
                if neighbor_temperature <= neighbor_material.ignition_temperature + 250.0 {
                    1.0
                } else {
                    0.35
                };
            let radiant_delta = flame_intensity
                * dt
                * 180.0
                * exposure
                * preheat_factor
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
) {
    let base = record * VISUAL_RECORD_FLOATS;
    if base + 7 >= out.len() {
        return;
    }
    out[base] = (key.0 * 16 + x) as f32 + 0.5;
    out[base + 1] = (key.1 * 16 + y) as f32 + 0.5;
    out[base + 2] = (key.2 * 16 + z) as f32 + 0.5;
    out[base + 3] = temperature;
    out[base + 4] = flame;
    out[base + 5] = smoke;
    out[base + 6] = oxygen;
    out[base + 7] = ((temperature - 293.15) / 1000.0).clamp(0.0, 4.0);
}

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
    let mut material_ids = vec![0i32; CELL_COUNT];
    if env
        .get_int_array_region(&material_ids_array, 0, &mut material_ids)
        .is_err()
    {
        return;
    }
    let mut initial_state = vec![0.0f32; CELL_COUNT * STATE_FIELDS];
    if env
        .get_float_array_region(&initial_state_array, 0, &mut initial_state)
        .is_err()
    {
        return;
    }
    world.tiles.insert(
        (section_x, section_y, section_z),
        Tile::new(material_ids, initial_state),
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
    let key = (x >> 4, y >> 4, z >> 4);
    let Some(tile) = world.tiles.get_mut(&key) else {
        return;
    };
    let mut initial_state = vec![0.0f32; STATE_FIELDS];
    if env
        .get_float_array_region(&initial_state_array, 0, &mut initial_state)
        .is_err()
    {
        return;
    }
    let index = idx(x & 15, y & 15, z & 15);
    tile.material_ids[index] = material_id;
    tile.temperature[index] = initial_state[0];
    tile.fuel[index] = initial_state[1];
    tile.oxygen[index] = initial_state[2];
    tile.smoke[index] = initial_state[3];
    tile.moisture[index] = initial_state[4];
    tile.char_progress[index] = initial_state[5];
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
}
