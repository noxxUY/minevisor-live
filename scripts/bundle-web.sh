#!/bin/sh
# Build the minevisor workspace page and bundle it into the plugin/mod jars.
# Usage: scripts/bundle-web.sh <path to the minevisor repo>
# The page is served by the plugin at <public-url>/workspace/.
set -e
site="${1:?usage: scripts/bundle-web.sh <path to the minevisor repo>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/web-bundle/minevisor-web"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

(cd "$site" && npx astro build --outDir "$tmp" >/dev/null)

rm -rf "$out"
mkdir -p "$out"
# The workspace page, its hashed assets, and the small root files it uses.
# Sample worlds (40+ MB) and the landing page are left out.
cp -R "$tmp/workspace" "$tmp/_astro" "$out/"
find "$tmp" -maxdepth 1 -type f \( -name "*.png" -o -name "*.svg" \) -exec cp {} "$out/" \;
echo "bundled $(du -sh "$out" | cut -f1) into web-bundle/"
