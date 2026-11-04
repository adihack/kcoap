#!/usr/bin/env bash
# Re-downloads the RFC texts and regenerates the Kotlin test-vector sources.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
for rfc in 3610 5869 8613; do
  curl -sfL "https://www.rfc-editor.org/rfc/rfc${rfc}.txt" -o "$work/rfc${rfc}.txt"
done
python3 -I "$here/gen_vectors.py" "$work" \
  "$here/../kcoap-crypto-default/src/commonTest/kotlin/com/example/kcoap/crypto/vectors"
