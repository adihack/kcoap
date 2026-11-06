# kcoap-edhoc-oscore

EDHOC as the key establishment for OSCORE, over CoAP:

| Spec | What | Where |
|---|---|---|
| RFC 9528 App. A.1 | OSCORE context from an EDHOC session: exporter-derived Master Secret and Salt, application AEAD and HKDF, Sender/Recipient IDs from C_R/C_I (Table 14) | `EdhocSession.oscoreParameters()`, `oscoreContext()` |
| RFC 9528 App. A.2 | EDHOC over CoAP, forward flow: POST to `/.well-known/edhoc`, `true`/C_R prefixes, Content-Formats 64/65, error responses (A.2.3), in the application model of kcoap-coap-message (`CoapRequest`, `CoapResponse`) | `EdhocOverCoap`, `EdhocServer.handle()`, `EdhocResource`, `EdhocClientHandshake.run()` |
| RFC 9668 §3 | EDHOC + OSCORE combined request: message_3 on the first OSCORE request, the EDHOC option, server processing order and errors | `CombinedRequestSecurity`, `CombinedRequestServer`, `EdhocClientHandshake.runCombined()` |
| RFC 9668 §4 | Connection identifiers usable as OSCORE IDs: C_R unique among sessions and Recipient IDs, never C_I, also under concurrent handshakes | `EdhocServer` |
| RFC 9528 §9.7 | Pending server sessions bound to the peer that sent message_1, bounded in total and per peer, the oldest evicted past a grace period, expired after MAX_TRANSMIT_WAIT, secrets wiped | `EdhocServer`, `EdhocServerEvent` |

## Server

```kotlin
val contexts = InMemoryContextRepository()                     // shared by EDHOC and OSCORE; bound it in production
val edhoc = EdhocServer(responderConfig, crypto, contexts, eventListener = { metrics.count(it) })
val security = CombinedRequestServer(edhoc, OscoreServer(contexts))
CoapServer(transport, scope, CoapServerConfig(security = security)) {
    resource(".well-known") { add(EdhocResource(edhoc)) }  // calls edhoc.handle(payload, peer)
    resource("lights") {
        // Only OSCORE reaches it (the default with message security); the policy narrows it to operators.
        access = AccessPolicy { (it.securityIdentity as? EdhocIdentity)?.peerCredential in operators }
        get { CoapResponse.content("on") }
    }
}
```

`EdhocServer` puts the context of every completed session into any `MutableSecurityContextRepository`
(kcoap-oscore), usually the one its `OscoreServer` finds contexts in. Those contexts accumulate, one per
handshake, and nothing removes them: `InMemoryContextRepository` has no bound, so a production server
supplies a repository with one (evicting the context found least recently, say). An evicted client gets
OSCORE's unprotected 4.01 "Security context not found", or 4.00 "Decryption failed" once its old C_R went to
another client, and takes either as the signal to run EDHOC again. A session is established all or nothing:
when the repository, the sequence number store or the `EdhocSessionListener` throws, or the handler is
cancelled, the context is removed again, the session wiped, and the client gets "EDHOC failed" in a 5.00
(RFC 9528 Appendix A.2.3). `CombinedRequestServer`
decorates any `ServerMessageSecurity` that verifies against those contexts, such as `OscoreServer` or a
decorator of it.

`EdhocServer.handle` answers everything a peer sends with a response, never an exception: a malformed
payload (`EdhocOverCoap.parseRequest` throws `EdhocException`) and every failed EDHOC step become the
EDHOC error response of RFC 9528 Appendix A.2.3.

`EdhocResource` is `AccessPolicy.Public` of its own, so clients run EDHOC before they have OSCORE; every
other resource of a server with message security refuses requests without it (ADR 0008). A request
verified with a context this server established carries an `EdhocIdentity` (`EdhocSession.oscoreContext`
attaches it): the peer credential EDHOC authenticated, its identifier, and the context's `OscoreIdentity`.
Authorize on the credential; the Recipient ID is a C_R the server may give a later session.

Until message_3 verifies, nothing about a client is authenticated, so the server bounds what clients can
make it keep (RFC 9528 §9.7):

