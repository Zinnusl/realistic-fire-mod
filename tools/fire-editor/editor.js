'use strict';
// Realistic Fire parameter editor.
// Loads the solver core (compiled to WebAssembly) and drives a single ground layer so the burn
// front's SHAPE and LONGEVITY can be tuned live. Every parameter maps straight back to the mod:
//   solver.*   -> SolverConfig::default() in src/main/rust/.../lib.rs
//   material.* -> a material JSON under data/realisticfire/realistic_fire/materials/
// The simulation runs at the game's true sub-tick (dt = 1/20/4 s, 4 substeps per tick).

const DT = 1 / 20 / 4;          // one solver sub-step, identical to FireSimulationManager
const GROUND_Y = 8;
const INSTANCE_DATAPACK = 'realisticfire-editor-tuning';
const GRASS_TARGETS = [
  { block: 'minecraft:short_grass' },
  { block: 'minecraft:tall_grass' },
  { block: 'minecraft:fern' },
  { block: 'minecraft:large_fern' },
  { block: 'minecraft:dead_bush' },
  { tag: 'minecraft:flowers' },
  { tag: 'minecraft:saplings' },
];

// --- parameter specs (single source of truth for the UI + export) ---------------------------
// Defaults reproduce the values currently shipped in the mod.
const SOLVER_PARAMS = [
  { key: 'radiant_strength',    label: 'Radiant strength',        min: 80,  max: 320,  step: 1,     val: 180,
    help: 'Drives ignition spread. Higher → faster, hotter front.' },
  { key: 'diagonal_exposure',   label: 'Diagonal exposure',       min: 0,   max: 1.4,  step: 0.001, val: 0.707,
    help: 'Diagonal vs axial radiant. Low → diamond / “+”, ~0.7 → round, high → square.' },
  { key: 'up_bias',             label: 'Up bias (conduction)',    min: 0,   max: 5,    step: 0.05,  val: 2.4 },
  { key: 'up_exposure',         label: 'Up exposure (radiant)',   min: 0,   max: 3,    step: 0.01,  val: 1.35 },
  { key: 'down_bias',           label: 'Down bias (conduction)',  min: 0,   max: 3,    step: 0.05,  val: 0.45 },
  { key: 'down_exposure',       label: 'Down exposure (radiant)', min: 0,   max: 3,    step: 0.01,  val: 1.8 },
  { key: 'conduction_transfer', label: 'Conduction transfer',     min: 0,   max: 0.1,  step: 0.001, val: 0.018 },
  { key: 'cooling_base',        label: 'Cooling base',            min: 0,   max: 0.1,  step: 0.001, val: 0.020,
    help: 'Newtonian cooling. Higher → tighter self-limiting radius, fire dies sooner.' },
  { key: 'cooling_insulation',  label: 'Cooling × insulation',    min: 0,   max: 0.05, step: 0.001, val: 0.008 },
  { key: 'burnout_temp_offset', label: 'Burnout temp offset (K)', min: 0,   max: 600,  step: 5,     val: 110,
    help: 'Heat kept after fuel is spent. Low → flames vanish instantly; high → they linger / “sit there”.' },
];

const MATERIAL_PARAMS = [
  { key: 'fuel',                  label: 'Fuel',             min: 0,   max: 1.5, step: 0.01, val: 0.22, reset: true,
    help: 'Total combustible mass. Per-cell value — takes effect on reset.' },
  { key: 'ignition_temperature_k', label: 'Ignition temp (K)', min: 300, max: 900, step: 5,  val: 460 },
  { key: 'burn_rate',             label: 'Burn rate',        min: 0.01, max: 1.0, step: 0.005, val: 0.18,
    help: 'Fuel consumed per second. Lower → longer burn window → rounder front, longer-lived fire.' },
  { key: 'heat_release',          label: 'Heat release',     min: 100, max: 2500, step: 10,  val: 620 },
  { key: 'smoke_yield',           label: 'Smoke yield',      min: 0,   max: 2,   step: 0.01, val: 0.12 },
  { key: 'moisture',              label: 'Moisture',         min: 0,   max: 0.5, step: 0.01, val: 0.04, reset: true,
    help: 'Per-cell dampness — takes effect on reset.' },
  { key: 'insulation',            label: 'Insulation',       min: 0,   max: 0.6, step: 0.01, val: 0.05 },
];

