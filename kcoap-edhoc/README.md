# kcoap-edhoc

EDHOC (RFC 9528), the lightweight authenticated key exchange for OSCORE, as sans-IO state machines for
both roles. The state machines take and return message bytes; transport, message correlation and
deduplication (RFC 9528 §3.4, §7) belong to the caller, and EDHOC over CoAP (Appendix A.2, RFC 9668)
to `kcoap-edhoc-oscore`.

## Driving a handshake

```kotlin
val initiator = EdhocInitiator(
    EdhocInitiatorConfig(
        method = EdhocMethod.STATIC_DH_STATIC_DH,
        cipherSuites = listOf(CipherSuite.SUITE_2),            // order of preference
        credentials = listOf(LocalCredential.staticDiffieHellman(credI, CredentialIdentifier.KeyId(kidI), keyI)),
        credentialResolver = TrustedCredentials(crypto, listOf(credR)),
    ),
    crypto,
)
val responder = EdhocResponder(EdhocResponderConfig(setOf(EdhocMethod.STATIC_DH_STATIC_DH), ...), crypto)

responder.processMessage1(initiator.createMessage1())
initiator.processMessage2(responder.createMessage2())
val (message3, session) = initiator.createMessage3()
val responderSession = responder.processMessage3(message3)
initiator.processMessage4(responder.createMessage4())     // only if the application profile uses message_4

val masterSecret = session.oscoreMasterSecret()            // EDHOC_Exporter(0, h'', key length)
```

Every failure is an `EdhocException` carrying the `EdhocError` of RFC 9528 §6; `errorMessage` is the
error message to send, or null when none may be sent (the peer sent an error, or nothing was received
yet). When the Responder answers message_1 with error code 2, `processMessage2` throws
`EdhocPeerErrorException`. If SUITES_R names a suite the Initiator prefers and runs, the exception's
`retryWith` is that suite and the Initiator is back at `EdhocPhase.START` with it selected: call
`createMessage1()` again (RFC 9528 §6.3.1). Otherwise `retryWith` is null and the session is aborted.

```kotlin
try {
    initiator.processMessage2(reply)
} catch (e: EdhocPeerErrorException) {
    if (e.retryWith == null) throw e
    reply = transport.exchange(initiator.createMessage1())
}
```

Calls out of order throw `IllegalStateException`. `EdhocPhase` mirrors the states of RFC 9528
Appendix I.

### Only suites the backend runs

Platforms differ in what their cryptography offers: WebCrypto has no ChaCha20/Poly1305, so suites 4 and
5 cannot run in a browser. `suite.isSupportedBy(crypto, method)` asks the `CryptoBackend`
(`supports(...)`, [ADR 0001](../docs/adr/0001-crypto-capabilities.md)) for everything a session with
that method needs: the key exchange, the signature algorithm if a side signs, both AEADs and both
hashes. It is true exactly when a handshake on that backend succeeds, which a test checks for every
suite and method on every target. Both state machines use it:

- The Initiator offers only suites it runs: SUITES_I is a prefix of them, and a retry picks from them.
- The Responder accepts and names in SUITES_R only suites it runs; a selected suite it cannot run is
  answered with error code 2 and the ones it can (RFC 9528 §6.3).
- A backend that turns out to lack an algorithm mid-handshake fails with a diagnostic that names it
  ("P_256 is not available in this crypto backend"), never as a peer's invalid key.

The configuration asks the backend once and remembers the answers, so sessions do not ask again. That
matters for a backend that keeps the probing defaults of `supports`, which generate keys and sign for
every question: the Responder chooses before it has checked anything of a message_1, and any peer can
send one. `config.supportedBy(crypto)` (both roles) makes the same choice up front: it returns the
configuration narrowed to the runnable suites, with the answers, and throws `IllegalArgumentException`,
naming each dropped suite and what it needs, when nothing usable is left. A server that needs a
configuration per session, to choose C_R, copies it with `withConnectionIdentifiers(provider)`, which
keeps the answers too.

| Type | Role |
|---|---|
| `EdhocInitiator`, `EdhocResponder` | State machines over sealed internal states; one instance per session |
| `EdhocInitiatorConfig`, `EdhocResponderConfig` | Application profile (RFC 9528 §3.9): method(s), suites, credentials, callbacks; `supportedBy(crypto)` |
| `EdhocSession`, `Message3` | PRK_out, PRK_exporter, `exporter`, `oscoreMasterSecret/Salt`, `keyUpdate` (Appendix H), peer credential, C_I / C_R; `Message3` is message_3 with the session it completes |
| `EdhocPeerErrorException` | The peer sent an error message; `retryWith` is the suite of the next message_1 after error code 2 |
| `CipherSuite`, `KeyExchangeAlgorithm` | Registry of RFC 9528 §10.2 plus suite -24; the key exchange is Diffie-Hellman or KEM |
| `AuthenticationCredential` | CRED_x as CCS or DER certificate, with the authentication key; certificates are parsed by `dev.kcoap.crypto.x509.X509Certificate` (kcoap-crypto) |
| `CredentialIdentifier` | ID_CRED_x: `KeyId`, `X509Thumbprint`, `X509Chain`, `Kccs`, `Other` |
| `CredentialResolver`, `TrustedCredentials` | Resolves and authorizes a received ID_CRED_x; chains go through `CertificateValidator` |
| `LocalCredential` | Own CRED_x, ID_CRED_x, private key handle, and whether it signs or does static DH |
| `EadHandler`, `EadItem` | Produce and consume EAD_1..4; critical labels are enforced, padding is dropped |
| `ConnectionIdentifierProvider`, `EphemeralKeySource` | Injection points for C_I / C_R and ephemeral keys (trace replay) |

