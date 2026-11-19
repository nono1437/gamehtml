#!/usr/bin/env bash
set -euo pipefail

# Use Rust 1.100.0 with wasm32-unknown-unknown and rust-src, Node.js 24,
# wasm-bindgen 0.2.120 and Binaryen 129 (wasm-opt).
repo_root=$(cd "$(dirname "$0")/.." && pwd)
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT

archive="$build_dir/ruffle.tar.gz"
curl --fail --silent --show-error --location \
  https://codeload.github.com/ruffle-rs/ruffle/tar.gz/refs/tags/v0.4.1 -o "$archive"
printf '%s  %s\n' 9fe38def86ba908492fce0ce75080fcdbe6aa38532137910c13d256a1268c948 "$archive" \
  | sha256sum --check
tar -xzf "$archive" -C "$build_dir"
cd "$build_dir/ruffle-0.4.1"
patch -p1 < "$repo_root/scripts/seer2-ruffle-socket.patch"
if [[ -n "${CARGO_TARGET_DIR:-}" ]]; then
  mkdir -p "$CARGO_TARGET_DIR"
  export CARGO_TARGET_DIR=$(cd "$CARGO_TARGET_DIR" && pwd)
  ln -s "$CARGO_TARGET_DIR" target
fi

cd web
npm ci --ignore-scripts --no-audit --no-fund
CFG_RELEASE_CHANNEL=stable BUILD_WASM_MVP=true npm run build --workspace=ruffle-core
npm run build --workspace=ruffle-selfhosted

dist=packages/selfhosted/dist
assets="$repo_root/app/src/main/assets/ruffle/seer2"
mkdir -p "$assets"
find "$assets" -maxdepth 1 -type f \( -name '*.js' -o -name '*.wasm' \) -delete
cp "$dist/ruffle.js" "$dist"/core.ruffle.*.js "$dist"/*.wasm "$dist"/LICENSE* "$assets/"