// material boolean stages
const MATERIAL_FLAGS = [
  { key: 'has_ash_stage',  label: 'Has ash stage',  val: true },
  { key: 'has_char_stage', label: 'Has char stage', val: false },
];

const SCENARIO = {
  half: 16,            // field radius in BLOCKS (patch is (2*half+1)^2 blocks)
  ignite_radius: 1,    // one-shot ignition radius, in blocks
  mode: 'point',       // 'point' = brief ignition that fuel must carry, 'source' = sustained flame
  water: 'none',       // 'none' | 'line' | 'ring'
  subdiv: 1,           // S: cells per block per axis. >1 = sub-block resolution (smoother front).
  seed: 1,             // deterministic spread-variation seed for the preview.
  smooth: false,       // bilinear upscale of the render (separates visualisation from physics)
};

const MAX_CELL_HALF = 90;            // must match MAX_HALF in the WASM bridge
const subdiv = () => Math.max(1, Math.round(SCENARIO.subdiv));
// Cell-grid radius actually handed to the solver (clamped to the WASM field limit).
const cellHalf = () => Math.min(MAX_CELL_HALF, SCENARIO.half * subdiv());

// --- state ----------------------------------------------------------------------------------
const state = { solver: {}, material: {}, flags: {} };
for (const p of SOLVER_PARAMS) state.solver[p.key] = p.val;
for (const p of MATERIAL_PARAMS) state.material[p.key] = p.val;
for (const f of MATERIAL_FLAGS) state.flags[f.key] = f.val;

let wasm = null;          // exports
let mem = null;           // WebAssembly.Memory
let playing = true;
let simSeconds = 0;
let dim = cellHalf() * 2 + 1;
let textures = { ash: null, ember: null };

const $ = (sel) => document.querySelector(sel);

function showError(msg) {
  let el = document.getElementById('err');
  if (!el) { el = document.createElement('div'); el.id = 'err'; document.body.appendChild(el); }
  el.textContent = 'Error: ' + msg;
}

async function loadTexture(path) {
  const image = new Image();
  image.decoding = 'async';
  image.src = path;
  await image.decode();
  const canvas = document.createElement('canvas');
  canvas.width = image.naturalWidth;
  canvas.height = image.naturalHeight;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  ctx.drawImage(image, 0, 0);
  return {
    width: canvas.width,
    height: canvas.height,
    data: ctx.getImageData(0, 0, canvas.width, canvas.height).data,
  };
}

function mod(n, d) {
  return ((n % d) + d) % d;
}

function sampleTexture(texture, worldCellX, worldCellZ, cellsPerBlock) {
  if (!texture) return null;
  const sx = mod(worldCellX, cellsPerBlock);
  const sz = mod(worldCellZ, cellsPerBlock);
  const u = Math.min(texture.width - 1, Math.floor((sx + 0.5) * texture.width / cellsPerBlock));
  const v = Math.min(texture.height - 1, Math.floor((sz + 0.5) * texture.height / cellsPerBlock));
  const i = (v * texture.width + u) * 4;
  return [
    texture.data[i],
    texture.data[i + 1],
    texture.data[i + 2],
    texture.data[i + 3],
  ];
}

function blendRGB(base, overlay, alpha) {
  return [
    base[0] * (1 - alpha) + overlay[0] * alpha,
    base[1] * (1 - alpha) + overlay[1] * alpha,
    base[2] * (1 - alpha) + overlay[2] * alpha,
  ];
}

