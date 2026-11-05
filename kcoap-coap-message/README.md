# kcoap-coap-message

CoAP's wire vocabulary: the message model, options, URIs and link format, the codecs for every CoAP
framing, and the message-security SPI that OSCORE implements. Common Kotlin with no I/O, no coroutines
and no state of its own; it depends only on `kcoap-common` and `kcoap-transport` (for `Endpoint` and
`CoapScheme`).

It is the stable layer the rest builds on. [kcoap-coap](../kcoap-coap/README.md), the endpoint stack with
`CoapClient` and `CoapServer`, depends on it with `api`, so an application that uses the stack gets it
without declaring it. [kcoap-oscore](../kcoap-oscore/README.md) depends on this module only, not on the
stack, so OSCORE can protect messages of another CoAP stack, a proxy or a gateway.

## Packages

The packages kept their names when they moved here from kcoap-coap, so imports did not change.

| Package | What |
|---|---|
| `dev.kcoap.coap` | `CoapMessage` and its `Builder`, `CoapCode`, `CoapMessageType`, `CoapOption`, `OptionSet` and its `Builder`, the option registry `CoapOptions` with `OptionDefinition` and each option's OSCORE class, `ContentFormat`, `BlockOption`, `CoapUri`, the application model `CoapRequest` and `CoapResponse`, `LinkFormat` and `WebLink` (RFC 6690), and `SignalingOptions` (RFC 8323 §5.2) |
| `dev.kcoap.coap.codec` | `MessageCodec`; `UdpMessageCodec` (RFC 7252 §3); `ReliableMessageCodec` for TCP and WebSocket framing and `ReliableStreamDecoder` for byte streams (RFC 8323 §3.2, §4.2); `CoapFormatException`. `OptionCodec`, the option encoding shared with kcoap-oscore, is `@InternalKcoapApi` |
| `dev.kcoap.coap.security` | The message-security SPI: `ClientMessageSecurity`, `ProtectedRequest`, `ServerMessageSecurity`, `UnprotectedRequest`, `SecurityIdentity`, `ResponseVerification`, `MessageSecurityException` |

```kotlin
val request = CoapMessage.request(CoapCode.GET) {
    messageId = 0x1234
    token = byteArrayOf(0x4a)
    options { uriPath("sensors/temperature") }
}
val datagram = UdpMessageCodec.encode(request)          // RFC 7252 §3
check(UdpMessageCodec.decode(datagram) == request)
val frame = ReliableMessageCodec.WebSocket.encode(request)  // RFC 8323 §4.2: no type, no Message ID
```

## Implemented

- **Messages, RFC 7252 §3 and §5.10**: header, token, options and payload; every option of the registry
  with its format, length bounds and repeatability (Uri-*, Location-*, ETag, If-Match, If-None-Match,
  Content-Format, Accept, Max-Age, Proxy-*, Size1, plus Observe from RFC 7641, Block1, Block2 and Size2 from
  RFC 7959, No-Response from RFC 7967, OSCORE from RFC 8613, Hop-Limit from RFC 8768, Echo and Request-Tag
  from RFC 9175, Q-Block1 and Q-Block2 from RFC 9177, EDHOC from RFC 9668), and each option's OSCORE class
  (RFC 8613 §4.1). Unknown options are kept and treated as Class E.
- **URIs, RFC 7252 §6 and RFC 8323 §8**: `coap`, `coaps`, `coap+tcp`, `coaps+tcp`, `coap+ws` and `coaps+ws`
  URIs to and from Uri-Host, Uri-Port, Uri-Path and Uri-Query, with percent-encoding.
- **Link format, RFC 6690**: parsing and serializing `/.well-known/core` documents, with the `osc`
  attribute of RFC 8613 §9.
- **Codecs**: the UDP header (RFC 7252 §3), the TCP and WebSocket framings of RFC 8323 §3.2 and §4.2, and a
  stream decoder that bounds the message size. Malformed input fails with `CoapFormatException`, never with
  another exception.
- **Message-security SPI**: where RFC 8613 Figure 1 puts OSCORE, between the request/response layer and the
  messaging layer. kcoap-coap's stack calls it, kcoap-oscore implements it, and kcoap-edhoc-oscore decorates
  it (RFC 9668).

## Not here

Everything that sends, waits or keeps state per peer is in kcoap-coap: the endpoint and its messaging
layers, tokens and Message IDs, Block-wise, Observe, Q-Block, the client and the server, and
`CoapException`, which reports how an exchange failed.

## Verified by

- `UdpMessageCodecTest`: RFC 8613 Appendix C messages byte for byte, and the RFC 7252 §3 header rules.
- `ReliableMessageCodecTest`: the RFC 8323 §3.2 length forms and the WebSocket framing (§4.2).
- `CodecFuzzTest`: mutated messages from `kcoap-testkit`'s `ByteMutator` either decode or fail with
  `CoapFormatException`.
- `CoapModelTest` and `CoapOptionsTest`: codes, options, builders, Block options, URIs and the option
  registry.
- In kcoap-coap, where they need the stack: link format in `DiscoveryTest`, the signaling options in
  `SignalTest`, and the message-security SPI with a fake OSCORE-like layer in `MessageSecurityTest`,
  `AccessPolicyTest` and `UnverifiedResponseTest`. kcoap-oscore implements the SPI against RFC 8613
  Appendix C.
- Line coverage in the root Kover report (`./gradlew koverXmlReport`), which counts the tests of every
  module: 98.7% for `dev.kcoap.coap`, 100% for `dev.kcoap.coap.codec` and 93.8% for
  `dev.kcoap.coap.security`. This module's own tests alone cover 69.5%
  (`./gradlew :kcoap-coap-message:koverXmlReport`).
