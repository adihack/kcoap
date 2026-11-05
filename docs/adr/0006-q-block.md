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
  `GET /.well-known/core` carrying Q-Block2 0/0/16, the probe libcoap sends, and the endpoint keeps the
  answer per peer and protection for all its clients, so a client registers nothing with it. Only a 2.xx
  answer with Q-Block counts as support: libcoap without Q-Block answers 4.02 and repeats the Q-Block2
  option it rejects. A 4.02 or a Reset before any other answer to a body sends the request again with RFC
  7959, since the server did not process it. An Empty ACK, a 2.31, a 4.08 or a block counts as an answer:
  a 4.02 after one rejected another option (RFC 7252 §5.4.1), is returned, and leaves the capability as
  it is. A 4.01 with Echo does not count. The server answers with Q-Block2 only
  when the request carries Q-Block1 or Q-Block2, never because the peer used Q-Block before (libcoap's
  per-session stickiness is not copied).
- **ETag counter.** Every Q-Block2 body gets its own 4-byte ETag from a counter with a random start, which
  replaces an application ETag (§4.4 requires one unique per transmitted body).
- **Tokens.** The final response to an upload goes on the request that completed the body. The client
  releases the tokens a 2.31 confirmed, except that of the latest 2.31, keeps those of later blocks, and
  accepts the final response on any token of the body, as §4.3 allows (Figure 14 uses another token than
  Figure 5). A 4.08 therefore goes on the request of the highest block that arrived, or, once a 2.31 was
  sent, on the 2.31's request until a higher block arrives: a resent block below the highest one can
  complete a set, and its 2.31 releases the highest block's token. Once a later set has arrived the server
  sends no 2.31 for a set it completes late, so the client also releases, on a 4.08, the tokens of the
  blocks up to its request that it does not list, and, for a Confirmable body, the token of a block
  acknowledged while later blocks are unsent: neither can carry the final response. Without that a long
  body over a lossy path held a token per block and ran into `maxExchangesPerPeer` (found by the soak).
  A server that sends no 2.31 while set 0 has a gap (libcoap) releases nothing until its first 4.08, so a
  NON upload also holds at most four sets of tokens (40 by default) and lets the next set wait for that
  4.08 beyond them. On a connection only block 0 (an early error) and the last block (the final response)
  keep their tokens (Appendix B), and the body's inbox has room for every block of the largest body, since
  nothing is asked for again there.
- **2.31 always carries Q-Block1** with the last NUM of the set, M set, because libcoap's client truncates
  the upload without it.
- **Block 0 is asked for again as `0/M=1`** by GET and FETCH: libcoap runs the handler again for NUM 0.
- **Resending the last block.** RFC 9177 does not say what a client does when the last block meets
  silence. A PUT, FETCH or iPATCH sends it again on the NON_RECEIVE_TIMEOUT ladder (4, 12, 28 and 60 s),
  which brings the final response from a server that completed the body, or 4.08s from one that lost it;
  POST and PATCH, which must not repeat, fail after the ladder (124 s) without resending. A 4.08 shows that
  the server holds the body and runs its own ladder on the same times, so after one the client resends
  nothing until that ladder could have run out (124 s after the latest 4.08); Figure 6 then shows only the
  resends of the lost block.
- **Partial-body lifetime floor.** A client keeps a partial body for min(NON_PARTIAL_TIMEOUT, Max-Age)
  (§4.4), but at least as long as the retry ladder; otherwise a Max-Age of 60 s or 0 would end recovery
  before it could work. Like the server, it counts from the latest new block, so a large body over a slow
  link is not discarded while its blocks still arrive.
- **One ETag per Q-Block2 block.** A block without ETag is dropped (§4.4), except libcoap's answer without
  ETag and Size2 while a body is assembled, which counts as a changed representation.
- **Echo.** A 4.01 with Echo resends each block of the burst that was sent before the first challenge;
  a challenge to a block that carried Echo ends the body.
