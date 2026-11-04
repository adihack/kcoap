# kcoap-edhoc-oscore

EDHOC as the key establishment for OSCORE, over CoAP:

| Spec | What | Where |
|---|---|---|
| RFC 9528 App. A.1 | OSCORE context from an EDHOC session: exporter-derived Master Secret and Salt, application AEAD and HKDF, Sender/Recipient IDs from C_R/C_I (Table 14) | `EdhocSession.oscoreParameters()`, `oscoreContext()` |
| RFC 9528 App. A.2 | EDHOC over CoAP, forward flow: POST to `/.well-known/edhoc`, `true`/C_R prefixes, Content-Formats 64/65, error responses (A.2.3) | `EdhocOverCoap`, `EdhocServer.handle()`, `EdhocClientHandshake.run()` |
| RFC 9668 §3 | EDHOC + OSCORE combined request: message_3 on the first OSCORE request, the EDHOC option, server processing order and errors | `CombinedRequestSecurity`, `CombinedRequestServer`, `EdhocClientHandshake.runCombined()` |
| RFC 9668 §4 | Connection identifiers usable as OSCORE IDs: C_R unique among sessions and Recipient IDs, never C_I | `EdhocServer` |

## Server

```kotlin
val contexts = InMemoryContextRepository()                     // shared by EDHOC and OSCORE
val edhoc = EdhocServer(responderConfig, crypto, contexts)
val security = CombinedRequestServer(edhoc, OscoreServer(contexts))
// Route POST /.well-known/edhoc to edhoc.handle(request); give `security` to the CoAP server.
```

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

Not yet: the reverse message flow (A.2.2), Block-wise of the combined request beyond the first-block
rule, and live interop of the combined flow (planned with libedhoc and aiocoap).