// --- persistence --------------------------------------------------------------------------
const STORE_KEY = 'realisticfire-editor-v1';
function saveState() {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify({
      solver: state.solver, material: state.material, flags: state.flags, scenario: SCENARIO,
    }));
  } catch (_) { /* ignore */ }
}
function loadState() {
  try {
    const raw = localStorage.getItem(STORE_KEY);
    if (!raw) return;
    const s = JSON.parse(raw);
    if (s.solver) Object.assign(state.solver, s.solver);
    if (s.material) Object.assign(state.material, s.material);
    if (s.flags) Object.assign(state.flags, s.flags);
    if (s.scenario) Object.assign(SCENARIO, s.scenario);
  } catch (_) { /* ignore */ }
}

// --- UI construction ----------------------------------------------------------------------
function fmt(v, step) {
  if (step >= 1) return String(Math.round(v));
  const dp = step >= 0.1 ? 2 : 3;
  return v.toFixed(dp);
}

function buildSlider(spec, store, onChange) {
  const row = document.createElement('div');
  row.className = 'row';
  const label = document.createElement('label');
  const name = document.createElement('span'); name.className = 'name'; name.textContent = spec.label;
  const val = document.createElement('span'); val.className = 'val';
  label.append(name, val);
  const input = document.createElement('input');
  input.type = 'range';
  input.min = spec.min; input.max = spec.max; input.step = spec.step;
  input.value = store[spec.key];
  val.textContent = fmt(store[spec.key], spec.step);
  input.addEventListener('input', () => {
    store[spec.key] = parseFloat(input.value);
    val.textContent = fmt(store[spec.key], spec.step);
    onChange(spec);
    saveState();
  });
  row.append(label, input);
  if (spec.help) {
    const h = document.createElement('div'); h.className = 'help'; h.textContent = spec.help;
    row.append(h);
  }
  return row;
}

function buildCheck(spec, store, onChange) {
  const row = document.createElement('div');
  row.className = 'row check';
  const label = document.createElement('label');
  const input = document.createElement('input');
  input.type = 'checkbox'; input.checked = !!store[spec.key];
  const name = document.createElement('span'); name.className = 'name'; name.textContent = spec.label;
  label.append(input, name);
  input.addEventListener('change', () => { store[spec.key] = input.checked; onChange(spec); saveState(); });
  row.append(label);
  return row;
}

function buildSelect(labelText, value, options, onChange) {
  const row = document.createElement('div');
  row.className = 'row';
  const label = document.createElement('label');
  const name = document.createElement('span'); name.className = 'name'; name.textContent = labelText;
  label.append(name);
  const sel = document.createElement('select');
  for (const o of options) {
    const opt = document.createElement('option');
    opt.value = o.value; opt.textContent = o.label;
    if (o.value === value) opt.selected = true;
    sel.append(opt);
  }
  sel.addEventListener('change', () => onChange(sel.value));
  row.append(label, sel);
  return row;
}

function buildUI() {
  const solverRows = $('#solver-rows');
  for (const p of SOLVER_PARAMS) solverRows.append(buildSlider(p, state.solver, () => { /* live, read each frame */ }));

  const matRows = $('#material-rows');
  for (const p of MATERIAL_PARAMS) {
    matRows.append(buildSlider(p, state.material, (spec) => { if (spec.reset) reset(); }));
  }
  for (const f of MATERIAL_FLAGS) matRows.append(buildCheck(f, state.flags, () => {}));

  const scen = $('#scenario-rows');
  scen.append(buildSlider({ key: 'subdiv', label: 'Resolution (cells / block)', min: 1, max: 6, step: 1,
    help: 'S = sub-cells per block per axis. 1 = blocks (chunky). Higher matches the in-game sub-block solver.' },
    SCENARIO, () => reset()));
  scen.append(buildSlider({ key: 'seed', label: 'Variation seed', min: 1, max: 9999, step: 1,
    help: 'Deterministic roughness pattern. In-game this comes from the world seed.' },
    SCENARIO, () => reset()));
  scen.append(buildCheck({ key: 'smooth', label: 'Smooth render (bilinear)' }, SCENARIO, () => {}));
  scen.append(buildSelect('Ignition', SCENARIO.mode, [
    { value: 'point', label: 'Point (fuel carries it)' },
    { value: 'source', label: 'Sustained source' },
  ], (v) => { SCENARIO.mode = v; saveState(); reset(); }));
  scen.append(buildSlider({ key: 'half', label: 'Block radius', min: 6, max: 40, step: 1,
    help: 'Field half-width in blocks. Effective area is clamped so cells/side ≤ ' + (MAX_CELL_HALF * 2 + 1) + '.' },
    SCENARIO, () => reset()));
  scen.append(buildSlider({ key: 'ignite_radius', label: 'Ignition radius (blocks)', min: 0, max: 4, step: 1 },
    SCENARIO, () => reset()));
  scen.append(buildSelect('Water firebreak', SCENARIO.water, [
    { value: 'none', label: 'None' },
    { value: 'line', label: 'Vertical line (+4 blocks)' },
    { value: 'ring', label: 'Ring (r = radius·0.6)' },
  ], (v) => { SCENARIO.water = v; saveState(); reset(); }));
}

