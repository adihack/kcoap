# Interop plan

libcoap and libedhoc are the **reference oracles**: libcoap for CoAP, DTLS and OSCORE (its maintainers
include RFC authors, e.g. Jon Shallow for RFC 8768 and RFC 9177), libedhoc for EDHOC (our own
implementation, already verified against RFC 9529). Everything else fills gaps they leave.

Protocol logic is common Kotlin code, so all of this runs on the `jvm` target. Platform crypto
differences are caught separately by running the RFC vectors on every target in CI.

## CoAP against libcoap (running)

The CoAP endpoint is cross-tested with libcoap v4.3.5b over real UDP on the host network, in both
directions. The tests live in `kcoap-coap/src/jvmTest/kotlin/dev/kcoap/coap/interop/`, are tagged
`interop`, and skip (JUnit assumption) when `docker info` fails or the image is missing.

```bash
docker build -t kcoap/libcoap:v4.3.5b interop/libcoap     # once
./gradlew :kcoap-coap:jvmTest --tests 'dev.kcoap.coap.interop.*'
```

| Test class | Peer | What runs |
|---|---|---|
| `KcoapClientAgainstLibcoapTest` | `coap-server -p <port> -d 10 -v 4` | `/.well-known/core` discovery and `?rt=ticks` filtering; GET of `/` as CON and NON; PUT/GET/POST (4.05) on `/example_data`; PUT/GET/DELETE of a dynamic resource; a 3000-byte Block1 upload and Block2 download; early negotiation of 64-byte blocks; Observe of `/time` with deregistration (GET with Observe 1, same token); the separate response of `/async?1`; CoAP ping |
| `LibcoapClientAgainstKcoapTest` | `coap-client -B 10 -v 6 -w ...` | GET (`-N` too); PUT with `-e`, POST, DELETE; a Block1 upload with `-b 64 -f` (46 × 2.31 Continue, then 2.04); Block2 downloads with `-b 128` and with the server's 1024-byte blocks; Observe with `-s 3` of a resource notifying every 500 ms; `/.well-known/core` with and without `?rt=` filtering; 4.04 and 4.05 |

`interop/libcoap/coap.sh` runs any libcoap example program the way the tests do, for manual checks:

```bash
interop/libcoap/coap.sh coap-server -p 5683 -d 10 -v 7
interop/libcoap/coap.sh coap-client -v 6 -s 10 coap://127.0.0.1:5683/time
```

`KCOAP_LIBCOAP_IMAGE` points both at another libcoap build. The JVM test task is never served from the
Gradle build cache, because whether docker is there is invisible to Gradle.

## Layer 1: byte-exact differential tests (deterministic)

Interop only proves that a handshake completes. Comparing bytes proves both sides compute the same
thing, message by message, and pinpoints the first field that differs.

### EDHOC against libedhoc

A small C program (`interop/libedhoc-oracle/`, to be written) links libedhoc and replaces the ephemeral
key generation in its crypto table with keys read from the test case. libedhoc's own RFC 9529 tests use
the same technique (`tests/host/rfc9529/test_rfc9529_chapter2.c`). For each case it prints, as hex:
message_1 to message_4, PRK_out, and the EDHOC_Exporter output for the OSCORE Master Secret and Salt.

The Kotlin JVM test generates the cases, runs the program as a subprocess, and compares byte for byte:

- methods 0–3, both roles
- suites 0, 2, 4, 24 (and 1, 3 once libedhoc has them)
- credentials: kid, CCS by value, x5t, x5chain
- connection identifiers: integer and byte-string forms
- EAD: none, padding, multiple items
- error messages and their codes

This covers far more combinations than the two RFC 9529 traces. A subprocess instead of JNI keeps
native code out of the Kotlin test classpath and works the same in CI.

### OSCORE against libcoap

OSCORE has no randomness: with the same security context and sender sequence number, protection is
deterministic. libcoap protects a fixed set of requests and responses (Observe, Block1/Block2, Echo,
Proxy-Uri, unknown options, Appendix B.2 re-derivation) and the bytes are captured on loopback. The
Kotlin side protects the same messages and must produce identical bytes, and must verify libcoap's output.

### DTLS against Bouncy Castle

On the JVM, Bouncy Castle `bctls` (PSK, raw public keys, AES-128-CCM-8, Connection ID) runs in the
same process. With fixed randoms and ephemeral keys on both sides, the handshake flights and records
are compared byte for byte, for both roles.

## Layer 2: live interop over UDP

1. **OSCORE interop spec** (core-wg test-spec5) using libcoap's `examples/oscore_testcases.sh`: kcoap
   server against the libcoap client, kcoap client against the libcoap server.
2. **Our libcoap + libedhoc application** as peer: EDHOC over CoAP (`/.well-known/edhoc`), sequential
   flow and RFC 9668 combined request, then OSCORE traffic on the derived context.
3. Loss, duplication and reordering injected between the peers (CoAP retransmission, EDHOC
   deduplication per RFC 9528 §7, OSCORE replay window, DTLS flight retransmission and record replay).

### DTLS and TLS against libcoap

libcoap builds against several DTLS libraries, so one oracle gives a matrix of independent DTLS
stacks. Feature support per libcoap's README:

| libcoap backend | PSK | RPK | Certificates | DTLS 1.3 |
|---|---|---|---|---|
| TinyDTLS | yes | yes | no | no |
| GnuTLS | yes | 3.6.6+ | yes | no |
| wolfSSL | yes | 5.6.4+ | yes | 5.4.0+ |
| OpenSSL | yes | no | yes | no |
| Mbed TLS | yes | no | yes | no |

Each runs `coap-client` and `coap-server` against kcoap in both roles: PSK and RPK with the CCM-8
suites, certificates with SubjectAltName and CN matching, session resumption, and Connection ID where
the build reports it (`coap_dtls_cid_is_supported()`). The TLS-capable builds (all but TinyDTLS) also
cover `coaps+tcp` and `coaps+ws`. TinyDTLS is the closest match to what constrained devices run.

Browser path: browser → libcoap `coap-server` as proxy (`coaps+ws` in, `coap`/`coaps` out) → device,
with OSCORE + EDHOC end to end.

## Layer 3: other partners, for what the oracles do not cover

| Partner | Fills which gap |
|---|---|
| uoscore-uedhoc (C) | EDHOC suites 1 and 3, until libedhoc implements them |
| aiocoap (Python) | A second opinion on the RFC 9668 combined flow |
| lakers (Rust) | A second EDHOC opinion (method 3, suite 2 only) |
| EDHOC-Fuzzer (Java) | Protocol state fuzzing of the kcoap Initiator and Responder |
| DTLS-Fuzzer, TLS-Attacker (Java) | State-machine learning and malformed-message tests of the kcoap DTLS client and server |

Nobody implements suites 5, 6 or 25.

## Gaps on the oracle side

- **libedhoc lacks suite 3**, which RFC 9528 §8 makes mandatory, and suite 1. Both differ from suites 2
  and 0 only in EDHOC MAC length and EDHOC AEAD tag length (AES-CCM-16-128-128). Adding them to libedhoc
  makes it compliant and removes the need for uoscore here.
- **libcoap has no EDHOC**; our application glue provides `/.well-known/edhoc` and the combined request.
- **Hop-Limit**: libcoap processes it as Outer (Class U), as draft-ietf-core-oscore-capable-proxies
  specifies; the published RFCs still make it Class E. See `docs/rfc-compliance.md`.

## Layout (planned)

`interop/libedhoc-oracle/` (C harness), `interop/libcoap-oracle/` (scripts and configs), and one
container image per peer, pinned to a commit.
