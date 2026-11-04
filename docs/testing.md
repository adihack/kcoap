# Testing

kcoap is tested at three levels, from the most precise to the most realistic.

## 1. RFC test vectors, byte for byte

Every protocol module checks the examples its RFCs publish, value by value. A test fails on the
first byte that differs, which pins down the exact step that is wrong.

| Module | Vectors |
|---|---|
| `kcoap-crypto` | FIPS 202 (SHAKE256), NIST SP 800-185 (KMAC256) |
| `kcoap-crypto-default` | RFC 3610 (AES-CCM, all 24), RFC 5869 (HKDF), RFC 7748 (X25519), RFC 8032 (Ed25519), RFC 8613 Appendix C (AEAD and key derivation) |
| `kcoap-crypto-bc` | NIST ACVP ML-KEM and ML-DSA key generation, encapsulation, signing |
| `kcoap-cbor` | RFC 8949 Appendix A |
| `kcoap-cose` | RFC 8613 C.4 (AAD), RFC 9529 credentials |
| `kcoap-coap` | RFC 8613 Appendix C messages (codec) |
| `kcoap-oscore` | RFC 8613 Appendix C.1–C.8, every intermediate value, both sides |
| `kcoap-edhoc` | RFC 9529 traces |

Vectors are extracted from the RFC text by scripts in `tools/`, not typed in by hand.

## 2. Differential tests against independent implementations

On the JVM, kcoap's own code is compared with Bouncy Castle on random inputs: AES-CCM for every tag
and nonce length and the long-AAD encoding, AES-GCM and ChaCha20, SHAKE256 and KMAC256.

## 3. Fuzzing hostile input

Every decoder and verifier is fed thousands of mutated RFC messages and random bytes (with
`kcoap-testkit`'s `ByteMutator`, seeded so failures reproduce): CBOR, the UDP, TCP and WebSocket codecs,
the OSCORE option and OSCORE verification, and EDHOC message_1 to message_3. Each must fail only with
its own protocol exception, never with an index or state error, and a tampered protected message must
never verify. These run on every platform, natively included.

## 4. Live interop with libcoap and libedhoc

Containers built from `interop/` run the reference implementations, and JVM tests talk to them over
real sockets. The tests skip themselves when Docker or an image is missing. See `interop/README.md`.

```bash
docker build -t kcoap/libcoap:v4.3.5b interop/libcoap
docker build -t kcoap/libedhoc:0363bd3 interop/libedhoc
```

## Running

```bash
export JAVA_HOME=/path/to/jdk-17-or-newer
./gradlew jvmTest                 # every test on the JVM
./gradlew linuxX64Test            # the common tests as native code: Kotlin/Native runtime, OpenSSL crypto
./gradlew macosArm64Test          # the common tests against Apple crypto (macOS only)
./gradlew koverHtmlReport         # coverage report: build/reports/kover/html/index.html
./gradlew koverVerify             # fails below the coverage floor
```

## Coverage

The build enforces a line-coverage floor for the whole library (`build-logic/.../kcoap.coverage`),
currently 90%; the codebase is above 97%. New code comes with tests for every public behaviour and
error path, so the floor only ever goes up.

## Writing tests

- Name the test after the expectation, or after the RFC section its vector comes from.
- Use `runTest` for anything that suspends; time-dependent code takes an injected clock or runs on the
  test dispatcher's virtual time.
- Keep fixed keys and randomness explicit, so a failure reproduces exactly.