// --- simulation control -------------------------------------------------------------------
function pushConfig() {
  const s = state.solver;
  wasm.rf_set_config(
    s.radiant_strength, s.diagonal_exposure, s.up_bias, s.up_exposure,
    s.down_bias, s.down_exposure, s.conduction_transfer, s.cooling_base,
    s.cooling_insulation, s.burnout_temp_offset);
}
function pushMaterial() {
  const m = state.material;
  wasm.rf_set_material(1, m.fuel, state.flags.has_char_stage ? 1 : 0, state.flags.has_ash_stage ? 1 : 0,
    m.ignition_temperature_k, m.burn_rate, m.heat_release, m.smoke_yield, m.insulation);
}

// Water is painted in CELL coordinates (1 block = S cells wide), so the firebreak keeps the same
// world thickness/size regardless of resolution.
function paintWater() {
  const S = subdiv();
  const ch = cellHalf();
  if (SCENARIO.water === 'line') {
    const wx0 = 4 * S;                       // ~4 blocks east, one block thick
    for (let dx = 0; dx < S; dx++)
      for (let z = -ch; z <= ch; z++) wasm.rf_set_cell_material(wx0 + dx, GROUND_Y, z, -2);
  } else if (SCENARIO.water === 'ring') {
    const r = Math.max(3 * S, Math.round(ch * 0.6));
    const dA = 1.2 / r;                       // step fine enough to leave no gaps
    for (let a = 0; a < 2 * Math.PI; a += dA) {
      const x = Math.round(Math.cos(a) * r);
      const z = Math.round(Math.sin(a) * r);
      wasm.rf_set_cell_material(x, GROUND_Y, z, -2);
    }
  }
}

function reset() {
  if (!wasm) return;
  const S = subdiv();
  const ch = cellHalf();
  dim = ch * 2 + 1;
  wasm.rf_init(Math.max(1, Math.round(SCENARIO.seed)));
  wasm.rf_set_subblock_resolution(S);
  pushConfig();
  pushMaterial();
  wasm.rf_setup_ground(ch, GROUND_Y, 1, state.material.fuel, state.material.moisture);
  paintWater();
  if (SCENARIO.mode === 'source') {
    // a ~1-block disk of sustained flame so the source is the same world size at any resolution
    const r = Math.max(0, S - 1);
    for (let dz = -r; dz <= r; dz++)
      for (let dx = -r; dx <= r; dx++)
        if (dx * dx + dz * dz <= r * r) wasm.rf_set_cell_material(dx, GROUND_Y, dz, -1);
  } else {
    wasm.rf_ignite(0, GROUND_Y, 0, 1200.0, SCENARIO.ignite_radius * S);
  }
  simSeconds = 0;
}

// --- rendering ----------------------------------------------------------------------------
const canvas = $('#view');
const cctx = canvas.getContext('2d');
let buf = null;       // small dim×dim canvas
let bctx = null;
let imgData = null;

