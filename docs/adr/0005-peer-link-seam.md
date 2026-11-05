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
