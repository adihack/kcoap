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

- A yes must run, and run right: AEADs round trip and reject a changed ciphertext or changed associated data
  with `AeadAuthenticationException`; Diffie-Hellman agrees both ways; signatures verify and changed ones do
  not; a KEM decapsulates its own secret and a changed ciphertext to another one (implicit rejection);
  lengths are those of the algorithm. Each algorithm also reproduces one published known answer, so that a
  look-alike does not pass: RFC 3610 packet 1 (AES-CCM-16-64-128), the GCM specification's test cases 2, 8
  and 14 (AES-GCM), RFC 8439 §2.8.2 (ChaCha20/Poly1305), RFC 7748 §6.1 (X25519), RFC 8032 §7.1 TEST 1
  (Ed25519), NIST's ACVP cases (ML-KEM, ML-DSA), FIPS 180-4 and FIPS 202 examples (SHA-256, SHA-384,
  SHAKE256) and RFC 4231 test case 1 (HMAC).
- A no must throw `UnsupportedAlgorithmException`, never another exception and never a result.
- SHAKE256 is a yes on every backend, since common code always computes it.

A decorator that adds algorithms (`class MyBackend(d: CryptoBackend) : CryptoBackend by d`) and forgets to
override the matching `supports` members fails here, because its delegate denies what the decorator runs.
kcoap-crypto-bc's tests show it with a test-only wrapper that forwards Bouncy Castle's `supports` to the
classical backend.

Known answers that need an imported private key (X25519, ML-KEM) are skipped when the backend refuses
`importKeyPair` with `UnsupportedAlgorithmException`, as a key store may. The checks generate key pairs and
destroy them again; a hardware backend pays what its probes would.

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
(`[jsNodeTest] kcoap-crypto capabilities of ...`), so CI logs show what each target runs. A backend of your
own can do the same in its build file:

```kotlin
tasks.withType<AbstractTestTask>().configureEach {
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
runs. kcoap-crypto-default runs it on the JVM, Linux, Apple, JS and Wasm; kcoap-crypto-bc on the JVM. On the
JVM the suites use JUnit 5 (`kotlin-test-junit5`), the platform kcoap's own tests run on; a test task there
needs `useJUnitPlatform()`.

The module brings `kcoap-crypto`, `kotlin-test` and `kotlinx-coroutines-test` (`api`), since the suites'
signatures use them.

## Verified by

The suite's own tests break the contract one way at a time, on decorators of the default backend, and check
that each break is reported: a yes for what throws, a no for what runs, a no with another exception, an
answer that changes, an AEAD that takes any tag or is another algorithm, a key agreement that disagrees, a
signature check that accepts too much, a KEM that is not ML-KEM. On the JVM they also check that the
abstract suites fail such backends, and that the ACVP suites fail a backend without ML-KEM or ML-DSA.

```bash
./gradlew :kcoap-crypto-testsuite:allTests
./gradlew :kcoap-crypto-default:allTests :kcoap-crypto-bc:jvmTest   # the suites on kcoap's backends
```