function ensureBuffer() {
  if (buf && buf.width === dim) return;
  buf = document.createElement('canvas');
  buf.width = dim; buf.height = dim;
  bctx = buf.getContext('2d');
  imgData = bctx.createImageData(dim, dim);
}

// fire palette: a in [0,1] -> [r,g,b]
function fireRGB(a) {
  // black -> deep red -> orange -> yellow -> white
  const r = Math.min(255, a * 3.2 * 255);
  const g = Math.min(255, Math.max(0, (a - 0.30) * 2.7 * 255));
  const b = Math.min(255, Math.max(0, (a - 0.72) * 3.4 * 255));
  return [r, g, b];
}

function renderAndStats() {
  const S = subdiv();
  const half = cellHalf();
  dim = half * 2 + 1;
  ensureBuffer();
  const ptr = wasm.rf_read_layer(half, GROUND_Y);
  // memory.buffer may have detached if it grew during step — always rebuild the view.
  const view = new Float32Array(mem.buffer, ptr, dim * dim * 3);
  const px = imgData.data;
  const ign = state.material.ignition_temperature_k;

  let hot = 0, ash = 0, maxT = 0;
  let axialReach = 0, diagReach = 0;

  for (let z = 0; z < dim; z++) {
    for (let x = 0; x < dim; x++) {
      const i = (z * dim + x) * 3;
      const t = view[i];
      const m = view[i + 2];
      const cx = x - half, cz = z - half;
      let r, g, b;
      if (m === -2) { r = 30; g = 80; b = 200; }          // water
      else if (m === -1) { r = 255; g = 90; b = 235; }    // sustained source
      else {
        // base: unburnt fuel (green) vs burnt ash (grey-brown)
        let br, bg, bb;
        if (m === 0) {
          const ash = sampleTexture(textures.ash, cx, cz, S);
          if (ash) { br = ash[0]; bg = ash[1]; bb = ash[2]; }
          else { br = 74; bg = 63; bb = 55; }

          const ember = sampleTexture(textures.ember, cx, cz, S);
          const emberA = ember ? (ember[3] / 255) * Math.max(0, Math.min(1, (t - 300) / 180)) : 0;
          if (emberA > 0) [br, bg, bb] = blendRGB([br, bg, bb], ember, emberA);
        } else { br = 47; bg = 74; bb = 38; }             // fuel
        const a = Math.max(0, Math.min(1, (t - 360) / 540)); // 360..900K glow ramp
        if (a <= 0) { r = br; g = bg; b = bb; }
        else {
          const f = fireRGB(a);
          r = br * (1 - a) + f[0] * a;
          g = bg * (1 - a) + f[1] * a;
          b = bb * (1 - a) + f[2] * a;
        }
      }
      const p = (z * dim + x) * 4;
      px[p] = r; px[p + 1] = g; px[p + 2] = b; px[p + 3] = 255;

      if (t > maxT) maxT = t;
      if (m === 0) ash++;
      if (t >= ign && m >= 0) {
        hot++;
        const d = Math.hypot(cx, cz);
        const ang = Math.atan2(cz, cx);            // -pi..pi
        const a45 = Math.abs(((ang % (Math.PI / 2)) + Math.PI / 2) % (Math.PI / 2) - Math.PI / 4);
        // a45 ~ pi/4 near axes, ~0 near diagonals
        if (a45 > Math.PI / 4 - 0.18) axialReach = Math.max(axialReach, d);
        else if (a45 < 0.18) diagReach = Math.max(diagReach, d);
      }
    }
  }
  bctx.putImageData(imgData, 0, 0);
  cctx.imageSmoothingEnabled = SCENARIO.smooth;
  cctx.clearRect(0, 0, canvas.width, canvas.height);
  cctx.drawImage(buf, 0, 0, canvas.width, canvas.height);

  const roundness = axialReach > 0.5 ? (diagReach / axialReach) : 1;
  const shape = roundness < 0.82 ? 'diamond / +' : roundness > 1.18 ? 'square' : 'round ✓';
  const blkRadius = (half / S).toFixed(0);
  $('#stats').innerHTML =
    `<div>time <b>${simSeconds.toFixed(2)} s</b></div>` +
    `<div>flame cells <b>${hot}</b></div>` +
    `<div>burnt <b>${(ash / (S * S)).toFixed(1)} blk²</b></div>` +
    `<div>peak temp <b>${Math.round(maxT)} K</b></div>` +
    `<div>axial reach <b>${(axialReach / S).toFixed(1)} blk</b></div>` +
    `<div>diag reach <b>${(diagReach / S).toFixed(1)} blk</b></div>` +
    `<div>grid <b>${dim}×${dim} (${blkRadius}blk·S${S})</b></div>` +
    `<div>roundness <b>${(roundness * 100).toFixed(0)}%</b></div>` +
    `<div>front <b class="big">${shape}</b></div>`;
  return hot;
}

