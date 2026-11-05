<!--
  Plan for Q-Block (RFC 9177) and first-class platform use, written on 2026-11-05 from research on the
  RFC text, libcoap 4.3.5b (source and live runs) and the kcoap code, each part critiqued by a second
  reviewer. Status: plan. The questions in section 4 were answered with their recommended defaults.
-->

# kcoap: plan for Q-Block (RFC 9177) and first-class platform use

This plan covers Q-Block with libcoap interop, and first-class use on Android, iPhone, JVM servers and browsers. It is based on `feat/protocol-core` at `3a13fd1` (it contains `3e28315` js/wasmJs and `f0dc39d` RFC 8323). I re-read the merged sources the plan touches: `BlockwiseClient`, `BlockwiseServer`, `stack/Blockwise.kt` (BERT, `MessageLimits`), `CoapEndpoint`, `ClientExchange`, `ConnectionDriver`, `UdpMessaging`, `ObservationRegistry`, `ClientObservation`, `CoapServer`, `CoapClient`, `OptionCheck`, `TransmissionParameters` and `OscoreEndpoints`. I also read `docs/target-architecture.md` and `docs/checklist.md`, and spot-checked libcoap (`-L` help text, `COAP_RBLOCK_CNT 4`, `MAX_PAYLOADS 10`). Nothing in the repository was changed and Gradle was not run.

**Path shorthand** (paths are relative to the repository root):
- `ROOT` = the repository root
- `KC` = `ROOT/kcoap-coap/src/commonMain/kotlin/dev/kcoap/coap`
- `KT` = `ROOT/kcoap-coap/src/commonTest/kotlin/dev/kcoap/coap`
- `KJ` = `ROOT/kcoap-coap/src/jvmTest/kotlin/dev/kcoap/coap/interop`
- `KO` = `ROOT/kcoap-oscore/src/commonMain/kotlin/dev/kcoap/oscore`

**Research artifacts**: the raw-datagram probe scripts and libcoap logs behind the findings below were kept outside the repository; the libcoap behaviours they show are pinned as tests by QB-3 and QB-4.

---

## 1. Q-Block (RFC 9177)

### 1.1 Requirements summary

The critiques' corrections are already applied in this table. "Policy" marks a kcoap decision where the RFC is silent or libcoap forces one.

| # | RFC | Level | Role | Requirement | kcoap rule |
|---|---|---|---|---|---|
| 1 | 9177 §4.1 | MUST | both | Support both Q-Block1 and Q-Block2, or neither | One switch per side enables both |
| 2 | §4.1; 7252 §5.4.1 | MUST | srv | Q-Block not supported: a CON request gets 4.02; a NON request is rejected or dropped | Today's behaviour stays the default; pinned in QB-0 |
| 3 | 7252 §5.4.1 | MUST | cli | Q-Block disabled: a response with option 19 or 31 is rejected | `BAD_RESPONSE`; a CON separate response gets an RST, sent only after the option check. Today the endpoint ACKs first (`CoapEndpoint.kt:528`); fixed in QB-2 |
| 4 | §4.1 | MUST | cli | Signal support: Q-Block2 in GET/FETCH; Q-Block1 or Q-Block2 in payload requests | Every request to a supporting peer carries Q-Block2(0, M=1, SZX) or Q-Block1 |
| 5 | §4.1 | REQUIRED | cli | Find out whether a peer supports Q-Block with a Confirmable message | Separate CON probe `GET /.well-known/core` with Q-Block2(0/0/16), the same probe libcoap sends; result cached per (peer, protected) |
| 6 | §4.1; 7252 §5.4.5 | MUST | both | Only Q-Block2, and only in a missing-block request, may repeat | `QBlockCheck`: a repeated Q-Block1 gets 4.02; a bad Q-Block2 list gets 4.00 (row 22) |
| 7 | §4.1 | MUST | both | No Q-Block mixed with Block in one layer; such a request gets 4.02, NON included; such a response is rejected | 4.02 piggybacked for CON, NON 4.02 for NON. The client never mixes them, including after a probe timeout (libcoap run rv/B) |
| 8 | §4.2; 7959 §2.2; 8323 §6 | MUST | both | Option is a uint of 0–3 bytes; SZX 7 is reserved on UDP | Reuse `BlockOption`. SZX 7 over UDP gets 4.00. Policy: accept SZX 7 only on a connection whose CSMs both indicated BERT (libcoap may send it); never send it |
| 9 | 7959 §2.3 | MUST | both | With M=1 the payload is exactly the block size | Server answers 4.00 (policy, as libcoap); client drops the block |
| 10 | §4.3; 9175 §3.4 | MUST | cli | A Request-Tag on every Q-Block1 request: the same within a body, unique across bodies | 4-byte counter with a random start, never reused while the client lives. `BlockwiseClient.acquireTag` (`BlockwiseClient.kt:206`) is not used |
| 11 | §4.6 | MUST | both | Exact Size1 on every Q-Block1 request, exact Size2 on every Q-Block2 response | Enforced on send; checked on receipt |
| 12 | §4.3 | MUST | srv | A Q-Block1 request without Request-Tag or Size1 gets 4.00 | libcoap does not do this; a raw-datagram test covers it |
| 13 | §4.3 | MUST/SHOULD | srv | Final response on a token of the body, preferably the last one received | The token of the request that completed the body |
| 14 | §4.3, §7.2 | SHOULD / MUST NOT | srv | 2.31 once per complete NON set, never per block, never for CON | Policy: the 2.31 always carries Q-Block1(last NUM of the set, M=1, SZX). libcoap's client truncates the upload without it (f-231-no-opt) |
| 15 | §4.3, §5 | MUST | srv | 4.08 with Content-Format 272: a CBOR Sequence of uints, ascending, unique, fitting one packet, on a token of the body | At most MAX_PAYLOADS numbers; always NON (libcoap ignores a CON 4.08); token of the highest block received or the last token |
| 16 | §5 | MUST / SHOULD | cli | Ignore duplicates in a 4.08; drop a list that is not ascending | Drop when the list is empty, not all uints, or decreases; remove adjacent duplicates; ignore NUM ≥ the block count |
| 17 | §4.3 | MUST | srv | A duplicate block is ignored but answered as if it were the first | Keep a record of completed bodies (key → final response) for NON_PARTIAL_TIMEOUT |
| 18 | §4.3 | MUST / SHOULD | both | A client that abandons a body forgets all its tokens; the server deletes the partial body when a Reset answers its 4.08 | Implemented on both sides |
| 19 | §4.3 | SHOULD | cli | Release the tokens on 2.31 and on the final response | Policy: keep the token of the latest 2.31 until the body ends (the server may send a timer 4.08 on it) |
| 20 | §4.4 | RULE | srv | Meaning of M in a Q-Block2 request: M=0 is that block only; 0/M=1 is the whole body; k×MAX/M=1 (k>0) is Continue; any other NUM with M=1 is the rest of its set | Classified in `QBlock2Sender` |
| 21 | §4.4 | MUST | srv | Send each requested block once, even when the requested blocks overlap | Expand M=1 entries, take the union, sort |
| 22 | §4.4 | MUST / SHOULD | both | Q-Block2 lists strictly ascending, one SZX; otherwise 4.00 | The client builds lists that way; the server answers 4.00 |
| 23 | §4.4 | MUST | srv | The same ETag on every block of a body, unique for every transmitted body | Per-body 4-byte counter with a random start; replaces an application ETag (Q5) |
| 24 | §4.4 | SHOULD / MUST | srv | Cache the body for missing-block requests. If the resource changed and nothing is cached, answer with the new ETag and Size2 | `ServedBody` cache. With no cache: GET/FETCH run the handler again; unsafe methods get a plain 4.08 |
| 25 | §4.4 | MUST / SHOULD | cli | Ignore a duplicate block with the same ETag. Send no ETag in follow-ups. Keep a partial body for min(NON_PARTIAL_TIMEOUT, Max-Age) | Policy: lifetime = max(min(247 s, Max-Age), the 124 s retry ladder). Otherwise a Max-Age of 60 s or 0 makes recovery impossible |
| 26 | §4.5 | MUST | both | The same Observe value on every block of a body, including after a Continue; none on missing-block requests or their responses | The server always complies. The client accepts blocks without Observe on an observation if their ETag matches the body in progress (libcoap omits Observe after a Continue, e6) |
| 27 | §4.7 | RULE | both | Q-Block1 followed by Q-Block2: follow-ups repeat the method, options and Request-Tag, without Q-Block1 and without payload | The server accepts a follow-up without Request-Tag only when exactly one cached body matches (Figure 12) |
| 28 | §4.8 | MUST | srv | Ignore a multicast request carrying Q-Block2 | Not applicable until multicast (coap-core-8) |
| 29 | §7 | MUST | sender | All blocks of a body are CON, or all are NON | Fixed per body |
| 30 | §7.1 | MUST NOT | both | Do not raise NSTART for CON Q-Block | Unchanged; CON bodies run in lock-step at NSTART 1 |
| 31 | §7.2 | SHOULD / MUST | both | MAX_PAYLOADS configurable (default 10) and equal on both peers. NON_RECEIVE_TIMEOUT ≥ NON_TIMEOUT × ACK_RANDOM_FACTOR + 1 s | `TransmissionParameters`, checked in `init` |
| 32 | §7.2 | MUST / MAY | sender | Wait NON_TIMEOUT_RANDOM between sets, drawn once per body; a Continue may cut the wait short | Drawn from `CoapEndpointConfig.random` |
| 33 | §7.2 | RULE | sender | PROBING_RATE applies per body. Each NON 4.08 and NON Q-Block2 request counts. The wait between bodies is capped at NON_PROBING_WAIT | `BodyLease`: one NSTART slot per NON body |
| 34 | §7.2 | SHOULD | receiver | Retry ladder NON_RECEIVE_TIMEOUT × 2^(n−1), each wait counted from the previous re-request; give up after NON_MAX_RETRANSMIT | 4, 12, 28 and 60 s, then discard at 124 s (Figure 6). Base = max(last block received, last re-request) |
| 35 | §7.2 | SHOULD | receiver | A block of a later set while earlier sets have gaps triggers an immediate 4.08 or missing-block request | Both sides. libcoap's server does not; its client does |
| 36 | §7.2 | SHOULD | srv | A Continue sends the set starting at NUM on the original token, at once | Even if those blocks were already sent (Figure 10). A repeated Continue within the same NON_TIMEOUT_RANDOM window is ignored |
| 37 | §7, App. B | RULE | both | Reliable transports: no sets, gaps, 2.31, CF-272 4.08 or timers; blocks back to back | The server marks intermediate requests answered, so Custody and Release are not stuck |
| 38 | §11; 9175 §2.4, §2.6 | NOT REC. / SHOULD | both | NoSec Q-Block is not recommended; mitigate amplification towards unauthenticated peers | WARN when NoSec. Echo address check before NON bursts larger than 3× the request bytes (SRV-1) |
| 39 | 7959 §7.1 | SHOULD | srv | Bound the state | Size1 > maxBodySize gets 4.13 with Size1; NUM×size ≥ Size1 or a last block that does not add up gets 4.00; per-peer and global caps give 5.03 with Max-Age |
| 40 | 8613 §7.4, §8.4 | outside RFC 8613 | cli | OSCORE assumes a single response per request | Several responses are allowed only to requests that carried inner Q-Block1 or Q-Block2: each needs a new Partial IV, checked against a per-request replay window (QB-5) |
| 41 | §8, §9 | MUST | proxy | Proxy rules | Not applicable (no proxy, coap-core-7) |

