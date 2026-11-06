# 0009. The browser topology is cross-tested through libcoap's own forward-proxy

Date: 2026-11-06. Status: accepted.

## Context

A browser has only WebSockets, so it reaches CoAP devices on UDP through a gateway: `coap+ws` in, `coap`
out (RFC 8323 §4, RFC 7252 §5.7). WP-I1 (critique D in [target-architecture.md](../target-architecture.md))
asks for that topology end to end: a kcoap WebSocket client, libcoap's `coap-server -P` as the proxy and a
kcoap UDP server, with an OSCORE GET, an EDHOC handshake and an RFC 9668 combined request, the server
authorizing the client by its OSCORE identity. If libcoap cannot forward WebSockets to UDP, the plan's fallback
is a test-only kcoap forwarder.

libcoap v4.3.5b (the `kcoap/libcoap:v4.3.5b` image, configured with `--enable-proxy-code` and
`--enable-websockets`) was checked in its source, which the image keeps in `/src/libcoap`, and in live runs:
first libcoap on all three hops, then kcoap at both ends. What it does:

1. **It translates `coap+ws` to `coap`.** `coap-client -P coap+ws://127.0.0.1:<ws>
   coap://127.0.0.1:5683/time` through `coap-server -w <ws> -P ,kcoap-proxy` reached a libcoap origin on
   UDP 5683 and got its 2.05 back over the WebSocket.
2. **The direct proxy ignores the target.** For `-P ,<names>` (no next hop), `cmdline_proxy` sets
   `COAP_PROXY_DIRECT_STRIP` with a zeroed target URI (`examples/coap-server.c:1843-1844`), and
   `coap_proxy_get_session` reads Proxy-Uri or Proxy-Scheme only for the `COAP_PROXY_FORWARD` types
   (`src/coap_proxy.c:228`). The zeroed host resolves as `localhost` and the port as 5683: a request for
   `coap://127.0.0.1:47611/time` went to 127.0.0.1:5683 ("ICMP: Connection refused") and timed out.
