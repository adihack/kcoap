# kcoap-oscore

Object Security for Constrained RESTful Environments (RFC 8613): end-to-end protection of CoAP
requests and responses that survives proxies and transport changes.

## Using it

```kotlin
val crypto = DefaultCryptoBackend()
val context = SecurityContext.derive(
    SecurityContextParameters(
        masterSecret = secret,
        senderId = byteArrayOf(0x01),
        recipientId = byteArrayOf(0x02),
    ),
    crypto,
)

// Client side: protect requests to a peer, verify its responses.
val client = OscoreClient(context)

// Server side: verify requests by 'kid' (and 'kid context'), protect the responses.
val repository = InMemoryContextRepository().apply { add(serverContext) }
val server = OscoreServer(repository)
```

Both plug into the CoAP stack through `dev.kcoap.coap.security` (`ClientMessageSecurity`,
`ServerMessageSecurity`), at the place RFC 8613 Figure 1 puts OSCORE: below Block-wise and Observe,
above retransmission and deduplication.

## What is implemented

| RFC 8613 | Feature |
|---|---|
| §3.2 | Context derivation (HKDF, any COSE AEAD from `kcoap-crypto`) with Master Salt and ID Context |
| §3.2.2, §7.4 | Replay window (RFC 6347 sliding window, 32 by default), atomic check and update |
| §4 | Inner/Outer option split from the option registry; unknown options are Class E |
| §4.1.3.3 | Proxy-Uri decomposition |
| §4.1.3.5 | Observe: Inner and Outer, FETCH / 2.05 outer codes, notification Partial IVs, freshness and replay of notifications |
| §4.2 | Outer Code POST / 2.04 |
| §5 | COSE_Encrypt0, nonce construction, AAD (Enc_structure over aad_array) |
| §6 | OSCORE option compression ('kid', 'kid context', Partial IV) |
| §7.2.1 | Sequence numbers below 2^40; the context stops when exhausted |
| §8 | Request and response protection and verification, error responses with the RFC's diagnostics |
| App. B.1.1 | Sequence-number persistence through `SequenceNumberStore`, reserving blocks ahead |

Not yet: Appendix B.1.2 (replay-window recovery with Echo after a server reboot), Appendix B.2
(context re-derivation), outer Block-wise for proxies, OSCORE over HTTP (§11).

## Verified by

- RFC 8613 Appendix C.1 to C.8: every key, IV, nonce, `info`, AAD and protected message, byte for byte,
  on both the client and the server side.
- End-to-end exchanges through the SPI, Observe ordering and replay, every error path.
- Interop with libcoap: planned with the CoAP stack (`interop/README.md`).