### 1.2 Design

```
CoapClient.request ─► QBlockClient (mode ≠ OFF) ──fallback──► BlockwiseClient (RFC 7959, unchanged)
                        ├ QBlockSupport: probe, capability cache per (peer, protected)
                        └ drives sans-IO cores: QBlock1Sender, QBlock2Receiver
ClientObservation ────► QBlock2Receiver per ETag (bodies of notifications)
CoapServer.answer ────► QBlockServer: PartialBody(QBlock1Receiver), ServedBody(QBlock2Sender),
                        CompletedBodies, NotificationBody;  BlockwiseServer for RFC 7959
CoapEndpoint          ResponseMode.INBOX exchanges, BodyLease (UdpMessaging), ACK after the option check
kcoap-oscore          RequestBinding.allowsSeveralResponses; per-request ReplayWindow
```

**Principle.** The Q-Block rules live in sans-IO state machines: events and `now` go in, a `List<QBlockAction>` and `nextDeadline` come out. This follows `code-style.md` and the `UdpMessaging` precedent. Thin coroutine drivers sit in `client/` and `server/`. Timers are `delay` calls on `endpoint.scope`, so they follow virtual time under `runTest`. Jitter comes from `CoapEndpointConfig.random`.

| File | New or changed | Content |
|---|---|---|
| `KC/stack/QBlock.kt` | new | `QBlockSet` (set arithmetic); `MissingBlocks` (CF 272 codec, hand-written CBOR major type 0, strict, so `kcoap-coap` gets no dependency on `kcoap-cbor`; the tests cross-check it against kcoap-cbor); `QBlockCheck` (mixing, repetition, ordering, SZX, Request-Tag and Size1 rules, returning a `QBlockViolation(code, diagnostic)`); `ReceivedBlocks` (bitset, gaps up to a limit); `QBlockTiming` (resolved parameters and the per-body NON_TIMEOUT_RANDOM draw); `BodyMode { NON, CON, RELIABLE }` |
| `KC/stack/QBlock1Sender.kt`, `QBlock1Receiver.kt`, `QBlock2Sender.kt`, `QBlock2Receiver.kt` | new | sans-IO cores. Actions: `SendBlock(num, more)`, `Send231(ref, num)`, `Send408(ref, nums)`, `SendContinue(num)`, `RequestMissing(nums, mBit)`, `Deliver`, `Fail(reason, response?)`, `Discard` |
| `KC/stack/TransmissionParameters.kt` | changed | Q-Block parameters (table 1.3) |
| `KC/CoapOption.kt` | changed | `ContentFormat.MISSING_BLOCKS_CBOR_SEQ = 272` |
| `KC/OptionSet.kt` | changed | `qBlock1`, `qBlock2` (first), `qBlock2List`; builder `qBlock1()`, `qBlock2()`, `addQBlock2()` |
| `KC/stack/Blockwise.kt` | changed | `operationOptions` also leaves out 19 and 31 (lines 152–157) |
| `KC/stack/ClientExchange.kt` | changed | `ResponseMode { SINGLE, STREAM, INBOX(inbox) }` replaces `isObservation`. `INBOX` pushes `ExchangeEvent` values (Response, Reset, TimedOut, Failed) with `trySend` into a bounded buffer (4×MAX_PAYLOADS + 8); an overflow counts as loss |
| `KC/stack/CoapEndpoint.kt`, `UdpMessaging.kt`, `Transmission.kt` | changed | `openExchange(…, mode, pacing = PerMessage \| Body(lease))`; `leaseBody(peer, bodyBytes)`. A `BodyLease` takes one NSTART slot (queued like a transmission) and releases automatically at min(bytes ÷ PROBING_RATE, NON_PROBING_WAIT) unless the body ends first. A separate CON response is ACKed only after verification and the critical-option check; an unknown critical option gets an RST |
| `KC/client/QBlockClientConfig.kt`, `QBlockSupport.kt`, `QBlockClient.kt` | new | Public config; capability cache and single-flight probe; drivers for upload, download, upload then download, and CON and reliable variants |
| `KC/client/CoapClient.kt`, `CoapClientConfig.kt`, `ClientObservation.kt` | changed | Strategy choice (line 98); `RESPONSE_RECOGNIZED` gains 19 and 31 when enabled (line 371), and a response that mixes Q-Block and Block options is rejected; `qBlock` config; observation body assembly (QB-6) |
| `KC/server/QBlockServerConfig.kt`, `QBlockServer.kt` | new | Public config. `PartialBody` keeps the requests (with their `UnprotectedRequest`) for lastToken, highest NUM and the latest 2.31 only. `ServedBody` keeps the original `InboundRequest` and `UnprotectedRequest`, ETag, SZX, NON_TIMEOUT_RANDOM, set cursor and gap job. `CompletedBodies`. Caches bounded by `ExpiringCache` |
| `KC/server/CoapServer.kt`, `CoapServerConfig.kt`, `OptionCheck.kt`, `ObservationRegistry.kt` | changed | `Reply` becomes sealed: `Single(response, observation?)`, `None` (NON: nothing; CON: empty ACK; reliable: `answered`), `Body(firstBlock, sender, observation?)`. The Q-Block branch runs after OSCORE and before the Block1 branch (line 193); `OptionCheck` takes the recognized set per request; notification bodies (QB-6) |
| `KC/stack/EndpointEvent.kt` | changed | `QBlockRecovery(peer, sent: Boolean, blocks)`, `QBlockFallback(peer, reason)`, `PartialBodyDropped(peer, reason)` |
| `KO/OscoreEndpoints.kt`, `KO/OscoreProtection.kt` | changed (QB-5) | `RequestBinding.allowsSeveralResponses`; per-request `ReplayWindow(32)`; notifications of a Q-Block2 registration accepted by window instead of a strict high-water mark |
| `ROOT/docs/adr/0006-q-block.md` | new | Records: sans-IO cores, separate probe, no per-session stickiness, ETag counter, OSCORE policy, body lease, resending the last block, partial-body lifetime floor, client Q-Block off on reliable transports by default |