The key exchange is a strategy (`EphemeralKeyExchange`, internal): ECDH on the suite's curve with
x-only G_X / G_Y (Appendix B), or a KEM where G_X is the encapsulation key, G_Y the ciphertext and
G_XY the shared secret (draft-ietf-lake-pqsuites §3). Static-DH methods 1–3 are rejected on KEM suites
both in the configuration and when a Responder receives such a message_1.

## What is implemented

- Methods 0–3, both roles; message_4; EDHOC_Exporter; EDHOC_KeyUpdate (Appendix H).
- Cipher suites 0–6 and 24 with the default backend; 25 is in the registry and reported as not
  implemented (its EdDSA needs Ed448, which no backend provides). Suite -24 = libedhoc's
  `EDHOC_CIPHER_SUITE_PQC_1` (ML-KEM-512, ML-DSA-44, AES-CCM-16-128-128, SHAKE256) with a backend that
  provides ML-KEM and ML-DSA. Private suites can be declared with the `CipherSuite` constructor.
  Only the suites the backend runs are offered and accepted (see above): in browsers, WebCrypto has no
  ChaCha20/Poly1305, so suites 4 and 5 are not available there.
- Key schedule of §4: HKDF for SHA-2, KMAC256 with empty customization for SHAKE256, the EDHOC_KDF info
  encoding, TH_2/TH_3/TH_4, PRK_2e / PRK_3e2m / PRK_4e3m / PRK_out, and the long-PLAINTEXT_2 keystream
  of Appendix G.
- Connection identifiers and compact 'kid' encoding (§3.3.2, §3.5.3.2), enforced on receipt.
- Credentials by `kid`, `x5t` (SHA-256/64, SHA-256, SHA-384, SHAKE256), `x5chain` and `kccs`;
  non-unique kids are tried in turn (§3.5.3). Certificates are parsed by kcoap-crypto's `x509` package
  (Ed25519, X25519, X448, P-256, P-384 and ML-DSA-44/65/87 keys), which throws only
  `CertificateFormatException` for what cannot be used, so a malformed `x5chain` from a peer ends the
  session with error code 1.
- Error codes 0–3 and cipher suite negotiation (§6). Unknown 'kid' / 'x5t' answers with code 3;
  untrusted or unsupported identifiers with code 1, as §6.4 requires.
- Received messages must be deterministically encoded CBOR (§3.1, §9.8); public keys and all
  cryptographic field lengths are validated (§9.2, §9.8), including the X25519 all-zero check. An x-only
  P-256 or P-384 share is expanded to the uncompressed point with kcoap-crypto's `Sec1` before the backend
  sees it, which checks that x is on the curve in common code: the same share fails the same way on every
  platform, and before an ephemeral key is generated for it.
- Intermediate secrets (G_XY, G_RX, G_IY, PRKs, K_3/IV_3, K_4/IV_4) are wiped after use and the
  ephemeral keys destroyed with `PrivateKey.destroy()`, whatever backend holds them (§9.8). A destroyed `EdhocSession` or `SoftwarePrivateKey` throws
  `IllegalStateException` instead of handing out the wiped bytes. Logging: DEBUG for protocol steps,
  TRACE for message bytes through `Logger.payload`; keys, PRKs and plaintexts are never logged.

### What a peer learns from an error

A failed peer authentication sends the same error whatever failed: code 1 with the diagnostic
"Authentication failed" for an untrusted credential sent by value, a trusted one that does not fit the
suite, a certificate chain the validator rejects, and a Signature_or_MAC_2 or _3 that does not verify.
A probing Initiator, which knows K_3 with signature methods, cannot tell which credentials are trusted
from the answer; `EdhocException.message` and the WARN log keep the precise reason. Errors about the
peer's own input (malformed messages, wrong lengths, a ciphertext that does not decrypt) stay specific.

Error code 3 still tells the peer whether a 'kid' or 'x5t' reference is known: that is the
interoperable behaviour of §6.4. A `CredentialResolver` that considers this sensitive throws instead
of returning an empty list:

```kotlin
val quiet = CredentialResolver { id ->
    trusted.resolve(id).ifEmpty { throw EdhocException(EdhocError.unspecified("Authentication failed"), "No credential for $id") }
}
```