- **C_R** is reserved in the same locked step that checks it is free, before message_2 is composed, and
  stays reserved until the session's OSCORE context is in the repository: concurrent handshakes of one
  server never share one, nor collide with a context it is adding (RFC 9668 §4.1.2). The context is added
  only if its Recipient ID is still free (`MutableSecurityContextRepository.addIfAbsent`), so when another writer
  of the repository took it meanwhile (a second `EdhocServer` for another application profile, RFC 9668
  §5, or the application), the handshake fails with "Connection identifier in use" and that writer's
  context stays.
- **Peer binding.** A session continues only for the peer that sent message_1 (for DTLS, the same
  session in any epoch). A message_3, an error message or a combined request from any other peer gets
  the answer for an unknown C_R, costs no cryptography and leaves the session waiting
  (`EdhocServerEvent.ContinuationFromAnotherPeer`). Only this pending state is bound to the address; a
  client whose address changes between message_1 and message_3 starts again. OSCORE contexts are not
  bound to addresses.
- **Bounds.** At most `maxPendingSessions` (64) sessions are in progress, at most
  `maxPendingSessionsPerPeer` (8) for one peer, where a peer is an address and port (for DTLS, a
  session). A message_1 beyond the per-peer bound is refused before any processing with an EDHOC error
  in a 5.00 response (`EdhocServerEvent.LimitReached`).
- **Eviction.** When all places are taken, the session that has waited longest for message_3 makes room
  for the new one if it has waited longer than `pendingSessionGracePeriod` (10 s); it is wiped and
  reported (`EdhocServerEvent.SessionEvicted`). Only when every waiting session is within its grace
  period is the new message_1 refused as above. A client that answers message_2 within the grace period
  is never evicted. A grace period of at least `pendingSessionLifetime` turns eviction off.
- **Expiry.** A session without message_3 after `pendingSessionLifetime` (MAX_TRANSMIT_WAIT, 93 s) is
  dropped on the injected `timeSource`, its ephemeral key and PRK_3e2m wiped
  (`EdhocServerEvent.SessionExpired`). A client's error message, and a handler cancelled mid-handshake,
  wipe them too and give the place back.

What the bounds do not stop is a flood of message_1 from spoofed addresses: each costs a key generation,
a Diffie-Hellman and a signature, and with the defaults 64 of them per 10 s (6.4 per second) keep new
clients out while the flood lasts. The defence is an Echo reachability check before message_2 (RFC 9528
Appendix A.2), which belongs to the CoAP server. Raise `maxPendingSessions` or lower the grace period to
make that flood dearer; watch `SessionEvicted` and `LimitReached` to see it.

Both `EdhocServer` and `EdhocClientHandshake` take a `sequenceStore` for the contexts they derive. By
default each context gets its own `EphemeralSequenceNumberStore`, which fits keys that are new with every
session. Pass a durable store to resume a context after a restart (`oscoreParameters` explains how). The
store keys its limits by context name, the Sender and Recipient ID pair, which is unique only among the
contexts of one server: a client gives each server it talks to its own durable store (or one that puts a
name of the server in front of the context names, used again for the restore), and a server does not
share its store with contexts outside its repository.

## Client

```kotlin
val security = PeerSecurity()
val client = CoapClient(transport, scope, CoapClientConfig { this.security = security })
val server = CoapUri.parse("coap://192.0.2.1")
// Two round trips in total (RFC 9668): message_1 and message_2 here, message_3 with the first request.
val (session, protection) = EdhocClientHandshake.over(client, server, initiatorConfig, crypto).runCombined()
security.install(client, server, protection)
client.get("coap://192.0.2.1/lights")
```

`EdhocClientHandshake` takes a `suspend (CoapRequest) -> CoapResponse` function, the shape of
`CoapClient.request`, which `over` passes; any other carrier, or a test double such as
`{ edhocServer.handle(it.payload, peer) }`, fits as well. `runCombined()` returns a `CombinedHandshake`,
which destructures into the session and the protection. A handshake fails with an `EdhocException`:
`EdhocPeerErrorException` when the server sent an EDHOC error, `EdhocTransferException` (with the
`response`) when it answered with something that is not EDHOC; `CoapException`s of the transfer pass through.
When the OSCORE context cannot be derived at the end because the `sequenceStore` or the crypto backend threw, the
run fails with an `EdhocException` (error code 1) with that failure as cause, as `EdhocServer` does on its side.
`PeerSecurity.install(client, uri, protection)` reports a lookup of the client's resolver that fails at runtime as
the client's requests do, a `CoapException` with `UNRESOLVED_HOST` and the failure as cause.
Both kinds are `KcoapException`s (kcoap-common), which `run`, `runCombined`, `EdhocServer.completeCombined`,
`PeerSecurity.install(client, uri, protection)` and `EdhocSession.oscoreContext` list with `CancellationException`
in `@Throws`, so Swift catches them as an `NSError`. A run that fails or is cancelled at any step
wipes what it holds (RFC 9528 §9.8): the ephemeral key X while it waits for message_2, PRK_4e3m and the
session's PRK_out and PRK_exporter after message_3.