**Public API**:

```kotlin
// KC/stack/TransmissionParameters.kt — new parameters, RFC 9177 §7.2 Table 3
public class TransmissionParameters(
    /* existing … */
    public val maxPayloads: Int = 10,
    public val nonTimeout: Duration = ackTimeout,
    public val nonReceiveTimeout: Duration = nonTimeout * 2,
    public val nonMaxRetransmit: Int = maxRetransmit,
) {
    // init: maxPayloads >= 1; nonTimeout > 0; nonMaxRetransmit in 0..20;
    //       nonReceiveTimeout >= nonTimeout * ackRandomFactor + 1.seconds   (RFC 9177 §7.2 MUST)
    public val nonPartialTimeout: Duration  // NON_TIMEOUT×(2^NMR−1)×ARF + 2×MAX_LATENCY + NON_TIMEOUT = 247 s
}

// KC/client/QBlockClientConfig.kt
public enum class QBlockMode { OFF, PROBE, ASSUME_SUPPORTED }
public class QBlockClientConfig(
    public val mode: QBlockMode = QBlockMode.OFF,
    public val probePath: String = "/.well-known/core",
    public val capabilityLifetime: Duration = 1.hours,
    public val onReliableTransports: Boolean = false,
    public val withMessageSecurity: Boolean = false,
    public val missingBlockMBit: Boolean = false,
)
// CoapClientConfig(…, qBlock: QBlockClientConfig = QBlockClientConfig())

// KC/server/QBlockServerConfig.kt
public class QBlockServerConfig(
    public val isEnabled: Boolean = false,
    public val withMessageSecurity: Boolean = false,
    public val onReliableTransports: Boolean = true,
    public val maxPartialBodies: Int = 64,
    public val maxCachedBodies: Int = 64,
    public val maxBodiesPerPeer: Int = 4,
    public val verifyUnauthenticatedPeers: Boolean = true,
)
// CoapServerConfig(…, qBlock: QBlockServerConfig = QBlockServerConfig())
```

### 1.3 Configuration parameters

| Where | Parameter | Default | Note |
|---|---|---|---|
| `TransmissionParameters` | `maxPayloads` | 10 | Must equal the peer's value; not negotiable |
| | `nonTimeout` | ACK_TIMEOUT (2 s) | |
| | `nonReceiveTimeout` | 2 × nonTimeout (4 s) | Validated as in row 31 |
| | `nonMaxRetransmit` | MAX_RETRANSMIT (4) | |
| derived | `nonPartialTimeout` | 247 s | libcoap computes about 264 s |
| internal | NON_TIMEOUT_RANDOM | uniform in [2 s, 3 s], once per body | libcoap uses [2, 2.5] s |
| internal | NON_PROBING_WAIT | 245 s + NON_TIMEOUT_RANDOM, so 247–248 s | Caps the BodyLease |
| `QBlockClientConfig` | as in the code above | | With `security != null` and `withMessageSecurity = false`, Q-Block is off for that whole client |
| reused | `CoapClientConfig.blockSize` / `maxResponseBodySize` | null / 1 MiB | SZX fixed per body; Size2 checked before allocating |
| `QBlockServerConfig` | as in the code above | | `completedBodies` lifetime = nonPartialTimeout |
| reused | `CoapServerConfig.maxBlockSize`, `maxBodySize`, `securityReserve`, `nonConfirmableNotificationInterval` | 1024, 1 MiB, 64, 3 s | One notification body counts as one notification |

### 1.4 Behaviour (corrected algorithms)

**Client (`QBlockClient`).**
1. **Choosing the mechanism.** Use BlockwiseClient when any of these holds:
   - the mode is OFF;
   - the peer is on a reliable transport and `onReliableTransports` is false;
   - message security is configured and `withMessageSecurity` is false;
   - the request carries No-Response;
   - the peer's capability is UNSUPPORTED.

   If the capability is UNKNOWN in PROBE mode, probe first.
2. **Probe.** CON `GET probePath` with Q-Block2(0, 0, SZX 0). It goes through `exchange()`, so a 4.01 with Echo is answered once.
   - The response carries Q-Block1 or Q-Block2: SUPPORTED.
   - 4.02, RST, a Block2 response, or any other response: UNSUPPORTED for `capabilityLifetime`.
   - Timeout: UNKNOWN. This request uses RFC 7959, and the probe is retried after min(60 s, lifetime).
   - The cache entry is invalidated on SessionEnded, on connection close, on a 4.02 or RST to a Q-Block message, and when a NON body gets no response at all.
3. **Q-Block1.**
   - One SZX for the body: header + Q-Block1 (3) + Size1 (4) + Request-Tag (4) + Observe (3) + reserve. If not even 16 bytes fit, fail with `MESSAGE_TOO_LARGE`. A body that fits one message gets no Q-Block1.
   - **NON.**
     - Sets of `maxPayloads` blocks, each block a new token. Every token goes to the body inbox in INBOX mode.
     - **2.31.** Its NUM, or if it has none, the NUM sent on that token, marks every block up to it as received. If it ends the current set, the next set goes out at once.
     - **4.08 with CF 272.** Decode it (row 16), resend the listed blocks in ascending order (at most `maxPayloads`, new tokens, the same Request-Tag, Size1, NUM/M/SZX, Observe and Content-Format), then send the rest of the current set, then wait NON_TIMEOUT_RANDOM.
     - **4.01 with Echo.** Resend that block with Echo, and keep Echo on later blocks. Every block sent before the first challenge is resent once when challenged; a challenge to a block that carried Echo ends the body.
     - **Errors.**
       - 4.08 without 272, 4.00, 4.02, 4.13 or 5.xx: stop, release everything, return that response.
       - 4.13 that names a smaller size: restart once with that size and a new Request-Tag.
       - RST: `BLOCKWISE_FAILED`, and the capability is invalidated.
     - **Silence after the last block.**
       - PUT, FETCH, iPATCH: resend the last block at NON_RECEIVE_TIMEOUT × 2^k, measured from the previous resend, at most `nonMaxRetransmit` times, then `RESPONSE_TIMEOUT`.
       - POST, PATCH: `RESPONSE_TIMEOUT` after 124 s with no resend, since repeating them is not safe.
     - **Guard.** The same NUM requested more than nonMaxRetransmit + 1 times fails the body.
   - **CON.** Lock-step under NSTART, empty ACKs, the final response on any token of the body.
   - **Reliable.** Blocks back to back; only the final response matters.
4. **Q-Block2.**
   - **Request.** Q-Block2(0, M=1, SZX). The exchange is in INBOX mode.
   - **Acceptance.** A block is accepted only if all of these hold:
     - exactly one Q-Block2, and no Block1 or Block2;
     - exactly one ETag;
     - Size2 present and ≤ `maxResponseBodySize`;
     - SZX is not 7 on UDP, and NUM < the block count;
     - M=1 implies the payload equals the block size; M=0 only on the last NUM, whose length adds up to Size2;
     - Content-Format, Size2 and SZX match the body.
   - **New ETag.** A block with a different ETag makes the old partial body stale. GET/FETCH start the new body; other methods fail. A missing ETag where the body has one counts as a change (this is libcoap re-running the handler).
   - **Duplicates.** A NUM already received is ignored.
   - **Continue.** When set s is complete and no Continue has gone out for it, send Continue = the original method and options with the Request-Tag, without Observe, ETag, payload or Q-Block1, on a new token, with Q-Block2((s+1)×MAX, M=1, SZX). The continuation is accepted on any token of the body. The Continue itself is never timed out.
   - **Missing blocks.**
     - Requested at once when a later set has started, otherwise when the timer fires (row 34).
     - The request carries one Q-Block2(NUM, M=0) per missing block, ascending, at most `maxPayloads`, fitting one datagram. With `missingBlockMBit`, a missing tail of a set is requested as a single M=1 option.
     - If block 0 is missing, GET and FETCH request 0/M=1 again (libcoap re-runs the handler for NUM 0); other methods list 0 as the RFC allows.
   - **Giving up.** More than `nonMaxRetransmit` retries makes the body stale: a non-Observe request fails, an observation waits for the next notification.
   - **Delivery.** Code and options of block 0 without Q-Block2 and Size2 (ETag kept), plus the concatenated payload.
