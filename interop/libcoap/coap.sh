#!/usr/bin/env bash
# Runs a libcoap example program from the kcoap/libcoap image on the host network, with the current
# directory mounted at /data, the way the JVM interop tests do.
#
#   interop/libcoap/coap.sh coap-server -p 5683 -d 10 -v 7
#   interop/libcoap/coap.sh coap-client -v 6 -m put -b 64 -f /data/file.txt coap://127.0.0.1:5683/store
#   interop/libcoap/coap.sh coap-client -s 10 coap://127.0.0.1:5683/time
#
# KCOAP_LIBCOAP_IMAGE selects another image (default kcoap/libcoap:v4.3.5b).
set -euo pipefail

image="${KCOAP_LIBCOAP_IMAGE:-kcoap/libcoap:v4.3.5b}"
tty=()
if [ -t 0 ] && [ -t 1 ]; then tty=(-it); fi
exec docker run --rm "${tty[@]}" --network host -v "$PWD:/data" "$image" "$@"
