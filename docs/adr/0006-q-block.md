# 0006. Q-Block as sans-IO state machines, off by default

Date: 2026-11-05. Status: accepted.

## Context

RFC 9177 adds Q-Block1 and Q-Block2: Non-confirmable bursts of MAX_PAYLOADS blocks, 2.31 and Continue to
skip the pause between sets, lists of missing blocks in a 4.08 (Content-Format 272) or in repeated
Q-Block2 options, and timers that ask for what is missing. The project requires it and libcoap 4.3.5
implements it (`-L 7`), but RFC 9177 leaves much open: which token answers carry, how long a partial body
lives, what a client does when a body meets silence, how a peer finds out that the other side supports
Q-Block, and how several responses to one request fit OSCORE's single-response replay model. libcoap adds
deviations of its own (no Observe after a Continue, no ETag or Size2 when it runs a handler again, a client
that truncates an upload after a 2.31 without Q-Block1, per-peer "sticky" Q-Block2). The full analysis is
in [../plan-qblock-and-platforms.md](../plan-qblock-and-platforms.md), section 1.

## Decision

- **Sans-IO cores.** The rules live in four internal state machines in `dev.kcoap.coap.stack`
  (`QBlock1Sender`, `QBlock1Receiver`, `QBlock2Sender`, `QBlock2Receiver`): messages and `now` go in, a list
  of `QBlockAction`s and `nextDeadline()` come out, and the driver owns tokens, options and I/O. Time is
  injected, so every RFC 9177 figure runs as a script on virtual time over the real `UdpMessaging`.
  NON_TIMEOUT_RANDOM is drawn once per body from the endpoint's `Random`. The parts they share are the
  Content-Format 272 codec (`MissingBlocks`, hand-written CBOR major type 0, so `kcoap-coap` gains no
  dependency), the structural checks (`QBlockCheck`), the MAX_PAYLOADS_SETs, a bitset of received blocks and
  the resolved timing.
- **Parameters.** `TransmissionParameters` carries MAX_PAYLOADS, NON_TIMEOUT, NON_RECEIVE_TIMEOUT and
  NON_MAX_RETRANSMIT, and derives NON_PARTIAL_TIMEOUT (247 s). NON_RECEIVE_TIMEOUT must be at least
  NON_TIMEOUT × ACK_RANDOM_FACTOR + 1 s (§7.2); its default is 2 × NON_TIMEOUT (Table 3) or that bound when
  larger, so a shorter ACK_TIMEOUT stays a valid configuration.
- **Off by default, both sides.** The client's `QBlockMode.OFF` and the server's `isEnabled = false` keep
  today's behaviour: a Confirmable Q-Block request gets 4.02, a Non-confirmable one a Reset, and the client
  rejects a response carrying Q-Block. Tests pin this. A body follows the request's message type; an
  application asks for bursts with a Non-confirmable request.
- **Separate probe, no stickiness.** A client in PROBE mode asks a peer once with a Confirmable
  `GET /.well-known/core` carrying Q-Block2 0/0/16, the probe libcoap sends, and caches the answer per peer
  and protection. The server answers with Q-Block2 only when the request carries Q-Block1 or Q-Block2,
  never because the peer used Q-Block before (libcoap's per-session stickiness is not copied).
- **ETag counter.** Every Q-Block2 body gets its own 4-byte ETag from a counter with a random start, which
  replaces an application ETag (§4.4 requires one unique per transmitted body).
- **Tokens.** The final response to an upload goes on the request that completed the body. The client
  releases the tokens a 2.31 confirmed, except that of the latest 2.31, keeps those of later blocks, and
  accepts the final response on any token of the body, as §4.3 allows (Figure 14 uses another token than
  Figure 5). A 4.08 therefore goes on the request of the highest block that arrived, or, once a 2.31 was
  sent, on the 2.31's request until a higher block arrives: a resent block below the highest one can
  complete a set, and its 2.31 releases the highest block's token.
- **2.31 always carries Q-Block1** with the last NUM of the set, M set, because libcoap's client truncates
  the upload without it.
- **Block 0 is asked for again as `0/M=1`** by GET and FETCH: libcoap runs the handler again for NUM 0.
- **Resending the last block.** RFC 9177 does not say what a client does when the last block meets
  silence. A PUT, FETCH or iPATCH sends it again on the NON_RECEIVE_TIMEOUT ladder (4, 12, 28 and 60 s),
  which brings the final response from a server that completed the body, or 4.08s from one that lost it;
  POST and PATCH, which must not repeat, fail after the ladder (124 s) without resending.
- **Partial-body lifetime floor.** A client keeps a partial body for min(NON_PARTIAL_TIMEOUT, Max-Age)
  (§4.4), but at least as long as the retry ladder; otherwise a Max-Age of 60 s or 0 would end recovery
  before it could work.
- **Body lease.** A Non-confirmable body takes one NSTART slot for its whole life, released at
  min(bytes ÷ PROBING_RATE, NON_PROBING_WAIT); a Confirmable body uses per-message NSTART (a lease plus
  Confirmable blocks would deadlock at NSTART 1). This lands with the endpoint seams (QB-2).
- **Reliable transports.** The client keeps Q-Block off on TCP and WebSockets unless configured, and uses
  BERT or Block there; a server that receives Q-Block on a connection sends blocks back to back and answers
  intermediate requests without a response.
- **OSCORE.** Q-Block under OSCORE is opt-in (`withMessageSecurity`): several responses to one request
  fall outside RFC 8613's model, so they are accepted only for requests that carried inner Q-Block, each
  with a new Partial IV checked against a per-request replay window. Protected requests otherwise use inner
  Block1 and Block2.
- **Amplification.** A server bursting to an unauthenticated, unverified peer first verifies its address
  with Echo (RFC 9175 §2.4), for Q-Block bursts only.
- **Strict towards libcoap's client.** A Q-Block2 follow-up that carries `Q-Block1 0/M` without payload
  gets 4.00; the deviation is pinned as an `upstream…` test.

## Consequences

- The RFC rules are testable without sockets or clocks, block by block and figure by figure, on every
  target. The drivers in the client and the server stay thin and do not repeat the rules.
- Nothing changes for applications until they turn Q-Block on; then 2.03 validation by their own ETag is
  lost on Q-Block2 bodies.
- MAX_PAYLOADS cannot be negotiated: a peer with another value costs delays, not data.
- The cores take `BodyMode`; when `PeerLink` lands (ADR 0005) they take the link's capabilities instead,
  and only their construction changes.