5. **Pacing.**
   - A NON body takes one BodyLease from its first message to its end. Blocks, Continue and missing-block requests go out with no slot hold of their own.
   - A CON body uses per-message NSTART and no lease (a lease plus CON blocks would deadlock at NSTART 1).
   - Tokens are kept until the body ends. Confirmed tokens are released, except the token of the latest 2.31 and an observation token.

**Server (`QBlockServer`), after message security.**
1. **Admission.**
   - The rules of rows 6–8, 11, 12 and 39 apply.
   - A request outside what is allowed (protected with `withMessageSecurity` off, or reliable with `onReliableTransports` off) keeps today's 4.02 or reject.
   - **Amplification.** Before a burst of more than 3× the request bytes to an unauthenticated, unverified peer on UDP, answer with a 4.01 carrying Echo, piggybacked or NON, never as a separate CON (SRV-1). The probe answer (one 16-byte block) stays under 3× and is never challenged.
2. **Q-Block1.**
   - **Key and state.** `OperationKey`, with Request-Tag and Content-Format in it and 19, 31, Size1/Size2/Echo and Observe out of it. State per body: size1, szx, observe, type, received bitmap, the three retained requests, retry, lastActivity, last408At.
   - **Completion.** Hand the assembled request (without Q-Block1 and Size1) to the handler. Answer on the completing request's token. Record key → final response.
   - **NON, before completion.**
     - A block of a later set while earlier gaps remain: 4.08 at once.
     - Every set up to k complete and k not the last: one 2.31 carrying Q-Block1.
     - Timer at max(lastActivity, last408At) + NON_RECEIVE_TIMEOUT × 2^retry: a 4.08 with the gaps, or, after a 2.31 with nothing since, the next set's numbers. It goes on a token the client still holds: the highest block's, or the latest 2.31's until a higher block arrives (ADR 0006).
     - Retry above `nonMaxRetransmit`: discard the body.
     - A Reset answering a 4.08: discard the body.
   - **CON.** `Reply.None` (empty ACK) per block; no 2.31 and no CF-272 4.08.
   - **Reliable.** `finishWithoutResponse` (`answered`) per intermediate block.
3. **Q-Block2.**
   - **When.** Used when the request carried Q-Block1 or Q-Block2 and the response is a 2.01, 2.02, 2.04 or 2.05 with a payload. Q-Block2 is echoed even for a body of one block. There is no per-session stickiness.
   - **SZX.** min(requested, maxBlockSize, the size that fits next to ETag (4), Q-Block2 (3), Size2 (4), Observe (3), Content-Format, Max-Age and the reserve). If 16 bytes do not fit, answer 4.13 without Size1.
   - **NON.** Block 0 is the `Reply`; blocks 1–9 follow on the same request with `respond()`, which sends a fresh NON each time, and OSCORE gives every response after the first a new Partial IV. The next set goes out after NON_TIMEOUT_RANDOM or on a Continue.
   - **Requests carrying Q-Block2.**
     - Continue: rows 20 and 36; the Continue's own token gets `Reply.None`.
     - 0/M=1: GET/FETCH run the handler again; unsafe methods are served from the cache when the Request-Tag matches.
     - Missing-block request: rows 21 and 22, answered on that request's token, with its type, without Observe, at most `maxPayloads` then a NON_TIMEOUT_RANDOM gap. Nothing cached: row 24.
   - **Reset.** An RST to any block stops that body.
   - **CON.** Block 0 piggybacked; each next block goes out as CON on the ACK of the previous one; a timeout ends the transfer but keeps the cache.
   - **Reliable.** All blocks back to back.
4. **Teardown.** SessionEnded or a closed connection drops the peer's partial bodies, cached bodies and timers; `close()` cancels everything.

**Observe (QB-6).**
- The server's `ServerObservation` remembers a Q-Block2 registration and its SZX.
- Each change produces one body with a new ETag and the next Observe value on every block, all CON or all NON.
- The body is the one outstanding notification until its last block has gone out, and in NON mode for at least `nonConfirmableNotificationInterval`.
- Changes in between are folded into the next body. A new body cancels the old body's pending sets.
- No `replaceOutstanding` inside a body.
- A Reset to any notification block removes the observer.
- The client's `ClientObservation` assembles per ETag and runs `NotificationOrder` once per body, on the first block of a new ETag. Under OSCORE it uses the Observe value the OSCORE layer derives from the Partial IV.
- Late blocks of a superseded ETag are ignored.
- A Q-Block1 FETCH registration takes the token of the 2.05 as the observation token, wherever it falls in the body (Figure 14). It deregisters with FORGET, because a re-sent body would break Request-Tag uniqueness.

**OSCORE (QB-5).**
- **Client, non-Observe request with inner Q-Block1 or Q-Block2.** Several responses are allowed; at most one may lack a Partial IV; the others must carry one that is new in the per-request `ReplayWindow(32)`. Requests without Q-Block keep today's single-response rule (`OscoreEndpoints.kt:130-131`).
- **Client, observations registered with Q-Block2.** The window replaces the strict high-water mark at line 143; the Observe rewrite at line 148 stays.
- **Server.** The original `UnprotectedRequest` stays in the `ServedBody` and `PartialBody` entries. A new Partial IV after the first response already happens (line 203).
- **libcoap 4.3.5b** cannot protect or decrypt burst responses. With the defaults, protected requests therefore use inner Block1/Block2.

### 1.5 Test plan

**Sans-IO unit tests** (`KT/stack/QBlockPartsTest.kt`, `QBlock1SenderTest.kt`, `QBlock1ReceiverTest.kt`, `QBlock2SenderTest.kt`, `QBlock2ReceiverTest.kt`, commonTest on every target):
- CF-272 codec: `01 09` gives [1, 9]; `09 01` is dropped; `01 01 09` gives [1, 9]; an empty payload, a non-uint or a truncated sequence is dropped; 46 encodes as `18 2e`; numbers above 0xFFFFF are dropped; the encoding round-trips against kcoap-cbor.
- `QBlockCheck`: every row 6–12 case.
- Parameters: 247 s; NON_TIMEOUT_RANDOM within [2, 3] s and drawn once; bad combinations throw `IllegalArgumentException`.
- Each figure below as an event script: exact NUM, M and token indices and exact virtual times.

**End-to-end on virtual time** (`KT/QBlockTest.kt`, `QBlockObserveTest.kt`, `QBlockReliableTest.kt`):
- `StackFixture` with `network.filter` for exact drops (decode the datagram; drop by direction, NUM and attempt) and `traffic` timestamps for timing assertions.
- `InMemoryConnections` for reliable transports.
- Also in this suite:
  - **Soak.** 20 seeds × 10 % and 30 % loss × bodies of 3000 B and 20 KiB, in both directions. Each run ends with an equal body or a clean error; afterwards no exchanges are left and the server holds no state.
  - **Fallback.** Server off: the probe gets 4.02 and RFC 7959 is used. RST to a NON. Probe timeout.
  - **Lease.** NSTART 1, two NON bodies: the second waits for the first. A silent peer delays the next body by min(bytes ÷ 1 B/s, 247–248 s). CON bodies do not deadlock.
  - **Limits and amplification.** 4.13 with Size1, 5.03 caps, the Echo gate.
  - **NAT rebinding.** A new testkit `RebindingTransport` (two attached addresses, switching at a chosen datagram): the server opens a new partial body, asks for the earlier blocks with a 4.08, and the client completes.
  - **Abandoned body.** Forgotten tokens: a late 4.08 gets an RST and the server deletes the partial body.

