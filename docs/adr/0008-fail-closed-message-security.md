# 0008. Fail-closed message security: resource access policies and verified responses

Date: 2026-11-05. Status: accepted.

## Context

Findings SEC-3 and SEC-4 of the architecture review (WP-SEC2 in
[target-architecture.md](../target-architecture.md)):

- **Server.** `CoapServerConfig(security = OscoreServer(...))` protected nothing by itself. A request without
  the OSCORE option was processed as a plain request, so every handler had to check `securityIdentity`
  itself, and before any handler ran an unauthenticated peer could buffer a 1 MiB Block1 or Q-Block1 body,
  register, replace or cancel observations (keyed by endpoint and token only), get Q-Block2 bursts, and list
  every resource in `/.well-known/core`. For an EDHOC peer the only identity a handler saw was the Recipient
  ID, a connection identifier the server allocates and may hand out again.
- **Client.** OSCORE sends its errors unprotected (RFC 8613 §8.2), and anyone who sees the token can send
  such an error. The client acted on them like on verified responses: an unprotected 4.01 with Echo made it
  send a protected POST again under a fresh Partial IV, which OSCORE's replay protection cannot catch, so the
  POST ran twice. QB-5 had narrowed this for requests carrying Inner Q-Block only, at the cost of an
  unprotected 4.02 from a server without OSCORE ending in a timeout. Separately, a missing `install()` or a
  resolver returning null sent requests in plaintext without a word.

## Decision

**Server: an access policy on every resource.**

- `fun interface AccessPolicy { suspend fun permits(request: AccessRequest): Boolean }` in kcoap-coap's
  server package. `AccessRequest` carries the method, the Uri-Path, the resource (null for a path that does
  not exist), the peer, the message-security identity and the channel `PeerIdentity`. Built-ins: `Public`,
  `MessageSecurityRequired` (an OSCORE identity) and `Authenticated` (an OSCORE identity or a DTLS/TLS peer
  identity, the two kinds of security of docs/architecture.md).
- `CoapResource.access` (null inherits from the parent) and `ResourceBuilder.access`; at the top level of the
  routes it is the root's.
- `CoapServerConfig.defaultAccess` governs whatever sets nothing. **Its default is
  `MessageSecurityRequired` when `security` is set, and `Public` otherwise.** Configuring message security
  therefore fails closed. A plain server, with no security to require, behaves as before. A server that also
  serves DTLS-authenticated clients without OSCORE sets `Authenticated`.
- `/.well-known/core` and kcoap-edhoc-oscore's `EdhocResource` are `Public` of their own, so discovery and the
  handshake work as before.
- The server asks the policy right after the resource lookup, before anything else for the request: option
  checks, Block1 assembly, the Block2 cache, held-back responses, the Q-Block layer (buffering, bursts, the
  address check), Observe registration and deregistration, and the handler. A refused request gets **4.01**
  when it has no message-security identity (RFC 7252 §5.9.2.2: authenticate first) and **4.03** when it has
  one (§5.9.2.4), protected if the request was, without a diagnostic. A path that does not exist is governed
  by its nearest existing ancestor and gets 4.04 only when that policy permits it, so the names below a
  protected resource do not leak. A policy that throws is answered with 5.00, like a failing handler. The
  policy is found in the same walk down the tree as the resource, the deepest one set on the way, so a
  resource removed at that moment, which has no parent any more, keeps the policy it inherited.
- **Deviation from RFC 7252 §5.4.1.** Since the policy comes before the option check, a refused request with
  an unrecognized critical option gets 4.01 or 4.03 instead of 4.02, and a refused Non-confirmable one gets
  that response instead of a Reset. On purpose: which options a resource recognizes is the resource's, and a
  caller it refuses learns nothing about it, not even that; the request would be refused just the same once
  its options were fixed. docs/rfc-compliance.md records it in the RFC 7252 §5 row.
- `/.well-known/core` lists a resource only when its policy permits a GET by the caller, and marks the
  `MessageSecurityRequired` ones with `osc` (RFC 8613 §9). A policy that throws leaves out its own link and
  no other, so the document, the Public EDHOC resource among it, is still served.
- Observe registrations are kept per endpoint and token, as RFC 7641 §4.1 requires, **and belong to the
  message-security identity that made them**: §4.1 replacement and §3.6 deregistration work within that
  identity only, and a registration of another identity, or none, on the same endpoint and token is neither
  added nor a replacement: it is answered as a plain GET, without Observe (§4.1 lets a server decline to add
  an observer), so nobody can have notifications sent to someone else's token.
- **Who:** `SecurityContext.identity` (default: its `OscoreIdentity`) is what `OscoreServer` reports for every
  request the context verifies. `EdhocSession.oscoreContext`, and so `EdhocServer`, attach an
  `EdhocIdentity` that carries the peer credential EDHOC authenticated and its identifier. The identity
  belongs to the context object, not to the Recipient ID, so a later session that gets the same C_R has its
  own. A provisioned context may name its peer through `SecurityContext.derive(identity = ...)`.

**Client: the stack acts only on verified responses.**