- **Body lease.** A Non-confirmable body takes one NSTART slot for its whole life, released at
  min(bytes ÷ PROBING_RATE, NON_PROBING_WAIT); a Confirmable body uses per-message NSTART (a lease plus
  Confirmable blocks would deadlock at NSTART 1). This lands with the endpoint seams (QB-2).
- **Reliable transports.** The client keeps Q-Block off on TCP and WebSockets unless configured, and uses
  BERT or Block there; a server that receives Q-Block on a connection sends blocks back to back and answers
  intermediate requests without a response.
- **OSCORE.** Q-Block under OSCORE is opt-in (`withMessageSecurity`): several responses to one request
  fall outside RFC 8613's model, so they are accepted only for requests that carried inner Q-Block, each
  with a new Partial IV checked against a per-request replay window. Protected requests otherwise use inner
  Block1 and Block2. The window has the size of the context's own (32 by default), and at most one response
  of a body may reuse the request's nonce; every other request takes exactly one response. Of the
  unprotected responses, such a request gets only the errors that end its body (4.00, 4.01 and 5.xx without
  Echo or a block option): the client acts on the others for the whole body, so they would let anyone on the
  path steer it. libcoap 4.3.5b protects and verifies only the first response to a request, so these bodies
  are tested kcoap against kcoap, and its failure is pinned (QB-5).
- **Amplification.** A server bursting to an unauthenticated, unverified peer first verifies its address
  with Echo (RFC 9175 §2.4), for Q-Block bursts only, and for Observe registrations with Q-Block, whose
  notification bodies are bursts that no request asks for (QB-6).
- **Observe** (QB-6). A notification is a Q-Block2 body with a new ETag and the Observe value on every
  block, also those sent for a Continue, never those answering a request for missing blocks (§4.5). A
  body is the observation's one outstanding notification until every block went out (Confirmable ones
  acknowledged) and, Non-confirmable, for at least `nonConfirmableNotificationInterval`, holding one NSTART
  slot; changes meanwhile fold into the next body, and no body is cut short by a newer one, so a resource
  that changes faster than its bodies go out does not starve the client. A newer body ends what an older
  one still had to send. Every block that carries Observe is a notification: a Reset to any of them, or an
  unacknowledged Confirmable one, removes the observer; a Reset to an answer for missing blocks (no Observe,
  §4.5) only drops what that request still had queued, since the client may have released its token.
  Notification bodies are not checked against the amplification factor one by one: under every policy that
  checks addresses, a Q-Block registration from an unauthenticated client verifies its address with Echo
  whatever the size of its first response, and once that verification lapses its bodies go Confirmable, so an
  address that does not acknowledge gets one block and its retransmissions before the observer goes. A body
  counts as one notification for RFC 7641 §7's run of Non-confirmable ones, and a Non-confirmable body holds
  its NSTART slot for `nonConfirmableNotificationInterval` (RFC 7641 §4.5.1), not PROBING_RATE: a complete
  single-set body draws no answer from a compliant client, so silence cannot be told from success. The
  observation keeps only an `OutstandingBody` handle; the body itself lives in the bounded cache. The client
  gives each body a receiver of its own, in the mode of its blocks' message type, orders bodies once, on the
  first block of a new ETag, and takes a body without Observe only in answer to its own request for the body
  in progress, since an older answer could be staler than the bodies since; a fresher notification of one
  message supersedes the body in progress too.
  The client releases the tokens of a body's Continues and requests for missing blocks with the body. A
  Q-Block1 FETCH registration keeps the token of the 2.05 and ends by forgetting it, because a
  deregistration would send the body again under a new Request-Tag; when its 2.05 is lost and the client
  sends the last block again on a new token, the server sends the 2.05 again on the token of the request
  that completed the upload, where the notifications go (§4.3), and nothing on the copy. Its follow-ups
  always carry the upload's Request-Tag and no payload (§4.7). Under OSCORE the client observes with Inner
  Block2 even where both sides opt in to Q-Block: `OscoreClient` takes a notification only with a Partial IV
  above every one before (RFC 8613 §7.4.1), and with reordering it dropped most blocks of a notification
  body. Taking them by a window per registration is still open.
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
