#!/usr/bin/env bash
# Conformance gate (#145, processor #40): check the golden batches against the event catalogue at
# the processor ref (branch or tag) in catalogue.version. Needs go and git. PROCESSOR_DIR reuses a local checkout.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
ref="$(cat "$root/catalogue.version")"
src="${PROCESSOR_DIR:-}"
if [ -z "$src" ]; then
  src="$(mktemp -d)"
  trap 'rm -rf "$src"' EXIT
  git clone -q --depth 1 --branch "$ref" https://github.com/NCG-Africa/edge_telemetry_processor_go.git "$src"
fi
cd "$src" && go run ./cmd/conform "$root/telemetry_library/src/test/resources/golden"