Response timing can still reveal whether a credential is trusted, because only a trusted one gets its
signature or MAC checked.

### Certificates: trust and authorization

`TrustedCredentials` without a `CertificateValidator` trusts only the pinned credentials: an `x5chain`
whose end-entity certificate is not pinned fails closed. With a validator, every chain it accepts
authenticates, whoever its end entity is, so authorize afterwards: on `EdhocSession.peerCredential`
(its `certificate`) and `peerCredentialIdentifier`, or in a resolver that wraps the trusted one:

```kotlin
val authorized = CredentialResolver { id -> trusted.resolve(id).filter { it.certificate?.subjectCommonName in allowedNames } }
```

No `CertificateValidator` implementation ships yet (see the X.509 item of the
[roadmap](../docs/roadmap.md)); a stub that accepts everything makes every certificate authenticate.

Not implemented: CWT (`kcwt`) and C509 credentials, `x5u`/`c5u` retrieval, EAD-specific security
applications, and §7 deduplication (left to the transport). The Responder refuses a C_R equal to C_I,
because with OSCORE both would become the same Recipient ID (§3.3.3).

## Verified by

| Vectors | What they check |
|---|---|
| RFC 9529 §2 (trace 1) | Method 0, suite 0, Ed25519 certificates by `x5t`: every message byte for byte on both sides; H(message_1), G_XY, TH_2, PRK_2e, KEYSTREAM_2, PLAINTEXT_2, MAC_2, Signature_or_MAC_2, TH_3, PRK_3e2m, PRK_4e3m, MAC_3, Signature_or_MAC_3, PLAINTEXT_3, K_3, IV_3, TH_4, K_4, IV_4, PRK_out, PRK_exporter, OSCORE Master Secret/Salt, key update; context_2/3, info encodings, messages to be signed, A_3/A_4 |
| RFC 9529 §3 (trace 2) | Method 3, suite 2, CCS by `kid`: the rejected first message_1 and the error `02 02`, renegotiation to SUITES_I = [6, 2], then the same values as trace 1 plus SALT_3e2m, G_RX, SALT_4e3m, G_IY |
| RFC 9529 §4 | All 15 invalid messages are rejected: encoding errors, wrong key lengths, x ≥ p, points off the curve (before Y is generated), the low-order X25519 point (Y destroyed), a short MAC, non-deterministic CBOR |
| Round trips | Every method × suites 0–6 and 24 (every suite the backend supports: on WebCrypto not 4 and 5); both identifier encodings; message_4 on and off; EAD and padding; credentials by value; non-unique kids |
| Suite negotiation | `SuiteNegotiationTest`: for every suite and method on every target, `isSupportedBy` is true exactly when a loopback handshake succeeds (the default backend, one without ChaCha20/Poly1305 and ES256, the PQ test double); SUITES_R holds only runnable suites; a selected suite the Responder cannot run gets error code 2 and the retry succeeds; SUITES_I and retries draw only on runnable suites; missing curves, KEMs and algorithms are named; `supportedBy` in both roles |
| Post-quantum | Suite -24 and an ML-KEM-1024 / ML-DSA-87 / SHA-384 private suite, with a test double that has the FIPS 203 / 204 sizes (`FakePostQuantumBackend`): G_X 800, G_Y 768, signatures 2420 bytes, KMAC256 derivation; on the JVM with real ML-KEM and ML-DSA from `kcoap-crypto-bc` (`PostQuantumBouncyCastleTest`), with CCS (AKP COSE_Key) and ML-DSA X.509 credentials |
| libedhoc (live, JVM) | `LibedhocInteropTest`: sessions with fresh ephemeral keys against libedhoc 0363bd3 in a container, kcoap as Initiator and as Responder, for suites 0, 2, 4, 24 × methods 0–3 and suite -24 (liboqs) × method 0; kid/CCS and x5chain; integer and byte-string identifiers; message_4; negotiation (error code 2) both ways; tampering; EAD. Both sides must agree on OSCORE secret/salt, Sender/Recipient IDs, EDHOC_Exporter and PRK_out, before and after key update |

The trace constants are generated from the RFC text by `tools/gen_edhoc_vectors.py`
(`tools/regen-vectors.sh`), which checks every value against the byte count the RFC prints.

The libedhoc tests need Docker and the image built from `interop/libedhoc/Dockerfile`, and skip
themselves otherwise (see `interop/README.md`):

```bash
docker build -t kcoap/libedhoc:0363bd3 interop/libedhoc
./gradlew :kcoap-edhoc:jvmTest --tests '*LibedhocInteropTest*'
```

Tests named `upstream…` record where libedhoc deviates from RFC 9528, asserting its current behaviour:
its Responder supports only its last configured suite and skips the §5.2.3 check for a supported suite
earlier in SUITES_I, and without an EAD callback it accepts unknown critical EAD items (§3.8). No
deviation was found on kcoap's side. Suites 1, 3, 5 and 6 have no independent peer yet (libedhoc lacks
them), nor does the private ML-KEM-1024 suite.