// --- main loop ----------------------------------------------------------------------------
let deadFrames = 0;
function frame() {
  if (wasm) {
    if (playing) {
      pushConfig();
      pushMaterial();
      // substeps per animation frame. The game runs 80 substeps/s; at ~60 fps, speed≈1.3 is
      // realtime, so the 1..8 range spans ~0.75×..6× — slow enough to judge how long flames linger.
      // One rf_step call runs the whole substep batch (reusing its internal buffers).
      const speed = parseInt($('#speed').value, 10);
      wasm.rf_step(DT, speed);
      simSeconds += speed * DT;
    }
    const hot = renderAndStats();
    if (playing && $('#autoloop').checked) {
      if ((hot === 0 && simSeconds > 1.0) || simSeconds > 30) {
        if (++deadFrames > 18) { reset(); deadFrames = 0; }
      } else deadFrames = 0;
    }
  }
  requestAnimationFrame(frame);
}

// --- export -------------------------------------------------------------------------------
function tunedGrassMaterial() {
  const out = {
    replace: true,
    targets: GRASS_TARGETS,
    fuel: state.material.fuel,
    ignition_temperature_k: state.material.ignition_temperature_k,
    burn_rate: state.material.burn_rate,
    heat_release: state.material.heat_release,
    smoke_yield: state.material.smoke_yield,
    moisture: state.material.moisture,
    insulation: state.material.insulation,
  };
  if (state.flags.has_char_stage) out.char_block = 'realisticfire:charred_block';
  if (state.flags.has_ash_stage) out.ash_block = 'minecraft:air';
  return out;
}

function exportObject() {
  const out = {
    solver: { ...state.solver },
    material: {
      fuel: state.material.fuel,
      ignition_temperature_k: state.material.ignition_temperature_k,
      burn_rate: state.material.burn_rate,
      heat_release: state.material.heat_release,
      smoke_yield: state.material.smoke_yield,
      moisture: state.material.moisture,
      insulation: state.material.insulation,
      has_char_stage: state.flags.has_char_stage,
      has_ash_stage: state.flags.has_ash_stage,
    },
    scenario: { ...SCENARIO },
    material_datapack: tunedGrassMaterial(),
    spread_variation: {
      deterministic: true,
      preview_seed: Math.max(1, Math.round(SCENARIO.seed)),
      radiant_preheat_multiplier: '0.78..1.22',
      burn_rate_multiplier: '0.88..1.12',
    },
  };
  out._note = 'Solver values are native-code constants unless this mod is rebuilt. Apply to instance writes the material datapack override and keeps this full snapshot under config/.';
  return out;
}

function exportJSON() {
  const out = exportObject();
  const text = JSON.stringify(out, null, 2);
  $('#export-out').value = text;
  return text;
}

function packMetaJSON() {
  return JSON.stringify({
    pack: {
      pack_format: 48,
      description: 'Realistic Fire editor material tuning',
    },
  }, null, 2) + '\n';
}

async function directory(root, parts) {
  let dir = root;
  for (const part of parts) dir = await dir.getDirectoryHandle(part, { create: true });
  return dir;
}

