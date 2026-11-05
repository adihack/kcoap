# Interop plan

libcoap and libedhoc are the **reference oracles**: libcoap for CoAP, DTLS and OSCORE (its maintainers
include RFC authors, e.g. Jon Shallow for RFC 8768 and RFC 9177), libedhoc for EDHOC (our own
implementation, already verified against RFC 9529). Everything else fills gaps they leave.

Protocol logic is common Kotlin code, so all of this runs on the `jvm` target. Platform crypto
differences are caught separately by running the RFC vectors on every target in CI.

## CoAP against libcoap (running)

The CoAP endpoint is cross-tested with libcoap v4.3.5b over real UDP, TCP and WebSockets on the host
network, in both directions. The tests live in `kcoap-coap/src/jvmTest/kotlin/dev/kcoap/coap/interop/`, are tagged
`interop`, and skip (JUnit assumption) when `docker info` fails or the image is missing.

```bash
docker build -t kcoap/libcoap:v4.3.5b interop/libcoap     # once
./gradlew :kcoap-coap:jvmTest --tests 'dev.kcoap.coap.interop.*'
```

| Test class | Peer | What runs |
|---|---|---|
| `KcoapClientAgainstLibcoapTest` | `coap-server -p <port> -d 10 -v 4` | `/.well-known/core` discovery and `?rt=ticks` filtering; GET of `/` as CON and NON; PUT/GET/POST (4.05) on `/example_data`; PUT/GET/DELETE of a dynamic resource; a 3000-byte Block1 upload and Block2 download; early negotiation of 64-byte blocks; Observe of `/time` with deregistration (GET with Observe 1, same token); the separate response of `/async?1`; CoAP ping |
| `LibcoapClientAgainstKcoapTest` | `coap-client -B 10 -v 6 -w ...` | GET (`-N` too); PUT with `-e`, POST, DELETE; a Block1 upload with `-b 64 -f` (46 × 2.31 Continue, then 2.04); Block2 downloads with `-b 128` and with the server's 1024-byte blocks; Observe with `-s 3` of a resource notifying every 500 ms; `/.well-known/core` with and without `?rt=` filtering; 4.04 and 4.05; the Q-Block fallback with `-L 7 -v 7` (below) |
| `KcoapClientAgainstLibcoapQBlockTest` | `coap-server -p <port> -L 7 -v 7` (also `-N`, `-l`, `-e`, `-w`, and without `-L`) | the kcoap client with Q-Block (RFC 9177), K1–K10, K12 and the K soak (below) |
| `LibcoapClientAgainstKcoapAddressVerificationTest` | `coap-client -B 10 -v 7 -o ...` (also `-N`, and `-L 7`) | a kcoap server with `AmplificationPolicy.ALL_RESPONSES` (RFC 9175 §2.4 item 3): a piggybacked 4.01 with Echo, then the whole 3000-byte body in Block2 after libcoap's repeat; a NON 4.01 for `-N`; libcoap's Q-Block probe of `/.well-known/core` answered 4.02 at once, never challenged |
| `LibcoapClientAgainstKcoapQBlockTest` | `coap-client -L 7 -v 7 ...` (also `-L 15`, `-N`, `-l`) | a kcoap server with Q-Block on, over UDP, TCP and WebSockets (below) |

### Q-Block (RFC 9177)

libcoap 4.3.5 has no `-Q` flag. Q-Block is turned on with `-L`, the sum of libcoap's `COAP_BLOCK_*` flags:
1 lets libcoap do block-wise transfers, 2 delivers a single body, 4 tries Q-Block, 8 (client) sets the M bit
in missing-block requests; 16 and 32 (client) and 64 and 128 (server) change other block-wise behaviour.
The Q-Block runs use `-L 7` (and `-L 15` for the M bit) on both `coap-client` and `coap-server`.

kcoap implements Q-Block in its server (QB-3) and its client (QB-4; see
[docs/plan-qblock-and-platforms.md](../docs/plan-qblock-and-platforms.md)), with Observe (QB-6), off by
default on both sides, and under OSCORE only where both sides opt in (QB-5, QB-7; its libcoap runs are listed
with `StackOscoreInteropTest` below).
While the server's is off, the fallback of RFC 9177 §4.1 is pinned: `coap-client -L 7` first sends a
Confirmable `GET /.well-known/core` with `Q-Block2: 0/_/16`, kcoap answers 4.02 "Unrecognized critical
option 31", libcoap logs "Q-Block support not available" and sends its real request at once with RFC 7959
options (`LibcoapClientAgainstKcoapTest.fallsBackFromQBlockWhileItIsOff`).

