#!/usr/bin/env bash
# Regenerates the Kotlin API models from server/crates/tasky-api.
# Requires: cargo install typeshare-cli
set -euo pipefail
cd "$(dirname "$0")/.."
typeshare --lang kotlin -c typeshare.toml -o api/kotlin/TaskyApi.kt server/crates/tasky-api
echo "wrote api/kotlin/TaskyApi.kt"
