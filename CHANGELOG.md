# Changelog

All notable changes to kcoap. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/). Before 1.0, a minor version may break the
API; every breaking change is listed here with a migration note.

## Unreleased

Nothing has been released yet. The API is still changing as the refactoring plan in
[docs/target-architecture.md](docs/target-architecture.md) lands; each work package that breaks the API
adds its entry and migration note below.

### Added

- CoAP over UDP (RFC 7252) with Observe (RFC 7641), Block-wise (RFC 7959), FETCH/PATCH/iPATCH (RFC 8132),
  Echo and Request-Tag (RFC 9175), No-Response (RFC 7967), Hop-Limit (RFC 8768) and link format (RFC 6690).
- CoAP over TCP and WebSockets (RFC 8323): CSM, Ping/Pong, Release, Abort, BERT.
- OSCORE (RFC 8613) with Appendix B.1 sequence-number persistence and Echo-based replay-window recovery.
- EDHOC (RFC 9528, RFC 9529 traces), including the post-quantum suite -24 of draft-ietf-lake-pqsuites.
- EDHOC over CoAP and the combined EDHOC + OSCORE request (RFC 9528 Appendix A, RFC 9668).
- Targets: JVM, iOS, macOS, Linux native, JS and Wasm, with capability-based crypto selection.
- Live interop tests against libcoap (CoAP, OSCORE, TCP, WebSockets) and libedhoc (both roles, PQ).
- One-command test suites (`scripts/test`) and CI/CD (tests on Linux and macOS, tag-driven releases).