`PeerSecurity` holds one protection per server, keyed by the transport endpoint the client's requests go
to; `install(client, uri, protection)` finds that endpoint with the client's own resolver. A request to a
server without a protection fails with `CoapException.Reason.SECURITY` (its cause the `MessageSecurityException`
of `PeerSecurity`) instead of going out in plaintext, except
for `unprotectedPaths` (the EDHOC resource); `PeerSecurity(allowUnprotected = true)` sends it in plain CoAP
on purpose. Discovery before the
handshake (`client.discover(server)` for `rt=core.edhoc`) asks `/.well-known/core`, so it takes a plain client or
`PeerSecurity(unprotectedPaths = setOf("/.well-known/edhoc", "/.well-known/core"))`, and an EDHOC resource at
another path needs that path in `unprotectedPaths`. A client with `QBlockMode.PROBE` and `withMessageSecurity`
runs EDHOC as it is: its Q-Block probe is not sent before `install`, and the first request after it probes.

## Verified by

- RFC 9668 Figure 4, byte for byte.
- Complete handshakes over the CoAP framing, sequential and combined, with and without message_4,
  cipher suite negotiation across the transfer and its limit, and every error path of RFC 9668 §3.3.1 and
  RFC 9528 Appendix A.2.3: each malformed request payload as an `EdhocException` with a generic diagnostic on
  the wire and only the type of the offending CBOR item in its message, logged with the payload as a length,
  responses that are not EDHOC as `EdhocTransferException` (`EdhocOscoreTest`). A client run that fails while
  waiting for message_2 (the exchange throws, a 4.04, cancellation) or after message_3 (rejected, a sequence
  number store failing) aborts its Initiator, wipes X and destroys its session
  (`EdhocOscoreTest.aFailedHandshakeWipesTheInitiatorAndItsSession`).
- The glue on its SPIs: `EdhocServer` on any `MutableSecurityContextRepository` (it checks C_R with `find`
  and adds with `addIfAbsent`), `CombinedRequestServer` around any `ServerMessageSecurity` (and a 4.00 when
  that layer does not take the rebuilt request), a handshake to a resource at another path, the crypto
  backend asked once per configuration by both sides, not per session; through the stack,
  `PeerSecurity.install(client, uri, protection)` resolving the endpoint the client's requests go to, also with
  a resolver of the client's own for a name (`FullStackTest`).
- OSCORE contexts derived on both sides interoperate. The OSCORE values themselves come from the
  EDHOC exporter, checked against RFC 9529 in `kcoap-edhoc`.
- Pending sessions (`EdhocServerSessionsTest`): C_R collisions with a session composing message_2 and
  with one completing, made deterministic with gates, and concurrent handshakes on real threads
  (`Stress.race`) that all draw the same C_R candidate; both fail without the reservation. Continuations
  from another peer (message_3, error message, combined request, another DTLS session) leave the session
  alone and run no cryptography; the bounds refuse before any cryptography, and a full server evicts the
  oldest session past its grace period; sessions expire on a `TestTimeSource`; a handler cancelled while
  message_2 is composed or message_3 processed gives back its C_R and place; dropped sessions have their
  ephemeral keys wiped; a second server on the same repository never replaces a context. Establishing is all
  or nothing: a repository that throws (before or after adding), a handler cancelled while it adds, a listener
  or a sequence number store that throws, each leave no context behind, no pending session and an unrun
  listener, and get "EDHOC failed" in a 5.00, also through `CombinedRequestServer`; a repository that cannot
  remove the context either still gets that answer, and an `Error` passes through after the same cleanup.

Not yet: the reverse message flow (A.2.2), Block-wise of the combined request beyond the first-block
rule, and live interop of the combined flow (planned with libedhoc and aiocoap).
