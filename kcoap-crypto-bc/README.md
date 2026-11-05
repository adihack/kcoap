# kcoap-crypto-bc

Post-quantum `CryptoBackend` for the JVM and Android: ML-KEM (FIPS 203) and ML-DSA (FIPS 204) on Bouncy
Castle's lightweight API (`bcprov-jdk18on`), for the EDHOC PQ cipher suites of draft-ietf-lake-pqsuites.
libedhoc's private suite -24 uses ML-KEM-512 for the ephemeral key exchange and ML-DSA-44 for signatures.

```kotlin
val crypto = BouncyCastleCryptoBackend()                     // wraps DefaultCryptoBackend
val responder = crypto.generateKeyPair(KeyType.ML_KEM_512)
val sent = crypto.encapsulate(KemAlgorithm.ML_KEM_512, responder.publicKey)
val received = crypto.decapsulate(responder.privateKey, sent.ciphertext)   // same bytes as sent.sharedSecret()
sent.destroy()                                                              // overwrites the sender's copy
```

## What it does

`BouncyCastleCryptoBackend(classical = DefaultCryptoBackend(), isSigningDeterministic = false)` implements
`generateKeyPair`, `importKeyPair`, `sign`, `verify`, `encapsulate` and `decapsulate` for the ML-KEM and ML-DSA
key types and delegates everything else (hashes, HMAC, AEADs, X25519, P-256/384, Ed25519, ECDSA) to `classical`.
Its `supports` members answer yes for ML-KEM and ML-DSA without probing and pass every other question to
`classical` ([ADR 0001](../docs/adr/0001-crypto-capabilities.md)), so EDHOC offers suite -24 with it.

| Algorithm | Parameter sets | Spec |
|---|---|---|
| ML-KEM: KeyGen, Encaps, Decaps with implicit rejection | 512, 768, 1024 | FIPS 203 Algorithms 19-21 |
| ML-DSA: KeyGen, Sign (hedged by default, deterministic on request), Verify | 44, 65, 87 | FIPS 204 Algorithms 1-3 |

- ML-DSA is pure ML-DSA (not HashML-DSA) with an empty context string, as COSE uses it
  (draft-ietf-cose-dilithium, ids -48/-49/-50).
- Input checks: the modulus check of encapsulation keys (FIPS 203 §7.2), the length and hash checks of ciphertexts
  and decapsulation keys (FIPS 203 §7.3). A tampered ciphertext of the right length is not an error: it
  decapsulates to the implicit-rejection secret `J(z || c)`, so the failure shows up when the derived keys are used.
- `verify` returns false for a wrong or malformed signature and throws only for a public key of the wrong length.
- All randomness (`d || z`, `m`, `ξ`, `rnd`) is drawn from `classical.randomBytes` and wiped after use, so a
  backend with fixed random bytes replays known-answer vectors. A random source that returns the wrong number of
  bytes is rejected.

## Key encodings

The encodings are the standard ones, so keys move unchanged between kcoap and other implementations such as
libedhoc.

| | Public key | Generated private key | Accepted by `importKeyPair` |
|---|---|---|---|
| ML-KEM-512 / 768 / 1024 | encapsulation key `ek`, 800 / 1184 / 1568 bytes | seed `d \|\| z`, 64 bytes | seed, or expanded `dk` of 1632 / 2400 / 3168 bytes |
| ML-DSA-44 / 65 / 87 | `pk`, 1312 / 1952 / 2592 bytes | seed `ξ`, 32 bytes | seed, or expanded `sk` of 2560 / 4032 / 4896 bytes |

Private keys are `SoftwarePrivateKey`s. Generated keys hold the seed: it is small and determines the whole key,
and the expanded key is derived again for each operation. Imported keys stay in the encoding they were given; the
seed and expanded forms are told apart by length. Import checks that the private key belongs to the public key
(for ML-DSA also that `tr = H(pk, 64)`, which Bouncy Castle does not check), and rejects an ML-KEM key pair that
fails the FIPS 203 input checks.

## Verified by

NIST ACVP known-answer vectors (github.com/usnistgov/ACVP-Server, `gen-val/json-files`, commit 975de31e), a few
cases per parameter set, embedded as hex in `src/jvmTest/.../vectors` by `tools/regen-pq-vectors.sh`:

| File | tcIds | What is checked |
|---|---|---|
| `ML-KEM-keyGen-FIPS203` | 1, 26, 51 | `ek` from `d`, `z`; seed and expanded `dk` decapsulate alike |
| `ML-KEM-encapDecap-FIPS203` | 1, 26, 51 | `c` and `k` from `ek`, `m`; `dk` decapsulates `c` to `k` |
| `ML-KEM-encapDecap-FIPS203` | 76 | implicit rejection: modified `c` gives NIST's `k` |
| `ML-KEM-encapDecap-FIPS203` | 117, 106 | modulus check rejects `ek`; hash check rejects `dk` |
| `ML-DSA-keyGen-FIPS204` | 1, 26, 51 | `pk` from `ξ`; seed and expanded `sk` sign alike |
| `ML-DSA-sigGen-FIPS204` | 5, 44, 70 (deterministic), 189 (hedged) | signatures byte for byte, and they verify |
| `ML-DSA-sigVer-FIPS204` | 10 | NIST's rejection of a modified message |

ML-DSA cases are all external-interface, pure, empty-context ones, the only variant the backend exposes. Besides
the vectors, the tests cover round trips for every parameter set, implicit rejection against kcoap's own SHAKE256,
tampered, truncated and malformed signatures, every input check, delegation of the classical algorithms, and the
wiping of drawn randomness.

```bash
./gradlew :kcoap-crypto-bc:jvmTest :kcoap-crypto-bc:koverXmlReport
```
