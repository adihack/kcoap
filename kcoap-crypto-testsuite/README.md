# kcoap-crypto-testsuite

The conformance suite of kcoap's crypto seam: abstract tests that a `CryptoBackend`'s own tests extend, so
every backend, kcoap's or yours (a key store, an HSM, a secure element, a post-quantum implementation), is
held to the same contract on every platform it runs on. It is a test library: depend on it from test source
sets only.

```kotlin
// build.gradle.kts of the backend
kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation("dev.kcoap:kcoap-crypto-testsuite:$kcoap")
        }
    }
}

// commonTest
class MyBackendConformanceTest : CryptoBackendConformanceTest() {
    override fun createBackend(): CryptoBackend = MyBackend(DefaultCryptoBackend())
}
```

## What it checks

| Type | What it is |
|---|---|
| `CryptoBackendConformanceTest` | The suite every backend extends: the capability contract for every AEAD, key type, signature algorithm, KEM and hash, and the capability report |
| `CapabilityInvariant` | The contract itself, as functions: `check(backend, question)` and `violations(backend)` return what is wrong, for tests of your own |
| `CapabilityReport` | What a backend answers to every question, as one log line per kind of question |
| `MlKemConformanceTest`, `MlDsaConformanceTest` | NIST's ACVP known answers for ML-KEM (FIPS 203) and ML-DSA (FIPS 204), replayed through any backend that has them |
| `ScriptedRandom` | The random bytes a backend draws during a known-answer test, from the vector |

**The capability contract** ([ADR 0001](../docs/adr/0001-crypto-capabilities.md)): `supports(x)` is false
exactly when the operations on `x` throw `UnsupportedAlgorithmException`. `CapabilityInvariant` asks each
question twice (the answer must not change), then runs the operations the default `supports` members probe:

- A yes must run, and run right: AEADs round trip and reject a changed ciphertext body, a changed tag or
  changed associated data with `AeadAuthenticationException`; Diffie-Hellman agrees both ways; signatures
  verify and changed ones do not; a KEM decapsulates its own secret and a changed ciphertext to another one
  (implicit rejection); lengths are those of the algorithm.