3. **It then loses options.** The forwarded request gets the zeroed target's options first
   (`src/coap_proxy.c:630`): an empty Uri-Port (0). A request that names a port of its own then has two, and
   `coap_add_optlist_pdu` stops at the repeat (`src/coap_option.c:563`, `src/coap_pdu.c:613`, "Option number
   7 is not defined as repeatable"), so every later option is gone: Uri-Path, Content-Format, OSCORE.
4. **The next-hop proxy opens the target instead of the next hop.** For `-P <next hop>,<names>`
   (`COAP_PROXY_FORWARD`) it connects to the request's target and passes Proxy-Uri or Proxy-Scheme on
   unchanged, so the origin is asked to proxy: a libcoap origin answered 5.05, and kcoap's `CoapServer`
   answers 5.05 too (RFC 7252 §5.10.2).
5. **The reverse proxy works on any port** (`-f coap://127.0.0.1:47611`: the origin's 2.05 came back), but
   there the client addresses the gateway as if it were the device, so neither the forward-proxy request of
   RFC 7252 §5.7.2 nor the Class U target options of RFC 8613 §4.1.3.3 are exercised.
6. **OSCORE requests are forwarded only in one form.** Without an OSCORE configuration of its own, the proxy
   leaves a request with the OSCORE option alone only when it carries Proxy-Scheme and a Uri-Host that is not
   one of its names (`src/coap_net.c:3940-3972`). With Proxy-Uri, the form RFC 8613 §4.1.3.3 describes and
   kcoap-oscore sends, it tries to decrypt the request itself, logs "OSCORE: Not enabled"
   (`src/coap_oscore.c:858`) and drops it without an answer.
7. **It adds a Content-Format to responses.** A forwarded response with a payload and no Content-Format gets
   text/plain (`media_type` defaults to 0 in `coap_proxy_forward_response`, `src/coap_proxy.c:764`). Outside
   a protected response that is a Class E option, which OSCORE discards when it verifies (RFC 8613 §8.4).
8. **It corrupts request bodies of several blocks.** `coap_proxy_forward_request` copies the body ("Need to
   take a copy of the data as request PDU may go away before all data is transmitted") but hands the original
   pointer, not the copy, to `coap_add_data_large_request` (`src/coap_proxy.c:708`), which reads the later
   blocks from it after the incoming request is freed. In three runs, each of the four 2445-byte bodies kcoap's
   device received through the proxy, sent to it over UDP or over WebSockets in 1024-byte blocks, had 40 changed
   bytes at offsets 1064 to 1103; libcoap to libcoap without the proxy kept every byte. Post-quantum EDHOC (suite -24) fails on it: its 2.4 KB message_3
   arrives changed and the device answers "Decryption of message_3 failed". Response bodies are copied right
   (`src/coap_proxy.c:811`): message_2 of that run, 3197 bytes in Block2 from the device, reached the client
   whole and verified.
9. The EDHOC option (21, RFC 9668 §3.1) is unknown to 4.3.5b; it is Safe-to-Forward, so the proxy accepts it in
   a proxy request and passes it on.
10. **It forwards a deregistration on a new token.** Every forwarded request gets a token of the proxy's own
    (`coap_proxy_forward_request`, `src/coap_proxy.c:590`), so the GET (outer FETCH) with Observe 1 that
    deregisters on the registration's token (RFC 7641 §3.6) reaches the device on another one. The device answers
    it as a plain request and keeps the observer; the proxy keeps the registration's entry while notifications
    come (`src/coap_proxy.c:883`), acknowledges them and passes them on to a client that has forgotten the token.
    Registration and notifications themselves cross intact, outer Observe included (RFC 8613 §4.1.3.5).

## Decision

- WP-I1 runs through libcoap's own forward-proxy, `coap-server -P ,kcoap-proxy` with a WebSocket listener,
  and not through a kcoap forwarder. libcoap does translate `coap+ws` to `coap`, and its gaps lie on paths the
  scope can stay off without testing less of kcoap.
- The device listens on port 5683, the default, of a loopback address of the test's own, 127.a.b.c chosen at
  random: the target the requests name and the one the direct proxy opens (item 2), since the proxy
  container's `/etc/hosts` maps `localhost` to that address alone. Linux routes all of 127.0.0.0/8 to the
  loopback interface, so no setup is needed; runs at the same time (several worktrees, `scripts/test interop`
  next to `scripts/test coverage`) each have their own address, so they neither collide on the port nor reach
  each other's device, and a server on 127.0.0.1:5683 is no obstacle either. Once libcoap honours the target,
  the tests run unchanged, with a proxy that reads a missing Uri-Port as libcoap does (next item).
- The client names the device with Proxy-Scheme and Uri-Host (RFC 7252 §5.10.2): the form libcoap forwards
  under OSCORE (item 6). It leaves Uri-Port out, against the letter of RFC 7252: a proxy composes the target
  as §6.5 says, where a missing Uri-Port stands for the request's destination port, the proxy's own, so §6.4
  step 7 calls for Uri-Port 5683 here. Uri-Port is left out only to avoid libcoap 4.3.5b's repeated Uri-Port
  (item 3), relying on the proxy defaulting a missing one to the Proxy-Scheme's port, as libcoap does
  (`coap_get_uri_proxy_scheme_info`); a proxy that follows §6.5 to the letter would look for the device on the
  proxy's own port. kcoap's client has no proxy setting, so a test-only `ProxyRoute` adds the options; the
  client proxy setting (checklist coap-core-7) must follow §6.4 step 7.
- Request bodies stay within one message on each side of the proxy (item 8): OSCORE's inner Block1 and Block2
  give one block per outer message, and the EDHOC messages of the classical suites fit one message.
  Post-quantum EDHOC through this proxy waits for a libcoap fix.
- Items 2 and 3, 6, 7 and 10 are pinned by `upstream…` tests in `BrowserTopologyInteropTest`, which fail, and
  are flipped, once libcoap changes. Item 8 is undefined behaviour, so no test can expect a stable outcome; it is
  documented here and in [interop/README.md](../../interop/README.md), as libcoap's descending missing-block
  list is.
- Should the scope grow to devices on other ports, or to request bodies of several blocks, before libcoap is
  fixed, the plan's fallback applies: a test-only forwarder in kcoap-edhoc-oscore's `jvmTest`, with a record
  that supersedes this one.

## Consequences

- One run covers what a browser deployment needs from kcoap: OSCORE end to end through an OSCORE-unaware proxy
  with the Appendix B.1.2 challenge, inner block-wise bodies both ways and an observation, EDHOC over CoAP, the
  RFC 9668 combined request and an EDHOC error message, all with the device authorizing on the client's
  `OscoreIdentity`, or on the `EdhocIdentity` EDHOC established (a client EDHOC authenticated but the policy
  does not admit gets a protected 4.03), while every request comes from one address, the proxy's.
- Through a proxy every client shares the proxy's address on the device's side, and libcoap's proxy shares one
  upstream session among them (`coap_proxy_get_session`). `EdhocServer`'s protections against other peers
  (WP-SEC1) then cover the whole gateway: one browser can take all `maxPendingSessionsPerPeer` places (8 by
  default) and hold off EDHOC for every other client of that gateway, and a pending session waits for message_3
  from the proxy's address, so any browser behind it can send a continuation for another's pending session.
  That gap is tracked in checklist edhoc-oscore-x2: it needs a per-client discriminator or a documented gateway
  deployment rule. The CoAP server's per-peer bounds, observers included, count the gateway as one peer as
  well, and item 10 fills them with observations nobody receives. On the client's side `PeerSecurity` keys
  protections by the transport endpoint, the proxy, so one client cannot hold protections for two devices
  behind one proxy; `OscoreClient(ClientContextResolver)` sees the request and could route on its Uri-Host
  instead.
- The interop tests need nothing on UDP port 5683 of the wildcard address, which would hold that port on every
  loopback address; the device's start fails with a message naming this record otherwise. Like the other
  interop tests they need Linux, here for its loopback routing as well as Docker's host network.
- No proxy container outlives its test: removing it does not depend on keeping its log, a failed `docker run`
  removes what it may have started, and `timeout` ends the proxy after ten minutes when nothing removed it, as
  when the test JVM is killed (`LibcoapProxyTest`).
- kcoap still has no proxy of its own (checklist coap-core-7) and no client proxy setting; a kcoap cross-proxy
  (E11) is not needed for this cross-test. `coaps+ws` to the gateway is not covered: it needs a TLS
  certificate on the proxy and trust configuration in the client, and moved to T1; the same run from a real
  browser moved to PL-4 ([plan-qblock-and-platforms.md](../plan-qblock-and-platforms.md), Amendment (I1)).