async function writeTextFile(dir, name, text) {
  const handle = await dir.getFileHandle(name, { create: true });
  const writable = await handle.createWritable();
  await writable.write(text);
  await writable.close();
}

function setApplyStatus(message, failed = false) {
  const el = $('#apply-status');
  if (!el) return;
  el.textContent = message;
  el.classList.toggle('error', failed);
}

async function applyToInstance() {
  const materialText = JSON.stringify(tunedGrassMaterial(), null, 2) + '\n';
  const snapshotText = exportJSON() + '\n';
  if (!window.showDirectoryPicker) {
    try { await navigator.clipboard.writeText(materialText); } catch (_) {}
    setApplyStatus('Folder write is unavailable in this browser. Material JSON copied instead.', true);
    return;
  }

  try {
    const root = await window.showDirectoryPicker({
      id: 'realisticfire-modpack-instance',
      mode: 'readwrite',
    });
    const pack = await directory(root, ['moonlight-global-datapacks', INSTANCE_DATAPACK]);
    await writeTextFile(pack, 'pack.mcmeta', packMetaJSON());
    const materials = await directory(pack, ['data', 'realisticfire', 'realistic_fire', 'materials']);
    await writeTextFile(materials, 'editor_grass.json', materialText);
    const config = await directory(root, ['config']);
    await writeTextFile(config, 'realisticfire-editor-config.json', snapshotText);
    setApplyStatus(`Wrote ${INSTANCE_DATAPACK} datapack and config snapshot. Reload datapacks or restart the instance.`);
  } catch (error) {
    if (error && error.name === 'AbortError') {
      setApplyStatus('Apply cancelled.');
    } else {
      setApplyStatus('Apply failed: ' + (error && error.message ? error.message : String(error)), true);
    }
  }
}

// --- wiring -------------------------------------------------------------------------------
function wireControls() {
  $('#play').addEventListener('click', () => {
    playing = !playing;
    $('#play').textContent = playing ? '⏸ Pause' : '▶ Play';
  });
  $('#reset').addEventListener('click', () => reset());
  $('#speed').addEventListener('input', () => { $('#speed-val').textContent = $('#speed').value; });
  $('#speed-val').textContent = $('#speed').value;
  $('#export').addEventListener('click', exportJSON);
  $('#copy').addEventListener('click', async () => {
    const t = exportJSON();
    try { await navigator.clipboard.writeText(t); $('#copy').textContent = 'Copied ✓'; setTimeout(() => $('#copy').textContent = 'Copy', 1200); } catch (_) {}
  });
  $('#download').addEventListener('click', () => {
    const t = exportJSON();
    const blob = new Blob([t], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob); a.download = 'fire-config.json'; a.click();
    URL.revokeObjectURL(a.href);
  });
  $('#apply-instance').addEventListener('click', applyToInstance);
  $('#reset-defaults').addEventListener('click', () => {
    for (const p of SOLVER_PARAMS) state.solver[p.key] = p.val;
    for (const p of MATERIAL_PARAMS) state.material[p.key] = p.val;
    for (const f of MATERIAL_FLAGS) state.flags[f.key] = f.val;
    saveState();
    location.reload();
  });
}

async function boot() {
  loadState();
  buildUI();
  wireControls();
  try {
    const [resp, ashTexture, emberTexture] = await Promise.all([
      fetch('realisticfire_solver.wasm'),
      loadTexture('textures/scorch_ash.png'),
      loadTexture('textures/ember_overlay.png'),
    ]);
    textures = { ash: ashTexture, ember: emberTexture };
    if (!resp.ok) throw new Error('could not fetch realisticfire_solver.wasm (' + resp.status + ')');
    const bytes = await resp.arrayBuffer();
    const { instance } = await WebAssembly.instantiate(bytes, {});
    wasm = instance.exports;
    mem = wasm.memory;
    reset();
    renderAndStats();
  } catch (e) {
    showError(e.message + '\nServe this folder over HTTP (e.g. `python3 -m http.server`) — file:// blocks WASM fetch.');
    return;
  }
  requestAnimationFrame(frame);
}

boot();