| RFC 9177 figure | Test name prefix `rfc9177Figure…` | Key assertions |
|---|---|---|
| 2 | `2NonQBlock1WithoutLoss` | 4 NON blocks with one Request-Tag, Size1 on each; no 2.31; 2.04 on the last token; all tokens released |
| 3 | `3ContinueSkipsThePause` | 2.31 on block 9's token carrying Q-Block1 9/1; block 10 sent with Δt = 0 |
| 4–5 | `4And5RecoveryAcrossSets` | Drop 1, 9, 10. 4.08 [1, 9] at once on block 11's token; resend 1 and 9, then 12; after 4 s a 4.08 [10]; 2.04 on the resent 10's token |
| 6 | `6EventualFailure` | 4.08 [1] at +4, +12, +28 and +60 s on one token; discard at +124 s with no fifth; the client accepts 4 responses on one token |
| 7 | `7ObserveSameOnEveryBlock` | One Observe per body; one emission per body; new ETag per body |
| 8 | `8ContinueOnOriginalToken` | Continue 10/1 without Observe; continuation on T:f0 with Observe; nothing on the Continue token; no pause |
| 9 | `9MissingAfterLaterSet` | Immediate [1, 9] on block 10; a lost answer leads to [1] after NON_RECEIVE_TIMEOUT |
| 10 | `10MBitRecoveryAndResentSet` | QB2 2/1 gives blocks 2–9 on T:f5 without Observe; Continue 10/1 sends block 10 again on T:f0 |
| 11 | `11FetchObserveToken` | Observation token = the 2.05's token; the other tokens released |
| 12 | `12ContinueWithoutRequestTag` | Accepted when unambiguous; refused when two bodies match (the refusal needs the server's body matching and is tested in `QBlockServerTest` under QB-3) |
| 13–15 | `13To15LossBothWays` | Final response on T:c3; lost last block found from Size2; fresh MIDs (Figure 15 reuses 0xa7) |
| 16–17 | `16And17ConQBlock1` | No 2.31; NSTART 1 lock-step (the NSTART 3 variant with custom parameters); same MID and token on retransmission; MAX_RETRANSMIT failure |
| 18 | `18ConQBlock2` | Block 0 piggybacked, CON blocks ACKed, Observe on every block |
| 19–20 | `19And20Reliable` | Only the final response; intermediate requests answered (a Custody Pong is not held up); no Continue and no timers |

**libcoap interop.**
- Files: new `KJ/KcoapClientAgainstLibcoapQBlockTest.kt` and `KJ/LibcoapClientAgainstKcoapQBlockTest.kt`; OSCORE cases in `ROOT/kcoap-oscore/src/jvmTest/kotlin/dev/kcoap/oscore/StackOscoreInteropTest.kt`.
- Harness additions in `KJ/Libcoap.kt`:
  - `wire`: the `v:1` / `v:Reliable` / `v:WebSocket` line right after each `netif: sent|recv` line;
  - read logs before stopping the container;
  - put `-L` before `-P` or `-f`.
- Kcoap-side loss: a jvmTest `DroppingTransport` around `KtorUdpTransport`.
- Test files: `/data/big3000.txt` (47 blocks of 64 B).
- `$P` comes from `Libcoap.startServer`, `$D` is the mounted directory, `$C` = `docker run --rm --network host -v $D:/data kcoap/libcoap:v4.3.5b coap-client`.
- Every test compares body bytes, because libcoap's client can exit 0 with a truncated body.

| ID | libcoap side | Expectation |
|---|---|---|
| **kcoap client → libcoap server** | | |
| K1 | `coap-server -A 127.0.0.1 -p $P -L 7 -v 7` | kcoap PROBE, NON GET `/example_data`, block size 64: probe gives SUPPORTED; 24 blocks, each with ETag 0x01, Size2 1500 and Content-Format 0; exactly two Continues (10/M/64, 20/M/64) without Observe or ETag, Request-Tag as in the first request; blocks on kcoap's original token; under 1.5 s |
| K2 | same + `-l 4,9` | One NON GET with QB2 2/_/64 and 7/_/64 when block 10 arrives. `-l 2` (block 0 lost): kcoap sends 0/M/64 again, never 0/_/64. `-l 5-25` with `missingBlockMBit`: QB2 3/M/64, the server sends 3–9 |
| K3 | as K1 | NON PUT of 3000 B: 2.31 with Q-Block1 9, 19, 29, 39, each followed at once by the next set; final 2.01/2.04 without Q-Block1; a later Q-Block2 GET returns the same bytes; under 1.5 s |
| K4 | as K1; kcoap drops its first send of blocks 2 and 7 | No 2.31; sets about 2–3 s apart; about 4 s after the last block a NON 4.08 with CF 272 `02 07` on block 46's token; resend; 2.04. Variant drop 1, 3, 5: immediate plain 4.08 "Too many missing blocks", returned to the caller with no hang and no leaked tokens |
| K5 | `coap-server -A 127.0.0.1 -p $P -v 7` (no `-L`) | Piggybacked 4.02 to the probe gives UNSUPPORTED; GET and PUT complete with Block2/Block1; no NON Q-Block datagram on the wire |
| K6 | `… -L 7 -N -v 7`; trigger: `$C -L 7 -N -b 64 -m put -f /data/big3000.txt coap://127.0.0.1:$P/example_data` | Two bodies (ETag 0x01 with Size2 1500, ETag 0x02 with Size2 3000); Continues at 10–40; blocks without Observe accepted; exactly one emission per body; deregistration with Observe 1 on the same token |
| K7 | as K1 | CON GET and PUT: separate CON blocks ACKed; no Continue, no 2.31 expected |
| K8 | as K1; kcoap drops block 2 twice | The second missing-block request comes after libcoap's 8 s cache expiry; the answer has no ETag or Size2; kcoap restarts the GET and never mixes the two representations |
| K9 | (a) as K1; (b) `… -L 7 -e -v 7` | (a) NON FETCH with a 700 B body: 2.31 Q-Block1 9, then the 2.05 Q-Block2 body on the last Q-Block1 token. (b) PUT of 3000 B with echo: kcoap fetches blocks 1–2 with a PUT carrying only Q-Block2 and the Request-Tag. If libcoap fails, pin it as an `upstream…` test |
| K10 | `… -L 7 -w $W -v 7` | `onReliableTransports = true`, block size 64, coap+tcp and coap+ws: blocks back to back, no 2.31 and no Continue. Without a set block size libcoap sends a single message (rv/D) |
| K11 | `… -L 7 -E /data/server.conf -v 7` | Defaults: protected requests use inner Block1/Block2 and pass. With `withMessageSecurity = true`: `upstreamLibcoapCannotProtectQBlock2Bursts`, expected to fail, pinned |
| K12 | `… -L 7 -v 7 -l 1` | The probe's ACK is lost: kcoap retransmits the CON probe and gets SUPPORTED; no message mixes Block2 and Q-Block2 |
| **libcoap client → kcoap server** (Q-Block on; `verifyUnauthenticatedPeers = false` except in L12) | | |
| L1 | `$C -L 7 -v 7 -B 10 coap://127.0.0.1:$P/` | Piggybacked 2.05 with Q-Block2 0/M/16, ETag and Size2; the log shows "Q-Block support available" |
| L2 | `$C -L 7 -N -b 64 -v 7 -B 30 -o /data/out.bin coap://127.0.0.1:$P/big` (3000 B) | Sets of 10, ETag and Size2 on every block; Continues 10–40 answered on the original token; out.bin equal; under 1.5 s. Variants: `-L 15`, and no `-b` (record whether libcoap completes with Block2 from a server without stickiness) |
| L3 | as L2; kcoap drops blocks 2 and 7 | One request [2, 7] when block 10 arrives, answered on its token with ETag and Size2, without Observe. Variant drop 3–9 with `-L 15`: QB2 3/M answered with 3–9 only |
| L4 | `$C -L 7 -N -b 64 -v 7 -B 30 -m put -f /data/big3000.txt coap://127.0.0.1:$P/store` | No response to intermediate blocks; 2.31 with Q-Block1 9, 19, 29, 39; final 2.04; all 47 blocks in under 1.5 s; same Request-Tag and Size1 throughout; stored bytes equal |
| L5 | L4 + `-l 4,9`; variant `-l 48` | NON 4.08 with CF 272 `02 07` on the last token received (at once on the later set); variant `18 2e` after 4 s. Never a CON 4.08 or one without 272 |
| L6 | `$C -L 7 -N -b 64 -v 7 -s 10 -B 15 -o /data/obs.bin coap://127.0.0.1:$P/obs` (1500 B, changes every 3 s) | Observe on every block; new ETag per body; Continues continue on the registration token; final Observe 1 GET handled |
| L7 | L2 and L4 without `-N` | Empty ACK per Q-Block1 block, no 2.31, final response piggybacked; Q-Block2 block 0 piggybacked, then CON blocks |
| L8 | raw datagrams from a kcoap raw transport | Descending Q-Block2 list gets 4.00; Q-Block1 without Request-Tag or without Size1 gets 4.00; Block+Q-Block gets 4.02. libcoap's follow-up `Q-Block1 0/M` without payload gets 4.00, pinned as `upstream…` |
| L9 | L4 and L2 with `-l 10%`: 3 runs in `interop`, 20 in a nightly soak | Each run gives the correct body or a clean error; afterwards no partial bodies remain on kcoap |
| L10 | `$C -L 7 -b 64 -v 7 -m put -f /data/big3000.txt coap+tcp://127.0.0.1:$P/store`, `coap+ws://127.0.0.1:$W/store`, GET `/big` | Back to back; only the final response; Custody and Release not stuck |
| L11 | `$C -L 7 -N -b 64 -E /data/client.conf,/data/seq coap://127.0.0.1:$P/big` | kcoap with `withMessageSecurity = false` answers an inner 4.02 and libcoap falls back. The 5 s stalls are libcoap's, recorded |
| L12 | L2 with `verifyUnauthenticatedPeers = true` | The first burst request gets a NON 4.01 with Echo; record whether libcoap repeats it (pin it if not) |

### 1.6 Open risks
- **OSCORE with several responses** falls outside RFC 8613's single-response replay model. The only evidence will be kcoap against itself: libcoap 4.3.5b breaks (rv/A, rv/A2). That is why it is off by default.
- **libcoap deviations** limit what interop can test, and some require leniency:
  - no Observe after a Continue;
  - missing ETag and Size2 when it re-runs the handler;
  - its client needs Q-Block1 in a 2.31;
  - per-session stickiness, including across clients on the same source port (rv/E2);
  - 4.00 instead of 4.02 for a Block2 + Q-Block2 mix;
  - a 3-range limit on both roles;
  - undefined behaviour for a descending missing-block list;
  - only block 0 of a 2.01/2.04 body is sent;
  - timings of 264 s and 8 s.
- **Timing.** The interop tests run in real time, from about 4 s up to the 124 s ladder. Ladder tests stay unit tests; the long soak runs nightly.
- **B1 (PeerLink)** has to move the new endpoint seams. `ExchangeConformanceTest` must cover Q-Block, or regressions slip through.
- **Mobile.** Carrier-grade NAT can expire bindings (sometimes about 30 s) before the server's 28 s and 60 s 4.08s. Q-Block2 is safe because the client drives it; Q-Block1 relies on resending the last block, which is done for idempotent methods only.
- **ETag replacement** removes 2.03 validation by application ETag on Q-Block2 responses.
- **No-Response** combined with Q-Block1 is undefined; the client avoids the combination.
- **MAX_PAYLOADS** cannot be negotiated; a mismatch with the peer causes delays.
- **The amplification gate** may slow down or break NoSec interop with clients that cannot handle Echo (L12).

---

## 2. Platforms

### Android
- **Done.** Every module builds for `jvm` and is meant to be consumed on Android. JCA crypto. Post-quantum via `kcoap-crypto-bc`. UDP and TCP on ktor-network. WebSocket on any ktor engine (`KtorWebSocketConnector(client)` accepts OkHttp). Q-Block will be common code with no Android-specific part.
- **Missing, by priority:**
  1. Proof that an Android build consumes it and that it stays within the API level (kmp-4).
  2. Emulator runs and a crypto capability report per API level (kmp-x4, C2).
  3. Lifecycle (E9): network changes, socket rebind, Doze, re-observe, Q-Block body expiry and restart.
  4. Android Keystore keys (E7).
  5. DTLS for `coaps` (E1, last by the owner's decision).
  6. Multicast with MulticastLock (coap-core-8).
  7. Guide (C3).
- **Work packages:**
  - **PL-1:** `samples/android-consumer` with AGP, declared minSdk, an animal-sniffer check of the kcoap jvm jars, and a CI job. This is the Android part of P1, pulled forward without the BOM.
  - **PL-2:** emulator smoke test on GitHub Linux with KVM: one CoAP, OSCORE and EDHOC round trip against an in-app server, plus the capability report.
  - **E9:** with a Q-Block rebind test (the `RebindingTransport` scenario on the device).

### iPhone
- **Done.** `iosArm64` and `iosSimulatorArm64` targets for every module except `kcoap-crypto-bc`. CryptoKit and CommonCrypto. 490 common tests on the iOS simulator in CI. ktor-network UDP and TCP.
- **Missing, by priority:**
  1. `@Throws` everywhere: a Kotlin exception crashes a Swift caller (D2).
  2. XCFramework and SwiftPM, with Flow bridging (E4).
  3. WebSocket on the Darwin engine; CIO bypasses URLSession policy (W1).
  4. Lifecycle and background (E9).
  5. Network.framework adapter for cellular, VPN On Demand, the Local Network permission and multicast (E10).
  6. Secure Enclave and Keychain keys (E7).
  7. Runs on a real device, and a guide.
- **Work packages:**
  - **D2** (recommend pulling its `@Throws` and Swift-smoke part earlier as **PL-3**, right after QB-7, with `@Throws(CoapException::class, CancellationException::class)`; D2 reparents the exceptions later);
  - **W1**, **E4**, **E9**, **E10**, **E7**.
- **Verification.** Only on macOS: the CI `apple` job (`scripts/test apple`) runs every new Q-Block commonTest on the simulator.

### JVM application server
- **Done.** UDP, TCP and WebSocket listeners (`KtorWebSocketListener` hard-codes CIO; W1 fixes that). Events and interceptors. `SecurityContextRepository`. Bounded RFC 7959 state. OSCORE B.1.2 Echo.
- **Missing, by priority:**
  1. RFC 9175 amplification mitigation and server Echo (coap-core-16): **SRV-1**, required by the Q-Block bursts.
  2. Concurrency fixes and stress tests (S1).
  3. Bounded deduplication, exchange and observer state (coap-core-x3), 4.29 rate limiting (coap-core-18), and sequence tokens on TLS connections (RFC 9175 §4.2): **SRV-2**.
  4. TLS for `coaps+tcp` (T1).
  5. Persistent sequence numbers (A1) and persistable replay windows and EDHOC state (kmp-x3).
  6. Metrics adapter, benchmarks, multiple sockets (`SO_REUSEPORT`/Netty), Java facade, server guide (kmp-6).
- **Q-Block on a server.** All state is per node. `SO_REUSEPORT` keeps a 4-tuple on one socket; several nodes need load-balancer affinity by source address. Partial and cached bodies are capped per peer and globally. Timers are one coroutine per active body, bounded by the caps; a timer wheel is not needed.

### Web browser
- **Done.** `js` and `wasmJs` builds of the protocol modules. WebCrypto with capability checks and fallbacks. WebSocket client. Common tests on Node for both targets.
- **Missing, by priority:**
  1. Tests in a real browser (W1: headless Chrome job).
  2. Browser → proxy → device end to end with OSCORE and EDHOC, and `coaps+ws` (I1).
  3. Offering only the EDHOC suites that run there (A2; WebCrypto lacks ChaCha20).
  4. JS/TypeScript facade, `@JsExport`, npm package (kmp-7), and a bundle-size budget (P1).
  5. OSCORE over HTTP (deferred).
- **Q-Block.** The client keeps Q-Block off over WebSocket by default and uses BERT or Block. Through an OSCORE-unaware proxy, inner Q-Block is structurally broken (one response per request, 5.04 on intermediate blocks), so protected browser traffic uses inner RFC 7959. I1 asserts this.
- **Work packages:** W1, A2, I1, P1 (js), and **PL-4** (JS facade and npm, after C3).

---

## 3. Ordered work packages

**Rules.**
- One implementing agent builds at a time.
- During development, run only module-scoped tasks, for example `./gradlew :kcoap-coap:jvmTest --tests '*QBlock*'`.
- At the end of a work package, run `scripts/test unit`, then `scripts/test interop --tests '*QBlock*'` (or the WP's filter), then `scripts/test coverage`. Never two Gradle invocations at once. If memory is tight, add `--max-workers=4` (`gradle.properties` allows 8).
- "Linux" means this box: JVM, linuxX64, js and wasmJs on Node, and Docker with libcoap and libedhoc.
- Apple targets only on the macOS CI job. Android needs the SDK and an emulator (not installed here; `/dev/kvm` exists). Real browsers need Chrome (not installed here).
- Every WP adds a CHANGELOG "Unreleased" entry.
- Ownership amendment to `target-architecture.md` §4.1, for `kcoap-coap/**`: S1 → C1 (annotation) → **QB-2 → SRV-1 → QB-3 → QB-4 → QB-6** → B1 → B2 → …
- Ownership amendment, for `kcoap-oscore/**`: A1 → **QB-5** → A3 → B1.

| # | ID | Title | Depends on | Can run alongside (no file conflict) | Verify |
|---|---|---|---|---|---|
| 1 | QB-0 | Pin today's fallback, fix the docs | — | everything | Linux |
| 2 | A1 | OSCORE safe persistence (critical; target-architecture §4.3) | WP-0 | QB-0, QB-1, C1, S1, QB-2…QB-4 | Linux, plus the macOS job |
| 3 | QB-1 | Q-Block sans-IO core, parameters, codecs | — | A1, A2, C1, W1 (shares only a build-file line with C1) | Linux, plus the macOS job |
| 4 | C1 | Build governance | WP-0 | QB-1 (except that line), A1, A2 | Linux |
| 5 | S1 | Concurrency fixes | WP-0 | A1, A2, C1 | Linux (jvm and linuxX64 stress tests) |
| 6 | QB-2 | Endpoint seams for multi-response bodies | QB-1, S1 | A1, A2, W1, PL-1 | Linux, plus the macOS job |
| 7 | SRV-1 | Address verification and amplification limit | S1 | QB-2, A1, A2 | Linux and libcoap |
| 8 | QB-3 | Q-Block server | QB-1, QB-2, SRV-1 | QB-4 (except `EndpointEvent.kt` and `Libcoap.kt`) | Linux and libcoap |
| 9 | QB-4 | Q-Block client | QB-1, QB-2 (QB-3 for kcoap-to-kcoap tests) | QB-3 (same exceptions) | Linux and libcoap |
| 10 | QB-5 | OSCORE with Q-Block | QB-3, QB-4, A1 | A2, W1 | Linux and libcoap |
| 11 | QB-6 | Observe with Q-Block | QB-3, QB-4 (QB-5 for its OSCORE cases) | A2, W1, PL-1 | Linux and libcoap |
| 12 | QB-7 | Interop soak, documentation, compliance | QB-3…QB-6 | everything outside docs/ | Linux |
| 13 | A2 | EDHOC capability-aware negotiation | WP-0 | QB-* | Linux, plus the macOS job |
| 14 | W1 | RFC 8323 follow-ups (Darwin engine, headless Chrome) | C1 | QB-* | macOS CI, Chrome in CI |
| 15 | PL-1 | Android consumer sample and API-level check | C1 | QB-*, A* | Android SDK (CI) |
| 16 | PL-3 | `@Throws` and Swift smoke test (early part of D2) | QB-7, A2 | W1 | macOS CI |
| 17 | B1 | PeerLink seam, amended | C1, S1, QB-7 | A3, A4 | Linux, plus the macOS job |
| 18–21 | A3 → A4 → A5 → C2 | Crypto and glue chain (target-architecture §4.3) | as written there | B1 (A3/A4 against B2) | Linux, plus the macOS job |
| 22–23 | B2 → B3 | Split out the message module, API cleanup | B1 | A4, A5, C2, T1 | Linux |
| 24 | SRV-2 | Server hardening | B3 | T1, I1 | Linux and libcoap |
| 25 | T1 | TLS for stream transports | B1 | B2, B3, I1 | Linux; macOS CI for Apple TLS |
| 26 | I1 | Browser-topology interop, plus a Q-Block-off assertion | A3, B1 | T1, B2 | Linux (libcoap proxy); Chrome in CI |
| 27–28 | D1 → D2 | Config builders; remainder of `KcoapException` | B3, A3, A4 | T1 | Linux, macOS CI |
| 29 | C3 | Assembly, `docs/platforms.md`, BOM | B2, C2 | — | Linux |
| 30 | P1 | Remaining samples (jvm, js), bundle budget | C3, W1 | — | Linux and Chrome in CI |
| 31 | PL-2 | Android emulator smoke test | PL-1 | everything | Android emulator (CI, KVM) |
| 32 | PL-4 | JS facade and npm package | C3 | — | Chrome in CI |
| 33+ | E9, E4, E7, E10, E1 (DTLS last) | Roadmap features | as in target-architecture §4.4 | — | devices, macOS |

### Details for the new work packages

**QB-0: pin today's fallback, fix the docs**
- **Scope:**
  - New `KT/QBlockFallbackTest.kt`: a CON request with Q-Block1 or Q-Block2 gets 4.02 with a diagnostic; a NON one is rejected as today; a client with Q-Block off gets `BAD_RESPONSE` for a Q-Block2 response.
  - L1 with Q-Block disabled, added to `KJ/LibcoapClientAgainstKcoapTest.kt`.
  - `ROOT/docs/checklist.md`: `coap-client -Q` / `coap-server -Q` become `-L 7`; rows testing-cicd-3 and Biggest gaps item 1.
  - `ROOT/interop/README.md`: the Q-Block flags.
- **Accept:**
  - The tests pass.
  - libcoap logs "Q-Block support not available" and its real GET follows within 1 s.
  - `grep -n -- ' -Q' docs/checklist.md` finds nothing.

**QB-1: Q-Block sans-IO core**
- **Scope:**
  - New `QBlock*.kt` files in `KC/stack`.
  - Edits to `TransmissionParameters.kt`, `CoapOption.kt`, `OptionSet.kt` and `Blockwise.kt`.
  - The ADR `docs/adr/0006-q-block.md`.
  - The five sans-IO test files.
  - One line in `ROOT/kcoap-coap/build.gradle.kts`: `jvmTest` depends on `:kcoap-cbor` (on `commonTest`, Kotlin/Wasm 2.4.21 emits an invalid test module).
- **Accept:**
  - Every Figure 2–20 script matches the RFC's sequence and times; Figure 6 at 4, 12, 28 and 60 s, discard at 124 s.
  - `TransmissionParameters().nonPartialTimeout == 247.seconds`.
  - A bad `nonReceiveTimeout` throws `IllegalArgumentException`.
  - The new files reach at least 95 % line coverage.
  - Existing tests are unchanged.

**QB-2: endpoint seams for multi-response bodies**
- **Scope:**
  - `KC/stack/ClientExchange.kt`: `ResponseMode`, `ExchangeEvent`, the bounded inbox.
  - `CoapEndpoint.kt`: modes, `Pacing`, `leaseBody`; ACK a CON separate response after verification and the option check, RST on an unknown critical option (fixes the coap-core-4 gap).
  - `UdpMessaging.kt` and `Transmission.kt`: `BodyLease`.
  - Callers of `isObservation` (`ClientObservation`, `CoapClient`).
  - Tests in `KT/stack/UdpMessagingTest.kt` and a new `KT/stack/ExchangeModesTest.kt`.
- **Accept:**
  - A lease holds one slot: a second lease waits; it releases automatically at min(bytes ÷ PROBING_RATE, NON_PROBING_WAIT).
  - INBOX mode receives N responses on one token with no RST.
  - A CON separate response with option 9999 (unknown, critical) gets an RST.
  - All existing suites pass unchanged.

**SRV-1: address verification and amplification limit (RFC 9175 §2.4 item 3, §2.6)**
- **Scope:**
  - New `KC/server/AddressVerification.kt`: an unpredictable Echo value (internal CSPRNG) and an `ExpiringCache` of verified `Endpoint`s.
  - `CoapServer.kt`: the gate.
  - `CoapServerConfig.kt`: `amplification = Q_BLOCK_BURSTS | ALL_RESPONSES | OFF`, default `Q_BLOCK_BURSTS`, factor 3.
  - Tests.
- **Accept:**
  - Applies only to unauthenticated peers (no OSCORE identity and `PeerIdentity.None`) on datagrams.
  - The 4.01 with Echo is piggybacked or NON, never a separate CON.
  - A repeat carrying the Echo is served, and the address stays verified.
  - Exempt: OSCORE, reliable transports, and responses up to 3× the request size.
  - The libcoap `/.well-known/core` probe is never challenged.
- **Status: done.** `KC/server/AddressVerification.kt` (Echo values per challenged address, verified
  addresses, responses kept for the repeat of a POST or PATCH) and `KC/server/AmplificationPolicy.kt`;
  in `CoapServer.kt` the gate is `checksAddress`, `mustVerifyAddress(inbound, request, responseSize, isBurst)`
  and `challenge`. QB-3 calls `mustVerifyAddress` with `isBurst = true` and the bytes of the set it is
  about to send, and sends `challenge(...)` instead when it says so. The server-wide
  `CoapServerConfig.amplification` replaces the planned `QBlockServerConfig.verifyUnauthenticatedPeers`, so
  L12 runs with the default policy. Under `ALL_RESPONSES` an Observe registration is challenged whatever
  its size (RFC 7641 §7). Tests: `AmplificationTest`, `AddressVerificationTest`,
  `LibcoapClientAgainstKcoapAddressVerificationTest`.

**QB-3: Q-Block server**
- **Scope:**
  - `KC/server/QBlockServer.kt`, `QBlockServerConfig.kt`, `CoapServer.kt` (sealed `Reply`, the Q-Block branch, probe answer), `CoapServerConfig.kt`, `OptionCheck.kt`, `EndpointEvent.kt` (all Q-Block events).
  - `KT/QBlockServerTest.kt`: a raw scripted client for every server rule, including the refusal half of Figure 12 (a Continue without Request-Tag that matches two cached bodies is refused), which the QB-1 figure script cannot show.
  - The server parts of `QBlockTest`/`QBlockReliableTest`.
  - `KJ/Libcoap.kt` (`wire` helper), a `DroppingTransport` in jvmTest, and `KJ/LibcoapClientAgainstKcoapQBlockTest.kt`: L1–L5, L7, L8, L10, L12.
- **Accept:**
  - Those interop tests pass with byte-equal bodies.
  - A Custody Pong is not held up by intermediate Q-Block1 requests.
  - The server state is empty after NON_PARTIAL_TIMEOUT.
  - WARN once at start when Q-Block is enabled with `security == null`.

**QB-4: Q-Block client**
- **Scope:**
  - `KC/client/QBlockClient.kt`, `QBlockSupport.kt`, `QBlockClientConfig.kt`, `CoapClient.kt`, `CoapClientConfig.kt`.
  - `KT/QBlockClientTest.kt` and the client parts of `QBlockTest`: soak, fallback, lease, NAT rebinding.
  - A new `RebindingTransport` in `ROOT/kcoap-testkit/src/commonMain/kotlin/dev/kcoap/testkit/`.
  - `KJ/KcoapClientAgainstLibcoapQBlockTest.kt`: K1–K5, K7–K10, K12.
- **Accept:**
  - Those interop tests pass.
  - The soak passes for 20 seeds with no leaked exchanges (`endpoint` exchange count is 0).
  - No message ever carries both Block and Q-Block options; a property check over all `traffic`.
  - Probe results are cached and invalidated as specified.

**QB-5: OSCORE with Q-Block**
- **Scope:**
  - `KO/OscoreEndpoints.kt`, `KO/OscoreProtection.kt` (the binding flag).
  - New `ROOT/kcoap-oscore/src/commonTest/kotlin/dev/kcoap/oscore/OscoreQBlockTest.kt`: unit tests plus kcoap-to-kcoap end to end on `InMemoryNetwork` with loss.
  - `ROOT/kcoap-oscore/src/jvmTest/kotlin/dev/kcoap/oscore/StackOscoreInteropTest.kt`: K11 and L11.
- **Accept:**
  - A replayed block response is rejected.
  - A second response to a request without Q-Block is still rejected.
  - Q-Block1 PUT and Q-Block2 GET with 10 % loss pass under OSCORE between two kcoap peers.
  - The libcoap limits are pinned as `upstream…` tests; the defaults pass against libcoap.

**QB-6: Observe with Q-Block**
- **Scope:**
  - `KC/server/ObservationRegistry.kt`, `CoapServer.kt` (`notification`), `QBlockServer.kt` (`NotificationBody`).
  - `KC/client/ClientObservation.kt`, `QBlockClient.kt`.
  - `KT/QBlockObserveTest.kt` (Figures 7–15); K6 and L6; OSCORE notifications with reordering in `OscoreQBlockTest`.
- **Accept:**
  - One emission per body.
  - With a resource that changes every 1 s and a 24-block body, the client still receives complete bodies: no starvation.
  - A Reset to any block removes the observer.
  - A Q-Block1 FETCH registration uses the token of the 2.05.

**QB-7: interop soak, documentation, compliance**
- **Scope:**
  - The nightly soak (L9 × 20, K soak) behind a property.
  - The S1 stress helper running Q-Block bodies (jvmTest and linuxX64Test).
  - `ROOT/docs/rfc-compliance.md` (RFC 9177 row marked done, with what verifies it), `ROOT/docs/checklist.md` (coap-core-13, testing-cicd-3/x3), `ROOT/docs/architecture.md` (Q-Block layer), `ROOT/kcoap-coap/README.md` (line 82), `ROOT/interop/README.md` (libcoap deviations, pinned), and a Q-Block section in `ROOT/docs/guide-client-server.md`.
- **Accept:**
  - `GuideExamplesTest` compiles the new section.
  - The docs match the configuration defaults.
  - Every libcoap deviation listed has a matching `upstream…` test.

**Amendment to B1.**
- Move `ResponseMode`, the inbox and `BodyLease` behind `PeerLink`: `DatagramLink` owns the lease; on `ConnectionLink` it does nothing.
- The Q-Block cores take `link.capabilities.hasMessageTypes`.
- `ExchangeConformanceTest` adds Q-Block1 and Q-Block2 on both links.
- Accept: the Q-Block suites pass with only construction lines changed.

**SRV-2: server hardening.**
- Caps with tests for `DeduplicationCache`, `MessageIdAllocator`, `ObservationRegistry` and the exchange map, against spoofed CON floods.
- A 4.29 helper with Max-Age, and a client retry-after helper.
- `TokenGenerator.sessionSequence` for TLS connections.

**PL-1: Android consumer check.**
- `samples/android-consumer`, a separate build against `mavenLocal`, with AGP and minSdk (Q8) and animal-sniffer over the kcoap jvm jars.
- A CI job.
- Accept: the job passes; minSdk is recorded in `docs/platforms.md` (C3 later).

**PL-2: Android emulator smoke test.**
- An emulator on GitHub Linux with KVM.
- An app that runs a CoAP GET, an OSCORE GET and an EDHOC handshake over UDP to a loopback server, and prints the capability report.
- Accept: green at minSdk and at the latest API level.

**PL-3: early Swift safety.**
- `@Throws` on the public suspend entry points.
- An unpublished `kcoap-swift-smoke` module and a Swift script in the macOS job.
- Accept: Swift catches a timeout and a malformed URI as `NSError`.

---

## 4. Questions only you can answer

Each has a recommended default, so work can start without waiting.

1. **Order.** Should Q-Block (QB-2…QB-7) land before the target-architecture refactor B1, so that B1 carries Q-Block onto PeerLink? *Default: yes, Q-Block first; B1 amended as in §3.*
2. **Release scope.** Is Q-Block part of 0.1.0, that is, added to the release freeze list in `docs/releasing.md`? *Default: yes.*
3. **Default enablement.** *Default: both sides OFF in 0.1.0 (`QBlockMode.OFF`, `isEnabled = false`). Reconsider PROBE as the client default after a release of nightly soak.*
4. **Message type of a body.** Should Q-Block bodies follow the request's `isConfirmable` flag, which defaults to CON, or always use NON bursts when the peer supports Q-Block? *Default: follow the request's type; applications call `.nonConfirmable()` to get bursts.*
5. **Application ETags.** On Q-Block2 bodies the RFC requires an ETag unique per transmitted body. Replace the application's ETag with a per-body counter (this loses 2.03 validation by that ETag)? *Default: replace it, and document this.*
6. **Q-Block under OSCORE.** It falls outside RFC 8613's single-response model and cannot be cross-tested (libcoap 4.3.5b fails). *Default: opt-in through `withMessageSecurity = false`; protected requests use inner Block1/Block2 unless enabled; tested kcoap against kcoap.*
7. **Amplification policy (RFC 9175 §2.6 SHOULD).** *Default: Echo check of unauthenticated peers before Q-Block bursts only (`Q_BLOCK_BURSTS`); keeping the challenge out of ordinary single responses keeps today's behaviour. Extending it to every large response is decided in SRV-2.*
8. **Android and iOS minimum versions.** *Default: Android minSdk 26 and iOS 15; PL-1's animal-sniffer report and the CryptoKit availability check may revise them.*
9. **Android verification.** May the implementing agent install the Android SDK (several GB) on this machine and use its KVM emulator, or should Android stay CI-only? *Default: CI-only.*
10. **Browser verification.** Install Chrome, or a Chrome Docker image, here for Karma, or CI-only? *Default: CI-only, through W1's job.*
11. **libcoap compatibility.** Should the kcoap server copy libcoap's per-peer "sticky" Q-Block2 (Q-Block2 even for Block2 or plain requests once a peer used Q-Block)? *Default: no; follow the RFC (Q-Block2 only when the request carries Q-Block1 or Q-Block2). Revisit only if L2 without `-b` shows libcoap clients failing.*
12. **Strictness towards libcoap's client bug.** libcoap's client sends `Q-Block1 0/M` without payload in Q-Block2 follow-ups. Should the kcoap server answer them strictly (4.00) or leniently (serve from the cache)? *Default: strict, pinned as an `upstream…` test.*
13. **Reporting upstream.** Should the libcoap deviations found (missing Observe after a Continue, the descending-list memory bug, 4.00 instead of 4.02, the 2.31 truncation, OSCORE bursts, stickiness) be filed with libcoap? Filing sends messages on your behalf. *Default: no filing; pin them as `upstream…` tests and list them in `interop/README.md`, for you to file if you want.*