- `ProtectedRequest.unprotectResponse` returns `ResponseVerification`: `Verified(message)` or
  `Unverified(message, reason)`; it still throws `MessageSecurityException` for a response to drop. kcoap-oscore
  hands every unprotected 4.xx and 5.xx on as `Unverified`, for every request alike, and drops any other
  unprotected response. QB-5's narrower rule for Inner Q-Block requests is part of this one: since nothing
  acts on an unverified response, it cannot steer a body either.
- For an unverified response the stack does nothing a response would cause: no Echo retry (RFC 9175 §2.3,
  RFC 8613 Appendix B.1.2 challenges are protected and still retried once), no Block1 restart or Block2
  follow-up, no Q-Block step, fallback or capability change, no end of an established observation, no
  emission to its collectors. A request that has no verified response yet ends.
- **How the application sees it: an exception, not a response.** The request fails with
  `CoapException(Reason.UNVERIFIED_RESPONSE)`, whose new `response` property holds the unverified response as
  it came. An established observation drops unverified responses (a real server sends no unprotected
  notifications), also on the requests for the rest of a notification body: a notification whose Block2 or
  Q-Block2 follow-up gets only an unverified answer is dropped, and the next change brings a fresh one. A
  registration still waiting for its answer fails the same way; on a reliable transport the client then
  deregisters (RFC 8323 §7.4), since the server's own answer, which the unverified one came ahead of, may
  have registered it. This is distinct from a
  timeout, so the unprotected 4.02 of a server without OSCORE now ends a Q-Block request at once, and
  distinct from a returned `CoapResponse`, so code that branches on `response.code` cannot act on a forgery
  by accident.
- **Plaintext only on purpose.** `OscoreClient` fails a request with `MessageSecurityException` when its
  `ClientContextResolver` returns null, and `PeerSecurity` when no protection is installed for the peer,
  before anything is sent. `allowUnprotected = true` on either restores plaintext for such requests;
  `PeerSecurity.unprotectedPaths` (the EDHOC resource) stays. There is no setting in the core:
  `ClientMessageSecurity.protectRequest` returning null is an explicit choice of the security layer.
- **The Q-Block probe follows the same rule.** A client in `QBlockMode.PROBE` with `withMessageSecurity` probes
  before a request to a server it knows nothing about, the EDHOC request included. A probe the security layer
  refuses to send (`MessageSecurityException`, as `PeerSecurity` before `install`) finds nothing and keeps
  nothing: that request goes with RFC 7959 under its own protection, so the EDHOC request goes in the clear
  and any other fails closed, and the first request after `install` probes. Discovery before the handshake
  (`/.well-known/core`) is not in `unprotectedPaths`, so it takes a plain client or an extended set.

## Alternatives considered

- **Default `Public` with a recommendation** (SEC-3's additive option). Rejected: the finding is that
  configuring OSCORE protected nothing, and a README line does not change what a forgotten check serves.
- **Default `Authenticated` with security set.** Rejected as the default: a server that configured OSCORE
  would then take plaintext from any DTLS-authenticated client, which is not what configuring OSCORE says.
  It stays one parameter away.
- **Return an unverified error as a `CoapResponse` with a marker** (SEC-4 item 3). Rejected: existing code
  checks `response.code` and would act on the forgery; an exception cannot be ignored silently.
- **Wait for a verified response until the timeout, then report the unverified one.** Rejected: the genuine
  case, a server that lost its context and answers 4.01, would take the whole request timeout (up to
  EXCHANGE_LIFETIME) to surface. Ending at once lets a party on the path end a request, which it can do by
  dropping packets too, but never makes it run twice.

## Consequences

- Breaking: servers with message security refuse unprotected requests by default; handlers that served
  them mark their resources `Public` or set `defaultAccess`. Clients catch
  `CoapException(UNVERIFIED_RESPONSE)` where they used to get an unprotected error response, and see
  `MessageSecurityException` where they used to send plaintext. The SPI's return type changed; only kcoap's
  modules implement it. Handlers that checked `securityIdentity is OscoreIdentity` see an `EdhocIdentity` for
  EDHOC peers. Migration notes are in [CHANGELOG.md](../../CHANGELOG.md).
- A request that gets an unverified error may or may not have been processed. Repeating a non-idempotent
  request is the application's decision, made with that knowledge; running EDHOC again is the usual remedy
  for a 4.01 from a server that lost its context.
- Outer Echo from an OSCORE-unaware proxy (RFC 9175 §2.3 with an unprotected 4.01) is no longer answered;
  checklist oscore-x3 records that deferral.
- Policies are asked per request and per listed link: they should be quick, and they see identities, never
  keys.

## Amendment (WP-B3)

WP-B3 gave the CoAP facades one exception type, so two statements above now read differently for a `CoapClient`
caller:

- A request that the security layer refuses to protect, as `OscoreClient` without a context or `PeerSecurity`
  before `install`, still fails before anything is sent, but the client throws `CoapException` with
  `CoapException.Reason.SECURITY`, whose `cause` is the `MessageSecurityException` (or whatever else
  `protectRequest` threw). Where "Consequences" says clients "see `MessageSecurityException`", they see it as
  that cause. The SPI itself, `ClientMessageSecurity.protectRequest`, still throws it.
- The Q-Block probe skips on `SECURITY` where it skipped on the raw `MessageSecurityException`, with the same
  effect: it finds and keeps nothing, and the request goes with RFC 7959 under its own protection.