kcoap's client (`QBlockClientConfig(QBlockMode.PROBE)`) runs against `coap-server -L 7 -v 7` in
`KcoapClientAgainstLibcoapQBlockTest`, one server per case since `-l` counts every datagram the server
sends, the probe's answer first. Cases K1–K10 and K12 of the plan: the probe and two Continues of a
NON GET of `/example_data` in 64-byte blocks, under 1.5 s; missing blocks asked for at once (`-l 4,9`),
block 0 asked for as `0/M` (`-l 2`) and a missing tail as `3/M` (`-l 5-25`, M bit); a NON PUT of 3000 bytes
with 2.31 at 9, 19, 29 and 39; blocks 2 and 7 lost on the kcoap side and asked for by libcoap's 4.08 `02 07`
about 4 s after block 39, where the client waits with four sets unconfirmed, and a 300-block upload with
block 2 lost that never holds more than about four sets of tokens; `coap-server` without `-L`; an
observation of `/example_data` with `-N` whose notification bodies (1500 and 3000 bytes, the second from
libcoap's own PUT) arrive whole, once each, and that deregisters on its token (K6); Confirmable bodies; a FETCH
answered in Q-Block2 on the last Q-Block1 token; 20 KiB (320 blocks) over TCP and WebSockets (`-X 1152`) back
to back; and a lost probe answer. The **K soak** (`kSoakBodiesBothWaysAt10PercentLoss`) puts 3000 bytes and
gets them back with 10 % loss on both sides (`-l 10%` and a lossy transport on kcoap's side), 3 runs here and
20 with `-Pkcoap.soak=true` (not yet scheduled in CI; [docs/testing.md](../docs/testing.md#nightly-soak)): every
run ends with the right body, a clean `CoapException` or libcoap's plain 4.08 below, and leaves no exchange.
Deviations of libcoap 4.3.5b's server, each pinned by the `upstreamLibcoap…` test named:

- three separate gaps in an upload get a plain 4.08 ("Too many missing blocks"), and the body is given up
  (`upstreamLibcoapGivesUpAnUploadWithThreeGapsAndItsPlain408IsReturnedK4`);
- a block asked for once its 8 s cache expired comes without ETag and Size2, from the handler run again
  (`upstreamLibcoapAnswersWithoutETagOnceItsCacheExpiredAndTheGetRestartsK8`);
- only block 0 of a 2.01 or 2.04 body is sent (`-e`); the rest comes when the client asks for it
  (`upstreamLibcoapSendsOnlyBlock0OfAnEchoedPutBodyAndTheRestIsFetchedK9`);
- without `-L`, its 4.02 to the probe repeats the Q-Block2 option it rejects
  (`upstreamLibcoapRepeatsTheQBlock2OptionItRejectsInIts402K5`);
- the blocks of a notification body that it sends for a Continue carry no Observe, where RFC 9177 §4.5 wants
  the body's value on every payload; kcoap's client takes them by their ETag
  (`upstreamLibcoapLeavesObserveOutOfTheBlocksItSendsForAContinueK6`);
- Q-Block sticks to a session: once a request from an address and port carried Q-Block1 or Q-Block2, every
  later body its block-wise layer sends there goes in Q-Block2, also to a request that asks for Block2, where RFC 9177 §4.1
  sends Q-Block2 only to a request that carries Q-Block. All of a kcoap endpoint's clients share its socket,
  so after one Q-Block body a request that kcoap's client sends with RFC 7959 (one with block options of its
  own, with No-Response, or protected without `withMessageSecurity`) gets a Q-Block2 answer, rejects it as an
  unrecognized critical option and fails with `BAD_RESPONSE`
  (`upstreamLibcoapAnswersABlock2RequestInQBlock2OnceItsSessionUsedQBlock`);
- a request with both Block2 and Q-Block2 gets 4.00 "Both Block2 and Q-Block2 invalid" instead of the 4.02 of
  RFC 9177 §4.1 (`upstreamLibcoapAnswersBlock2MixedWithQBlock2With400`, a raw datagram, since kcoap never sends
  the mix).

Two more deviations named in the plan (§1.6) are known from libcoap's source but not pinned:

- **A descending list of missing blocks** (server). `add_block_send` in `src/coap_block.c` keeps the requested
  numbers sorted, but when a lower number comes after a higher one it overwrites the slot instead of moving it
  (its `memmove` also counts bytes, not entries), so a request listing `7` then `2` leaves the second slot
  uninitialized and the server sends a block whose number is whatever that memory held. That is undefined
  behaviour, so no test can expect a stable outcome, and it may take the container down. kcoap's client always
  lists missing blocks in ascending order (RFC 9177 §4.4), and kcoap's server answers a descending list 4.00 (L8).
- **An upload truncated after a 2.31 without Q-Block1** (client). libcoap's client cuts an upload short when a
  2.31 comes without Q-Block1 (plan §1.1, row 14). kcoap's server always puts Q-Block1 in a 2.31 for that reason
  (ADR 0006), so pinning this needs a server that leaves it out, which kcoap does not have.

With `CoapServerConfig(qBlock = QBlockServerConfig(isEnabled = true))`, `LibcoapClientAgainstKcoapQBlockTest`
runs the plan's cases against `coap-client -L 7 -v 7` (`-L` goes before `-f`), reads every message from
libcoap's log (`Libcoap.wire`: the `v:1` line after each `netif: sent|recv` line) and compares the body
bytes each time, since libcoap's client can exit 0 with a truncated body:

| Case | libcoap side | What it shows |
|---|---|---|
| L1 | `-L 7 coap://…/` | the probe gets block 0 piggybacked with Q-Block2, ETag and Size2; libcoap logs "Q-Block support available" |
| L2 | `-L 7 -N -b 64 -o … /big` (3000 B), also `-L 15`, and no `-b` | sets of 10 blocks with one ETag and Size2; libcoap's Continues 10, 20, 30, 40 answered on the original token, all in well under 1.5 s |
| L3 | as L2, kcoap loses blocks 2 and 7 (`DroppingTransport`); `-L 15` with 3 to 9 lost | one missing-block request [2, 7] when block 10 arrives, answered on its own token; with the M bit `3/M`, answered with 3 to 9 only |
| L4 | `-L 7 -N -b 64 -m put -f /data/big3000.txt` | nothing for blocks in the middle, 2.31 with Q-Block1 9, 19, 29, 39, then 2.04; one Request-Tag and Size1 throughout |
| L5 | L4 with `-l 4,9`, and with `-l 48` | a Non-confirmable 4.08 `02 07` (Content-Format 272) at once on a block of the later set; `18 2e` NON_RECEIVE_TIMEOUT (4 s) after the last block that came |
| L6 | `-L 7 -N -b 64 -s 10 /obs` (1500 B, changing every 3 s) | Observe on every block of every notification body, one ETag per body, Continues continuing on the registration's token, nothing on a Continue's own token; libcoap's deregistration with Observe 1 removes the observer |
| L6 + Echo | L6 against a server with the default `AmplificationPolicy.Q_BLOCK_BURSTS` | the registration gets a Non-confirmable 4.01 with Echo whatever the size of its first response (its bodies are bursts no request asks for); libcoap repeats the registration with the Echo value and Q-Block2, and every notification body then arrives whole |
| L7 | L2 and L4 without `-N` | an Empty ACK per Q-Block1 block and the final response piggybacked, no 2.31; block 0 piggybacked, then Confirmable blocks one per ACK |
| L8 | raw datagrams | a descending Q-Block2 list and Q-Block1 without Request-Tag or Size1 get 4.00, Block mixed with Q-Block 4.02 |
| L9 | L4 and L2 with `-l 10%`, and 10 % loss on kcoap's side; 3 runs here, 20 with `-Pkcoap.soak=true` (not yet scheduled in CI) | every upload stores the whole body or nothing; every download ends with the body, with libcoap giving up (an error, or exit 0 with nothing written), or with one block written for it, which counts as the client deviation below only when every block reached libcoap and kcoap served one body (one ETag and Size2 on every block, each block on the token of a request that asked for it); kcoap holds no partial body afterwards; at 20 runs most runs reach the end (the first: 14 of 20 uploads, 10 of 20 downloads, 8 meeting the deviation) |
| L10 | `-L 7 -b 64` over `coap+tcp` and `coap+ws` | blocks back to back, only the final response, no Continue |
| L12 | L2 against a server with the default `AmplificationPolicy.Q_BLOCK_BURSTS` | the first burst request gets a Non-confirmable 4.01 with Echo; libcoap repeats it with the Echo value and gets the body |

What the runs showed about libcoap 4.3.5b's client:

- Its first Q-Block2 request has an empty token and a Request-Tag, and carries `Q-Block2 0/M/<size>`; its
  Continues repeat the Request-Tag on tokens of their own.
- Without `-b` it asks without Q-Block2, so kcoap, which keeps no per-client Q-Block state (ADR 0006),
  answers with RFC 7959 Block2, which libcoap follows to the end.
- It repeats an Observe registration with the Echo value of a 4.01 (L6 + Echo), as it repeats other requests.
- It writes no `-o` file for an observation, and when it gives a download up it may exit 0 with nothing
  written (L9), so every case compares the bytes it wrote.

Deviations of libcoap 4.3.5b's client, each pinned by the `upstreamLibcoap…` test named:

- **Continue after an upload**
  (`upstreamLibcoapRepeatsQBlock1WithoutPayloadInFollowUpsOfAnUploadAndGets400`): when a Q-Block1 POST gets
  a Q-Block2 body back, libcoap's Continue repeats `Q-Block1 0/M` and Size1 without a payload. RFC 9177 §4.7
  wants neither, and a Q-Block1 block with M set must fill its block (RFC 7959 §2.3), so kcoap answers
  4.00; libcoap then gives the body up after its first set.
- **Gaps across two sets** (`upstreamLibcoapWritesOneBlockForABodyWhoseGapsSpanTwoSetsL3`, found by L9): with
  blocks 3, 11 and 14 lost on kcoap's side, libcoap asks for block 3 alone when block 10 arrives, sends Resets
  to blocks of set 1 on the original token, asks for the rest of set 1 with `11/M` and for the later sets, gets
  every block, and exits 0 having written one 64-byte block instead of the 3000-byte body. The test also checks
  kcoap's side: one ETag and Size2 3000 on every block, every request for missing blocks answered on its own
  token from the cached body, and the Continues' sets on the original token, so it holds only while the fault
  is libcoap's.

```bash
interop/libcoap/coap.sh coap-client -L 7 -v 7 -B 10 coap://127.0.0.1:5683/hello
interop/libcoap/coap.sh coap-client -L 7 -N -b 64 -v 7 -B 30 -m put -f /data/big3000.txt coap://127.0.0.1:5683/store
interop/libcoap/coap.sh coap-server -p 5683 -L 7 -v 7
```

### Over TCP and WebSockets (RFC 8323)

The same image (built with `--enable-tcp --enable-websockets`) serves `coap+tcp` on the `-p` port and
`coap+ws` on the `-w` port. kcoap's side runs `KtorTcpConnector`/`KtorTcpListener` and
`KtorWebSocketConnector`/`KtorWebSocketListener` on real loopback sockets.

```bash
./gradlew :kcoap-coap:jvmTest --tests 'dev.kcoap.coap.interop.*Reliable*'
```

| Test class | Peer | What runs, over `coap+tcp` and `coap+ws` each |
|---|---|---|
| `KcoapClientAgainstLibcoapReliableTest` | `coap-server -p <port> -w <ws port> -d 10 -X 1152 -v 4` | CSM exchange (kcoap's first; libcoap's Max-Message-Size 1152 and Block-Wise-Transfer); GET of `/`; PUT/GET of `/example_data`; PUT/GET/DELETE of a dynamic resource; a 3000-byte upload in 1024-byte Block1 blocks and its download in one message (kcoap announces 8704); Observe of `/time` with deregistration (GET with Observe 1, same token); Ping and Ping with Custody |
| | `coap-server -p <port> -d 10 -X 4352 -v 4` (TCP) | a 20000-byte BERT upload in 4 KiB blocks (numbers 0, 4, 8, 12, 16, 19) answered with BERT 2.31s, and the download in BERT blocks |
| `LibcoapClientAgainstKcoapReliableTest` | `coap-client -B 10 -v 6 -w ...` | GET of `/hello`; PUT with `-e`, GET, DELETE; `-m put -f` of 20000 bytes, which libcoap sends in BERT blocks to kcoap's 8704 (the last one several KiB); a 20000-byte download with `-X 4352` in BERT blocks, and with `-X 1152` over WebSockets in 1024-byte blocks; Observe with `-s 3` |

Commands as the tests run them, for manual checks:

```bash
interop/libcoap/coap.sh coap-server -p 5683 -w 8080 -d 10 -X 1152 -v 7
interop/libcoap/coap.sh coap-client -v 6 -w coap+tcp://127.0.0.1:5683/time
interop/libcoap/coap.sh coap-client -v 6 -w -X 1152 -s 3 coap+ws://127.0.0.1:8080/time
interop/libcoap/coap.sh coap-client -v 6 -m put -f /data/large.txt coap+tcp://127.0.0.1:5683/store
```

What the runs showed about libcoap v4.3.5b (kcoap follows RFC 8323 and works around the first two):

- **Pong without the token.** libcoap answers every Ping with an empty token (and always with Custody),
  although §5.4 requires the identical token. kcoap matches a Pong with an empty token to its oldest
  outstanding Ping; Pongs come in order on a reliable transport.
- **Final BERT blocks of more than 1024 bytes are rejected** with 4.08 "Too many missing blocks":
  libcoap records the total at the first 1024-byte block of a final BERT block, then refuses the rest.
  §6 allows "a multiple (possibly 0) of 1024 bytes plus a partial block". kcoap therefore cuts the block
  before the last 1024-byte boundary, so its final BERT block carries at most 1024 bytes. That costs
  one message at most, and both blocks are valid BERT.
- **WebSocket frames above 1472 bytes kill the server.** libcoap reads WebSocket frames into a 1472-byte
  buffer whatever Max-Message-Size its CSM announces, logs "packet size bigger than provided data space",
  and then aborts with "stack smashing detected". The WebSocket tests therefore run libcoap with
  `-X 1152`. kcoap sends what the peer's CSM allows, as RFC 8323 §5.3.1 says, so a default libcoap
  WebSocket peer (8 MiB announced) should be given a smaller `-X` until this is fixed upstream.

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

Planned as an option of `edhoc_peer` (the live peer below): replace the ephemeral key generation in
libedhoc's crypto table with keys read from the test case, as libedhoc's own RFC 9529 tests do
(`tests/host/rfc9529/test_rfc9529_chapter2.c`), and inject the same keys on the kcoap side through
`EphemeralKeySource`. Every message then has to match byte for byte, not just the derived secrets.

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

### Running now

`kcoap-oscore/src/jvmTest/.../LibcoapOscoreInteropTest.kt` runs with every `./gradlew jvmTest` when
Docker and the `kcoap/libcoap:v4.3.5b` image (built from `interop/libcoap/Dockerfile`) are present,
and is skipped otherwise:

- kcoap OSCORE client → libcoap `coap-server -E`: GET `/time`, including libcoap's RFC 8613 B.1.2
  replay-window challenge (protected 4.01 with Echo, answered by repeating the request with Echo).
- libcoap `coap-client -E` (with a sequence-number file) → a minimal kcoap OSCORE responder: GET and
  PUT, and the RFC 8613 §8.2 unprotected error response on replay.

libcoap's own debug log is written to `kcoap-oscore/build/interop-logs/` when an exchange fails.

`kcoap-oscore/src/jvmTest/.../StackOscoreInteropTest.kt` does the same through kcoap's CoAP client and
server over real UDP (ktor-network):

- `CoapClient` with `OscoreClient` → `coap-server -E -d 5`: GET `/time` (the stack repeats the request
  with Echo after the B.1.2 challenge), PUT and GET of a dynamic resource, Observe of `/time`.
- `coap-client -E` → `CoapServer` with `OscoreServer` (requiring freshness): GET, Block2 download in
  64-byte blocks, Block1 upload, Observe with `-s 3`; every block and notification is protected on its own.
- Q-Block under OSCORE (QB-5, cases K11 and L11 of the plan), both sides with Q-Block on (`-L 7`): with the
  defaults, kcoap's client keeps protected bodies on Inner Block1 and Block2 against `coap-server -L 7 -E`
  and never probes, and kcoap's server answers `coap-client -L 7 -N -b 64 -E`'s protected probe with an inner
  4.02, after which libcoap falls back to Block2 and gets the body (after about 5 s, libcoap's own wait while
  it tests for Q-Block). With `withMessageSecurity` on, libcoap 4.3.5b protects, or verifies, only the first
  response to a request, pinned as `upstream…` tests: its server logs "OSCORE: PDU could not be encrypted"
  for every block of a Q-Block2 set after the first, so a download gets one block per request (Q-Block1
  uploads complete, since each block's request gets one response, and a client on the defaults reads the
  stored body back with Inner Block2), and its client drops a request's OSCORE
  association with the first response ("Security Context association not found") and gives up at block 1.
  kcoap against kcoap takes these bodies (`OscoreQBlockEndToEndTest`), also the notification bodies of a
  protected observation, which no libcoap run can cover for the same reason.

```bash
docker build -t kcoap/libcoap:v4.3.5b interop/libcoap
./gradlew :kcoap-oscore:jvmTest --tests '*LibcoapOscoreInteropTest*'
```

### EDHOC against libedhoc

`kcoap-edhoc/src/jvmTest/.../LibedhocInteropTest.kt` runs with every `./gradlew jvmTest` when Docker and
the `kcoap/libedhoc:0363bd3` image are present, and is skipped otherwise. The image builds libedhoc at the
pinned commit `0363bd3af02265e919e888b60ccffb4606b68f16` with its vendored mbedTLS, liboqs, XKCP and
zcbor and its own test suite, and adds `edhoc_peer` (`interop/libedhoc/peer/`), built in libedhoc's build
tree against its reference cipher suites.

```bash
docker build -t kcoap/libedhoc:0363bd3 interop/libedhoc
docker run --rm kcoap/libedhoc:0363bd3 ctest --test-dir /build --output-on-failure   # libedhoc's own tests
./gradlew :kcoap-edhoc:jvmTest --tests '*LibedhocInteropTest*'
```

`edhoc_peer` runs one EDHOC session per process, as Initiator or Responder, and talks a line protocol on
stdin and stdout (`docker run -i --rm kcoap/libedhoc:0363bd3 edhoc_peer --role responder --method 3
--suite 2 --cid 18 --key p256-dh:… --own-kid 32 --own-cred … --peer-kid 2b --peer-cred … --peer-key …`).
The options and the protocol are documented in the source header; in short:

| Direction | Line | Meaning |
|---|---|---|
| in | `msg <hex>` | the next message from kcoap: an EDHOC message or an error message |
| out | `msg <hex>` | a message for kcoap: message_1..4, or an error message |
| out | `peer_error <code> [<s>,<s>…]` | an error message arrived (SUITES_R for code 2) |
| out | `retry <suite>` | the Initiator got error code 2 and sends a new message_1 with this suite selected |
| out | `failed <ret> <code>` | processing failed (libedhoc return value); the error message follows as `msg` |
| out | `result <name> <hex>` | after completion: `suite`, `c_i`, `c_r`, `oscore_secret`, `oscore_salt`, `sender_id`, `recipient_id`, `exporter`, `prk_out_check`, `exporter_updated`, `prk_out_check_updated` |
| out | `done` | the session completed |

The test hands the peer its private key, its credential, and the credential and public key it must accept,
all generated fresh per session; ephemeral keys are random on both sides. The peer moves libedhoc's log
(which libedhoc writes to stdout) to stderr; the protocol transcript and that log are written to
`kcoap-edhoc/build/interop-logs/` when a session fails. A session passes when libedhoc and kcoap agree on
the selected suite, C_I and C_R, the OSCORE Master Secret and Salt, the Sender and Recipient IDs of RFC 9528
Appendix A.1, EDHOC_Exporter(32768, "kcoap", 32), EDHOC_Expand(PRK_out, "kcoap-prk-out-check", 32) (PRK_out
itself stays in libedhoc's non-exportable key slot), and the last two again after EDHOC_KeyUpdate.

What interoperates (every row in both roles: kcoap Initiator with libedhoc Responder, and the reverse):

| Suite | Methods | Credentials | Also covered |
|---|---|---|---|
| 0 (Ed25519/X25519, AES-CCM-16-64-128) | 0, 1, 2, 3 | CCS by `kid`; Ed25519 `x5chain` (kcoap Initiator) | EAD padding and non-critical items to libedhoc |
| 2 (P-256, AES-CCM-16-64-128) | 0, 1, 2, 3 | CCS by `kid` | negotiation with error code 2 both ways; tampered message_2/3 and error code 1 both ways |
| 4 (X25519/Ed25519, ChaCha20-Poly1305) | 0, 1, 2, 3 | CCS by `kid`; Ed25519 `x5chain` (libedhoc Initiator) | |
| 24 (P-384, A256GCM, SHA-384) | 0, 1, 2, 3 | CCS by `kid` | negotiation from 24 down to 2 |
| -24 (ML-KEM-512, ML-DSA-44, SHAKE256) | 0 | CCS with an AKP `COSE_Key`; ML-DSA-44 `x5chain` | Bouncy Castle on kcoap's side, liboqs on libedhoc's |

For each suite and method, message_4 is on in one role and off in the other, and integer and byte-string
connection identifiers and kids alternate across the matrix. Method 3 on suite -24 ends in error code 1
in both directions (static-DH methods need DH, draft-ietf-lake-pqsuites §6.3). libedhoc does not
implement suites 1, 3, 5, 6 or 25, ML-KEM-768/1024 or ML-DSA-65/87.

Deviations found, all on libedhoc's side, each pinned by an `upstream…` test that asserts libedhoc's
current behaviour (so it fails, and gets flipped, once libedhoc is fixed):

1. **The Responder supports only its last configured suite and skips the prior-suite check.**
   `edhoc_message_1_process` (`library/core/classic/edhoc_classic_message_1.c`, steps 2 and 3b) compares
   the selected suite with the last entry of `edhoc_set_cipher_suites` only. Configured with [0, 2], it
   accepts SUITES_I = [0, 2] (2 selected) although RFC 9528 §5.2.3 step 2 and §6.3.1 require error code 2
   because suite 0, listed earlier, is supported; and it refuses SUITES_I = 0 although 0 is configured,
   contrary to its own API documentation ("the Responder checks the selected suite against those it
   supports"). Single-suite Responders, the common configuration, are unaffected.
   Test: `upstreamLibedhocResponderSupportsOnlyItsLastSuiteAndSkipsThePriorSuiteCheck`.
2. **Unknown critical EAD items are accepted when no EAD callback is bound.** Without `edhoc_bind_ead`,
   message processing skips EAD entirely (`edhoc_classic_message_1.c` step 4 runs only when
   `ead.process` is set), so a message_1 carrying EAD_1 with label -5 completes. RFC 9528 §3.8: an
   endpoint receiving a critical EAD item it does not recognize "MUST send an EDHOC error message back"
   and abort. With a callback bound, `include/edhoc/ead.h` leaves the criticality rule to the callback.
   Test: `upstreamLibedhocIgnoresUnknownCriticalEadWithoutAnEadInterface`.
3. Robustness, not an interop failure: methods 1–3 with the KEM suite -24 are not refused up front (the
   suites' `supports_dh_nike` flag is never read by libedhoc's core). Its Initiator sends such a
   message_1, and its Responder accepts it and fails only while composing message_2 ("ML-KEM suite does
   not support static Diffie-Hellman"). Test: `staticDiffieHellmanMethodsOnTheKemSuiteAreRefused`.

No deviation from RFC 9528 or draft-ietf-lake-pqsuites-02 was found on kcoap's side.

### Planned

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
- **libedhoc deviations** from RFC 9528 (Responder suite selection, critical EAD without a callback):
  see "EDHOC against libedhoc" above.
- **libcoap has no EDHOC**; our application glue provides `/.well-known/edhoc` and the combined request.
- **Hop-Limit**: libcoap processes it as Outer (Class U), as draft-ietf-core-oscore-capable-proxies
  specifies; the published RFCs still make it Class E. See `docs/rfc-compliance.md`.

## Layout

One container image per peer, pinned to a release or commit: `interop/libcoap/` (libcoap v4.3.5b) and
`interop/libedhoc/` (libedhoc 0363bd3, with the `edhoc_peer` line-protocol peer in `peer/`). Planned:
`interop/libcoap-oracle/` (scripts and configs for the byte-exact OSCORE runs).
