# Security policy

kcoap implements message security (OSCORE, RFC 8613) and key exchange (EDHOC, RFC 9528), so a flaw in
it can expose the devices and services that rely on it. Thank you for reporting one privately.

## Supported versions

kcoap is before 1.0. Security fixes go to the latest release and to `main`; older releases do not get
fixes, so upgrade to the latest one.

| Version | Security fixes |
|---|---|
| `main` | yes |
| latest 0.x release | yes |
| older 0.x releases | no |

No version has been released yet, so today this means `main`.

## How to report

**Do not report a vulnerability in a public issue, pull request or comment.**

1. **Preferred:** report it through GitHub's private vulnerability reporting:
   https://github.com/adihack/kcoap/security/advisories/new. Only the maintainer and you see the
   report, and the fix can be prepared in a private fork.
2. **Fallback:** email **adrian.soltys@seelso.net** with "kcoap security" in the subject. If you need an
   encrypted channel, say so in a first short email and we will agree on one.

## What to include

| Item | Examples |
|---|---|
| Affected module and version | `kcoap-oscore` 0.1.0, or the commit of `main` |
| Platform and crypto backend | JVM 21, Android API 26, iOS, Linux native, a browser, Node.js; `kcoap-crypto-default`, `kcoap-crypto-bc` or your own `CryptoBackend` |
| Transport | UDP, TCP or WebSocket, and the URI scheme |
| Security mode | no security; OSCORE with its AEAD and HKDF algorithms; EDHOC with its method, cipher suite and credential type; the EDHOC + OSCORE combined request |
| What an attacker can do | read, forge or replay messages, impersonate a peer, bypass an access policy, exhaust memory, crash the endpoint |
| How to reproduce | steps, a test or a small program; the RFC sections involved |
| Evidence | logs or a packet capture, **without keys**: remove private keys, OSCORE Master Secrets and Salts, and EDHOC secrets. kcoap does not log keys, but TRACE logs contain message bytes, so check them first |
| Credit | how you want to be credited, or that you prefer not to be |

## What happens next

kcoap has one maintainer, so these are targets, not guarantees:

| Step | Target |
|---|---|
| Confirm receipt | within 5 working days |
| First assessment: confirmed or not, and how severe | within 14 days |
| Status updates until it is resolved | at least every 14 days |
| Fix released for a confirmed vulnerability | as soon as practical, within the embargo below |

There is no bug bounty.

## Coordinated disclosure

- **Embargo.** Please keep the vulnerability private for **90 days** from the report, or until a fixed
  release is published, whichever comes first. If a fix needs longer, for example because other
  implementations or a specification must change too, we agree on a new date together. If the
  vulnerability is exploited in the wild, the fix and the advisory may be published earlier.
- **Advisory.** A fixed vulnerability is published as a GitHub security advisory, with a CVE ID where
  one is warranted, and listed in [CHANGELOG.md](CHANGELOG.md).
- **Credit.** Reporters are credited in the advisory and in the changelog, unless they prefer not to
  be.
- **Other implementations and specifications.** If the problem also affects other implementations,
  such as libcoap or libedhoc, or comes from an RFC itself, we coordinate with their maintainers or the
  responsible IETF working group, with your agreement.

## Scope

| In scope | Out of scope |
|---|---|
| Protocol and crypto handling in the published modules: CoAP message parsing and the endpoint, Block-wise and Q-Block, Observe; OSCORE protection, verification, replay protection and sequence numbers; EDHOC handshakes, credentials and key derivation; the EDHOC to OSCORE glue; access policies and response verification; the crypto backends; how the transports handle untrusted input | the interop harness in `interop/` and the samples in `samples/` |
| Resource exhaustion or amplification that a remote peer can cause | vulnerabilities in dependencies themselves: report them upstream, and tell us if kcoap's use of a dependency makes them exploitable |
| | attacks that need control of the device, its keys or its storage |
| | features documented as not implemented yet in [docs/rfc-compliance.md](docs/rfc-compliance.md), such as DTLS, TLS for `coaps+tcp` and OSCORE Appendix B.2; but a way their absence makes a deployment unsafe that the documentation does not warn about is in scope |

A weakness in the CI workflows or the release process should be reported privately too.

## Security design

- [ADR 0008](docs/adr/0008-fail-closed-message-security.md): message security fails closed. Servers
  check an access policy before any processing, and clients act only on verified responses.
- [Architecture: layers](docs/architecture.md#layers): the two kinds of security (OSCORE end to end,
  DTLS and TLS hop by hop), where authorization sits and which identity each layer reports.
- [Architecture: DTLS](docs/architecture.md#dtls): the channel-security plan, implemented last.
- [Code style: errors](docs/code-style.md#errors) and [logging](docs/code-style.md#logging): no secrets
  in exceptions or logs, constant-time comparison of tags.