- Every algorithm also reproduces a published known answer, so that a look-alike that is consistent with
  itself does not pass (an ECDH that returns a hash of the shared point, an ES256 that hashes with SHA-384, an
  AES-CCM that formats its CBC-MAC wrongly on both sides):

  | Algorithms | Known answer |
  |---|---|
  | AES-CCM-16-64-128 | RFC 3610 §8, packet vector #1 |
  | the other seven AES-CCM variants of COSE (RFC 9053 §4.2) | the COSE Working Group's examples, `aes-ccm-examples/aes-ccm-enc-02` to `-08` ([cose-wg/Examples](https://github.com/cose-wg/Examples), commit `53c9d634`) |
  | A128GCM, A192GCM, A256GCM | the GCM specification's test cases 2, 8 and 14 (McGrew and Viega) |
  | ChaCha20/Poly1305 | RFC 8439 §2.8.2 |
  | X25519, X448 | RFC 7748 §6.1 and §6.2 |
  | P-256 and P-384 ECDH | RFC 5903 §8.1 and §8.2: the secret is the x-coordinate of the shared point |
  | Ed25519 | RFC 8032 §7.1 TEST 1, verified |
  | ES256, ES384 | RFC 6979 §A.2.5 and §A.2.6, the "sample" signatures with SHA-256 and SHA-384 as raw `r \|\| s`, verified |
  | ML-KEM, ML-DSA | NIST's ACVP cases (below) |
  | SHA-256, SHA-384, SHAKE256; HMAC | the FIPS 180-4 and FIPS 202 examples; RFC 4231 test case 1 |

  Signatures are verified, not reproduced, since signers may hedge (Apple's CryptoKit does).
- A no must throw `UnsupportedAlgorithmException`, never another exception and never a result.
- SHAKE256 is a yes on every backend, since common code always computes it.

A decorator that adds algorithms (`class MyBackend(d: CryptoBackend) : CryptoBackend by d`) and forgets to
override the matching `supports` members fails here, because its delegate denies what the decorator runs.
kcoap-crypto-bc's tests show it with a test-only wrapper that forwards Bouncy Castle's `supports` to the
classical backend.

Known answers that need an imported private key (X25519, X448, P-256, P-384, ML-KEM) are skipped when the
backend refuses `importKeyPair` with `UnsupportedAlgorithmException`, as a key store may. The checks generate
key pairs and destroy them again; a hardware backend pays what its probes would. `PrivateKey.destroy` must not
throw: one that does is a violation, unless an operation had already failed, which is then the one reported.

**The ACVP suites** take two factories: the backend, and the backend drawing every random byte from a
`ScriptedRandom`, exactly as many as FIPS 203 and 204 prescribe (`d || z`, `m`, `ξ`, `rnd`). NIST's
deterministic ML-DSA signatures are signed hedged with `rnd = {0}^32`, which is what the deterministic variant
is (FIPS 204 §3.4), so the backend needs no deterministic mode. Private keys are imported as the seed or the
expanded key of their `KeyType` (`seedLength`, `expandedPrivateKeyLength`), and input that fails the FIPS
checks (§7.2 and §7.3 of FIPS 203, the `tr` of FIPS 204) is rejected with an exception other than
`UnsupportedAlgorithmException`. Parameter sets the backend does not support are skipped, but it must support
one: the suite never passes on a backend without the algorithm.

| Vectors (NIST ACVP-Server, commit 975de31e, `gen-val/json-files`) | tcIds | What is checked |
|---|---|---|
| `ML-KEM-keyGen-FIPS203` | 1, 26, 51 | `ek` from `d`, `z`; seed and expanded `dk` decapsulate alike; neither imports with another `ek` |
| `ML-KEM-encapDecap-FIPS203` | 1, 26, 51 | `c` and `k` from `ek`, `m`; `dk` decapsulates `c` to `k` |
| `ML-KEM-encapDecap-FIPS203` | 76 | implicit rejection: modified `c` gives NIST's `k` |
| `ML-KEM-encapDecap-FIPS203` | 117, 106 | the modulus check rejects `ek`; the hash check rejects `dk` |
| `ML-DSA-keyGen-FIPS204` | 1, 26, 51 | `pk` from `ξ`; seed and expanded `sk` sign alike; changed parts, `tr` included, do not import |
| `ML-DSA-sigGen-FIPS204` | 5, 44, 70 (deterministic), 189 (hedged) | signatures byte for byte, and they verify; changed and malformed ones do not |
| `ML-DSA-sigVer-FIPS204` | 10 | NIST's rejection of a modified message |

The vectors are generated into `src/commonMain/.../vectors` by `tools/regen-pq-vectors.sh`; the ML-DSA cases
are external-interface, pure, empty-context ones, the only variant COSE uses.

## The capability report

`CryptoBackendConformanceTest` prints one line per kind of question, each starting with
`kcoap-crypto capabilities`:

```
kcoap-crypto capabilities of DefaultCryptoBackend: AEAD yes AES_CCM_16_64_128, ..., A256GCM; no CHACHA20_POLY1305
```

kcoap's crypto modules copy these lines from the test output into the build log with the test task in front
(`[jsNodeTest] kcoap-crypto capabilities of ...`), so CI logs show what each target runs. The lines exist only
while the tests run, and they depend on the platform's cryptography, which Gradle cannot see as an input: so
these test tasks are never taken from the build cache or skipped as up to date, and every build that asks for
them, CI's included, runs them and logs the report of the machine it runs on. A backend of your own can do the
same in its build file:

```kotlin
tasks.withType<AbstractTestTask>().configureEach {
    outputs.cacheIf("the capability report must reach the build log") { false }
    outputs.upToDateWhen { false }
    val target = name
    val log = logger
    addTestOutputListener { _, output ->
        output.message.lineSequence().filter { it.startsWith("kcoap-crypto capabilities") }
            .forEach { log.lifecycle("[$target] $it") }
    }
}
```

## Targets and dependencies

Every target (the UNIVERSAL tier): the suites are common code (the build checks it), and kotlin-test and
kotlinx-coroutines-test, which they build on, run everywhere, so a backend runs the same suite wherever it
runs. kcoap-crypto-default runs it wherever kcoap's tests run: the JVM, Linux, JS and Wasm on Node.js, and
macOS and the iOS simulator (`scripts/test apple`, CI's macOS job); browser test tasks are off in kcoap's
build. kcoap-crypto-bc runs it on the JVM. A check of an algorithm the platform lacks passes when the backend
says so, such as X448 on Apple's CryptoKit or ChaCha20/Poly1305 on WebCrypto. On the
JVM the suites use JUnit 5 (`kotlin-test-junit5`), the platform kcoap's own tests run on; a test task there
needs `useJUnitPlatform()`.

The module brings `kcoap-crypto`, `kotlin-test` and `kotlinx-coroutines-test` (`api`), since the suites'
signatures use them.

## Verified by

The suite's own tests break the contract one way at a time, on decorators of the default backend, and check
that each break is reported: a yes for what throws, a no for what runs, a no with another exception, an
answer that changes, an AEAD that takes any tag, checks the tag without the body or is another algorithm, a
key agreement that disagrees or is a hash of the right one, a signature check that accepts too much or hashes
the message first, a KEM that is not ML-KEM, a key whose `destroy` throws. Each look-alike that is consistent
with itself is caught by the known answer of every algorithm the platform runs. On the JVM they also check that the
abstract suites fail such backends, and that the ACVP suites fail a backend without ML-KEM or ML-DSA.

```bash
./gradlew :kcoap-crypto-testsuite:allTests
./gradlew :kcoap-crypto-default:allTests :kcoap-crypto-bc:jvmTest   # the suites on kcoap's backends
```
