# 0005. One CoAP endpoint over an internal PeerLink seam

Date: 2026-11-05. Status: accepted.

## Context

The request/response layer was first written on top of UDP messaging, so CON/NON, Message IDs and
transmission outcomes leaked into the client, the server, Observe and Block-wise. RFC 8323 then added
TCP and WebSockets, where none of these exist. Splitting the endpoint into one class per transport, or
making it an interface, would duplicate the exchange layer.

## Decision

- One `CoapEndpoint` runs one exchange layer (tokens, matching, Block-wise, Observe, Echo/Request-Tag,
  message security) over an internal `PeerLink` per peer.
- `PeerLink` offers send, respond, acknowledge, reject and ping; it reports limits (maximum message size,
  BERT) and capabilities (has Reset, has message types, is ordered), a `DeliveryOutcome`, and when it
  closes.
- `DatagramLink` implements it over `UdpMessaging` and a `DatagramTransport`; `ConnectionLink` over
  `ReliableMessaging` and a `Connection`. Datagram-only behaviour such as the notification CON/NON policy
  stays in `DatagramLink`.
- The seam is internal until kcoap publishes a sans-IO API.

## Consequences

- Block-wise, Observe and OSCORE behave the same over every transport and are tested by one conformance
  suite run over both links.
- The public `transport`, `maxDatagramSize` and `NoDatagramTransport` of `CoapEndpoint` go away.

## As built (WP-B1)

- `PeerLink` (`kcoap-coap`, `stack/PeerLink.kt`) has four capabilities, not three: `verifiesAddress` joins
  `hasReset`, `hasMessageTypes` and `isOrdered`, because whether RFC 9175 §2.4's address check applies is a
  property of the link (a connection's handshake verified the address), not of message types.
- `DeliveryOutcome` has a fifth value, `Released`: a Non-confirmable message's NSTART hold ended. An
  observation sends its next notification then, and a request must not take it for an acknowledgement.
- The datagram link is one driver per transport (`DatagramLink`, with its own lock, receive and timer loops)
  and hands out an equal `PeerLink` per peer; `ConnectionLink` wraps each `ConnectionDriver`.
- `DatagramLink` owns `BodyLease`; a lease towards a connection peer is granted at once without opening the
  connection. The Q-Block drivers take their body mode from the link's capabilities.
- The notification policy is `NotificationPolicy` (`Interspersed` on datagram links, `Untyped` on
  connections), which each observation gets from its registration's link.
- The endpoint picks the link in one exhaustive `when` on `Endpoint`; a peer without a transport fails with
  `CoapException.Reason.TRANSPORT_UNAVAILABLE`.
- The conformance suites run on `kcoap-testkit`'s `InMemoryLink`: `ExchangeConformanceTest` and
  `OscoreOverLinksTest`, the datagram side through `InMemorySessions`, a stand-in for DTLS sessions, so that a
  lost datagram link is an ended session.
