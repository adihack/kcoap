#!/usr/bin/env bash
# Re-downloads the NIST ACVP ML-KEM and ML-DSA vectors (pinned commit) and regenerates the Kotlin test-vector
# sources of kcoap-crypto-testsuite, whose conformance suites replay them through any backend.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
commit=975de31eb83d87039ec88934fdc47d8c312b892d
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
for name in ML-KEM-keyGen-FIPS203 ML-KEM-encapDecap-FIPS203 ML-DSA-keyGen-FIPS204 ML-DSA-sigGen-FIPS204 \
  ML-DSA-sigVer-FIPS204; do
  curl -sfL "https://raw.githubusercontent.com/usnistgov/ACVP-Server/${commit}/gen-val/json-files/${name}/internalProjection.json" \
    -o "$work/${name}.json"
done
python3 -I "$here/gen_pq_vectors.py" "$work" \
  "$here/../kcoap-crypto-testsuite/src/commonMain/kotlin/dev/kcoap/crypto/testsuite/vectors" "$commit"
