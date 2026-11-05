# kcoap-edhoc-oscore

EDHOC as the key establishment for OSCORE, over CoAP:

| Spec | What | Where |
|---|---|---|
| RFC 9528 App. A.1 | OSCORE context from an EDHOC session: exporter-derived Master Secret and Salt, application AEAD and HKDF, Sender/Recipient IDs from C_R/C_I (Table 14) | `EdhocSession.oscoreParameters()`, `oscoreContext()` |
| RFC 9528 App. A.2 | EDHOC over CoAP, forward flow: POST to `/.well-known/edhoc`, `true`/C_R prefixes, Content-Formats 64/65, error responses (A.2.3) | `EdhocOverCoap`, `EdhocServer.handle()`, `EdhocClientHandshake.run()` |
| RFC 9668 §3 | EDHOC + OSCORE combined request: message_3 on the first OSCORE request, the EDHOC option, server processing order and errors | `CombinedRequestSecurity`, `CombinedRequestServer`, `EdhocClientHandshake.runCombined()` |
| RFC 9668 §4 | Connection identifiers usable as OSCORE IDs: C_R unique among sessions and Recipient IDs, never C_I, also under concurrent handshakes | `EdhocServer` |
| RFC 9528 §9.7 | Pending server sessions bound to the peer that sent message_1, bounded in total and per peer, expired after EXCHANGE_LIFETIME, secrets wiped | `EdhocServer`, `EdhocServerEvent` |

## Server

```kotlin
val contexts = InMemoryContextRepository()                     // shared by EDHOC and OSCORE
val edhoc = EdhocServer(responderConfig, crypto, contexts, eventListener = { metrics.count(it) })
val security = CombinedRequestServer(edhoc, OscoreServer(contexts))
CoapServer(transport, scope, CoapServerConfig(security = security)) {
    resource(".well-known") { add(EdhocResource(edhoc)) }  // calls edhoc.handle(request, peer)
}
```

Until message_3 verifies, nothing about a client is authenticated, so the server bounds what clients can
make it keep (RFC 9528 §9.7):

- **C_R** is reserved in the same locked step that checks it is free, before message_2 is composed, and
  stays reserved until the session's OSCORE context is in the repository: concurrent handshakes never
  share one, nor collide with a context being added (RFC 9668 §4.1.2).
- **Peer binding.** A session continues only for the peer that sent message_1 (for DTLS, the same
  session in any epoch). A message_3, an error message or a combined request from any other peer gets
  the answer for an unknown C_R, costs no cryptography and leaves the session waiting
  (`EdhocServerEvent.ContinuationFromAnotherPeer`). Only this pending state is bound to the address; a
  client whose address changes between message_1 and message_3 starts again. OSCORE contexts are not
  bound to addresses.
- **Bounds.** At most `maxPendingSessions` (64) sessions are in progress, at most
  `maxPendingSessionsPerPeer` (8) for one peer. A message_1 beyond them is refused before any processing
  with an EDHOC error in a 5.00 response (`EdhocServerEvent.LimitReached`); sessions in progress are
  never dropped for a new one.
- **Expiry.** A session without message_3 after `pendingSessionLifetime` (EXCHANGE_LIFETIME, 247 s) is
  dropped on the injected `timeSource`, its ephemeral key and PRK_3e2m wiped
  (`EdhocServerEvent.SessionExpired`). A client's error message wipes them too.

Spoofed message_1 floods still cost a key generation and a signature each; the defence is an Echo
reachability check before message_2 (RFC 9528 Appendix A.2), which belongs to the CoAP server.

Both `EdhocServer` and `EdhocClientHandshake` take a `sequenceStore` for the contexts they derive,
`EphemeralSequenceNumberStore()` by default, which fits keys that are new with every session. Pass a
durable store to resume a context after a restart (`oscoreParameters` explains how).

## Client

```kotlin
val handshake = EdhocClientHandshake(initiatorConfig, crypto) { request -> client.send(request) }
val (session, security) = handshake.runCombined()  // two round trips in total (RFC 9668)
// Give `security` to the CoAP client: its first request carries message_3.
```

`EdhocClientHandshake` takes a plain "send this request, return the response" function, so it is
independent of the CoAP endpoint and easy to test.

## Verified by

- RFC 9668 Figure 4, byte for byte.
- Complete handshakes over the CoAP framing, sequential and combined, with and without message_4,
  cipher suite negotiation across the transfer, and every error path of RFC 9668 §3.3.1 and RFC 9528
  Appendix A.2.3.
- OSCORE contexts derived on both sides interoperate. The OSCORE values themselves come from the
  EDHOC exporter, checked against RFC 9529 in `kcoap-edhoc`.
- Pending sessions (`EdhocServerSessionsTest`): C_R collisions with a session composing message_2 and
  with one completing, made deterministic with gates, and concurrent handshakes on real threads
  (`Stress.race`) that all draw the same C_R candidate; both fail without the reservation. Continuations
  from another peer (message_3, error message, combined request, another DTLS session) leave the session
  alone and run no cryptography; the bounds refuse before any cryptography; sessions expire on a
  `TestTimeSource` and their ephemeral keys are wiped.

Not yet: the reverse message flow (A.2.2), Block-wise of the combined request beyond the first-block
rule, and live interop of the combined flow (planned with libedhoc and aiocoap).
