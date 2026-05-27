#!/usr/bin/env bash
# Compile the solver core to WebAssembly and drop it next to the editor page.
# The editor runs the exact same Rust simulation the mod ships, so dialed-in values transfer back.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
crate="$here/../../src/main/rust/realisticfire_solver"

echo "==> cargo build --release --target wasm32-unknown-unknown"
( cd "$crate" && cargo build --release --target wasm32-unknown-unknown )

wasm="$crate/target/wasm32-unknown-unknown/release/realisticfire_solver.wasm"
cp "$wasm" "$here/realisticfire_solver.wasm"
echo "==> copied $(du -h "$here/realisticfire_solver.wasm" | cut -f1) to $here/realisticfire_solver.wasm"

echo
echo "Serve it:   (cd \"$here\" && python3 -m http.server 8777)"
echo "Then open:  http://localhost:8777/"
