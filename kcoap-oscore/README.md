# kcoap-oscore

Object Security for Constrained RESTful Environments (RFC 8613): end-to-end protection of CoAP
requests and responses that survives proxies and transport changes.

## Using it

```kotlin
val crypto = DefaultCryptoBackend()
val context = SecurityContext.derive(
    SecurityContextParameters(
        masterSecret = secret,
        senderId = byteArrayOf(0x01),
        recipientId = byteArrayOf(0x02),
    ),
    crypto,
    // A pre-shared Master Secret outlives the process, so its sequence numbers must too.
    sequenceStore = FileSequenceNumberStore(dataDirectory),
)

// Client side: protect requests to a peer, verify its responses.
val client = OscoreClient(context)

// Server side: verify requests by 'kid' (and 'kid context'), protect the responses.
val repository = InMemoryContextRepository().apply { add(serverContext) }
val server = OscoreServer(repository)
```

Both plug into the CoAP stack through `dev.kcoap.coap.security` (`ClientMessageSecurity`,
`ServerMessageSecurity`), at the place RFC 8613 Figure 1 puts OSCORE: below Block-wise and Observe,
above retransmission and deduplication.

The module depends on [kcoap-coap-message](../kcoap-coap-message/README.md) (the message model, the option
codec and that SPI), not on the endpoint stack: OSCORE's API carries no `CoapEndpoint`, `CoapClient` or
`CoapServer`, so a proxy, a gateway or another CoAP stack can call `OscoreClient` and `OscoreServer` on
`CoapMessage`s of its own. An application that runs OSCORE through kcoap's client and server adds
[kcoap-coap](../kcoap-coap/README.md) to its dependencies.

This module verifies, decrypts and reports; kcoap-coap's client and server act on what it reports. So the
fail-closed rules below and the freshness check of Appendix B.1.2 hold only through them. A caller that uses
`OscoreClient` and `OscoreServer` without kcoap-coap takes over these duties of the SPI, which the KDoc in
[`MessageSecurity.kt`](../kcoap-coap-message/src/commonMain/kotlin/dev/kcoap/coap/security/MessageSecurity.kt)
and [`ResponseVerification.kt`](../kcoap-coap-message/src/commonMain/kotlin/dev/kcoap/coap/security/ResponseVerification.kt)
states, or OSCORE fails open:

1. When `UnprotectedRequest.challenge` is not null, send `protectResponse(challenge)` and never process
   `message`: its context cannot yet tell a fresh request from a replay (RFC 8613 §7.4, Appendix B.1.2).
2. Treat null from `unprotectRequest` as an unauthenticated request, and refuse it unless the resource is
   meant to be public (ADR 0008).
3. Act only on `ResponseVerification.Verified`. On `Unverified`, never repeat the request (not even for an
   Echo option), continue a body or end an observation: anyone on the path can send one, and a repeat runs a
   non-idempotent request twice (RFC 8613 §8.2).
4. Assign the token and the Message ID before `protectRequest`, and keep them: OSCORE protects neither, and
   responses are matched by token.
5. Protect every response to a request, each notification and each Q-Block block included, with the same
   `UnprotectedRequest`, and verify every response to a request with the same `ProtectedRequest`.

`OscoreServer` finds contexts through the `SecurityContextRepository` SPI (`find` by Recipient ID and ID
Context). `MutableSecurityContextRepository` adds `add`, `addIfAbsent` and `remove`, for contexts that come
and go while the server runs: kcoap-edhoc-oscore's `EdhocServer` adds one per completed handshake.
`InMemoryContextRepository` implements it without a bound; a server that runs EDHOC for many clients supplies
one with a bound, since an evicted client only runs EDHOC again: on 4.01 "Security context not found", or on
4.00 "Decryption failed" once the server gave its Recipient ID to another client. `remove(context)` removes
that very context only, so a late removal never takes out a newer context under the same Recipient ID.

Through kcoap-coap's client and server, both fail closed
([ADR 0008](../docs/adr/0008-fail-closed-message-security.md)):

- `OscoreClient` fails a request its `ClientContextResolver` finds no context for with
  `MessageSecurityException`, which kcoap-coap's client reports as `CoapException.Reason.SECURITY` with it as
  cause, before anything is sent; `OscoreClient(resolver, allowUnprotected = true)` sends such requests in
  plain CoAP instead. Unprotected 4.xx and 5.xx responses (RFC 8613 §8.2) are handed to the stack as
  `ResponseVerification.Unverified`, which acts on nothing in them: the request fails with
  `CoapException.Reason.UNVERIFIED_RESPONSE`. Any other unprotected response is dropped.
- A server with `security = OscoreServer(...)` refuses requests without OSCORE on every resource that is not
  `AccessPolicy.Public` (`CoapServerConfig.defaultAccess`). Each verified request carries its context's
  `SecurityContext.identity`: the `OscoreIdentity` (Recipient ID, ID Context) unless the context was derived
  with an `identity` of its own, or by EDHOC (an `EdhocIdentity` with the peer credential).

`SecurityContext` never exposes its keys, its Common IV or the Master Secret. `toString()` shows only
the identifiers. `SecurityContextParameters.masterSecret()` returns a copy for provisioning code.

## Sequence numbers and freshness

AES-CCM is broken when one key encrypts twice under the same nonce, and OSCORE nonces come from the
Sender Sequence Number. A context that restarts at 0 with the same key reuses nonces, so
`SecurityContext.derive` has no default store. The caller chooses one:

| Where the keys come from | `sequenceStore` | `FreshnessPolicy.AUTO` (the default) |
|---|---|---|
| A provisioned Master Secret, used across restarts | a durable store (recipes below) | the first request after every start is answered with an Echo challenge (Appendix B.1.2) |
| EDHOC (`EdhocSession.oscoreContext()` in `kcoap-edhoc-oscore`) | `EphemeralSequenceNumberStore`, its default; the context ends with the process, so run EDHOC again after a restart | trusted from the start: the keys are new |
| Tests | `EphemeralSequenceNumberStore` | trusted, unless the same store derives the context again |

- **Appendix B.1.1.** The context reserves `persistInterval` numbers (64 by default) per write. Every
  number it hands out is below a limit that `store()` has already returned for. If `store()` throws or
  is cancelled, nothing is handed out, and the next message writes again. A restart skips at most one
  block.
- **Appendix B.1.2.** The replay window is never persisted. With `AUTO`, a context requires the Echo
  proof when it was restored from its store, or when its store is anything but the ephemeral one: an
  earlier run may have accepted requests without persisting a number, because a response that reuses
  the request nonce takes none. `REQUIRE_PROOF` and `ASSUME_FRESH` override the choice. The challenge
  reaches the stack as `UnprotectedRequest.challenge`, and its own Partial IV comes from the durable
  counter. Every challenge repeats the same Echo value until a request returns it, so a replayed
  request cannot revoke the value the client is about to send back.
- **One live context per store and name.** Deriving again from the same store is a restore: discard
  the previous context first. Two live contexts with the same keys hand out the same numbers, and two
  derivations at the same time both start at 0.

### Writing a durable store

`SequenceNumberStore` is an SPI the application supplies, because durable storage differs per runtime.
Whatever the runtime, `store()` returns only when the value is on stable storage. A value written halfway
must never be read back, and the stored value never goes back. Keep the file out of backups that can be
restored onto a device: an older value brought back is exactly a nonce reuse.

**JVM and Android.** One file per context, written to a temporary file, forced to disk, then renamed
over the old one:

```kotlin
class FileSequenceNumberStore(private val directory: Path) : SequenceNumberStore {
    private val lock = Mutex()

    override suspend fun load(contextName: String): Long? = lock.withLock {
        withContext(Dispatchers.IO) {
            val file = directory.resolve("$contextName.seq")
            if (Files.exists(file)) Files.readAllBytes(file).decodeToString().trim().toLong() else null
        }
    }

    override suspend fun store(contextName: String, limit: Long) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                val temporary = directory.resolve("$contextName.seq.tmp")
                FileChannel.open(temporary, CREATE, WRITE, TRUNCATE_EXISTING).use { channel ->
                    channel.write(ByteBuffer.wrap(limit.toString().encodeToByteArray()))
                    channel.force(true) // the new value is on disk
                }
                // Readers see the old value or the new one, never a torn one.
                Files.move(temporary, directory.resolve("$contextName.seq"), ATOMIC_MOVE, REPLACE_EXISTING)
                // The rename is on disk too (Linux, Android, macOS; Windows cannot open a directory).
                FileChannel.open(directory, READ).use { it.force(true) }
            }
        }
    }
}
```

On Android, put the directory under `Context.noBackupFilesDir`, which Auto Backup skips. `java.nio.file`
needs API 26, kcoap's minSdk; the value is read as bytes because `Files.readString` came only with API 33.
[samples/android-consumer](../samples/android-consumer/README.md) compiles this store at API 26.

**iOS and macOS.** The same steps over POSIX from Kotlin/Native: `open`, `write`,
`fcntl(fd, F_FULLFSYNC)` (on Apple platforms `fsync` does not flush the drive's cache), `close`,
`rename`. Use a file in Application Support with `NSURLIsExcludedFromBackupKey` set. Do not use
`NSUserDefaults`, which promises nothing about durability when a call returns.

**Linux.** The same POSIX steps with `fsync` on the file and on its directory.

**Browsers.** Store the limits in IndexedDB, in a transaction opened with `{ durability: "strict" }`.
`store()` resumes only on the transaction's `complete` event. Ask for `navigator.storage.persist()`.
Even then, a user can clear site data.

**Node.js.** The file steps with `fs.promises`: `filehandle.sync()` before the rename.

A store that loses its data returns null for a context that was used before, and the context starts
at 0 again. The library cannot detect this. Deployments that cannot keep storage reliable, such as
browsers holding a pre-shared Master Secret, need keys that are new per session: EDHOC, or Appendix
B.2 once it is implemented.

## What is implemented

| RFC 8613 | Feature |
|---|---|
| §3.2 | Context derivation (HKDF, any COSE AEAD from `kcoap-crypto`) with Master Salt and ID Context; the copies of the Master Secret and Salt and the PRK are wiped afterwards |
| §3.2.2, §7.4 | Replay window (RFC 6347 sliding window, 32 by default), atomic check and update |
| §7.4 | One response per request, also when it carries a Partial IV. A request carrying an Inner Q-Block1 or Q-Block2 option (RFC 9177) takes the several responses of its body: at most one with the request's nonce, every other with a Partial IV new in a replay window of that request, of the context's size. Outside RFC 8613's single-response model, so the CoAP stack uses it only where both sides opt in (`withMessageSecurity`). §7.4.1 for a registration carrying Inner Q-Block (RFC 9177 §4.5), whose notification bodies come one notification per block: its notifications are checked against a replay window of the registration rather than only above the Notification Number, every Partial IV that verified is recorded as received before freshness is decided, and below the Notification Number only blocks of the freshest body (the ETag of the block holding it) pass |
| §4 | Inner/Outer option split from the option registry; unknown options are Class E |
| §4.1.3.3 | Proxy-Uri decomposition |
| §4.1.3.5 | Observe: Inner and Outer, FETCH / 2.05 outer codes, notification Partial IVs, freshness and replay of notifications. Without Inner Q-Block notifications are taken in strictly increasing Partial IV order. With it, freshness is per body (§4.1.3.5.2): the body whose block holds the highest Partial IV is the freshest, and its other blocks are taken in any order. The notification without a Partial IV counts as the oldest, so once a later one was taken it is dropped |
| §4.2 | Outer Code POST / 2.04 |
| §5 | COSE_Encrypt0, nonce construction, AAD (Enc_structure over aad_array) |
| §6 | OSCORE option compression ('kid', 'kid context', Partial IV) |
| §7.2.1 | Sequence numbers below 2^40; the context stops when exhausted |
| §8 | Request and response protection and verification, error responses with the RFC's diagnostics. Unprotected responses are not authenticated: the client passes the unprotected 4.xx and 5.xx errors (§8.2) on to a request that takes one response, and to a request carrying Inner Q-Block only 4.00, 4.01 and 5.xx without Echo or a block option, which end its body; every other one would steer the body from outside OSCORE (RFC 9177 §11) and is dropped |
| App. B.1.1 | Sequence-number persistence through the application's `SequenceNumberStore`: blocks reserved ahead, each durable before its first number is used; a failed or cancelled write hands out nothing and is retried |
| App. B.1.2 | Echo freshness proof for contexts whose replay window may be lost (`FreshnessPolicy`, `AUTO` by default), surfaced to the stack as `UnprotectedRequest.challenge` |

Not yet: Appendix B.2
(context re-derivation), outer Block-wise for proxies, OSCORE over HTTP (§11).

## Verified by

- RFC 8613 Appendix C.1 to C.8: every key, IV, nonce, `info`, AAD and protected message, byte for byte,
  on both the client and the server side.
- End-to-end exchanges through the SPI, Observe ordering and replay, every error path.
- Q-Block bodies (RFC 9177) under OSCORE: the blocks of a set in any order, a replayed block, a second
  response to a request without Q-Block, a block beyond the window, forged blocks, unprotected errors handed
  on as unverified and other unprotected responses dropped (`OscoreQBlockTest`, every target); kcoap's Q-Block
  client and server through the stack on the in-memory network, both ways, with a replayed block on the wire,
  an unprotected 4.02 and 4.08 injected on a body's tokens, which end the body as unverified and steer
  nothing (ADR 0008), a restarted server's B.1.2 challenge and 10 % loss over 10 seeds each way
  (`OscoreQBlockEndToEndTest`).
- Q-Block2 notification bodies (RFC 9177 §4.5) under OSCORE: the blocks of a body in any order for Q-Block2 and
  Q-Block1 FETCH registrations, replayed blocks, a block of an older body, a stale whole body, an older
  notification of one message, a stale notification that is a replay once its body becomes the freshest, the
  window edge, and the strict order without Q-Block (`OscoreQBlockTest`, every target); through the stack,
  notification bodies at 10 % loss with 20 to 50 ms of latency over 5 seeds (every body whole, in order, once,
  no genuine block refused), a held-back older body and a replayed newer one never delivered, and a protected
  observation that falls back to Inner Block2 when the server does not opt in (`OscoreQBlockEndToEndTest`).
- Appendix B.1.1 and B.1.2 under simulated restarts:
  - a recording store and AEAD show that every Partial IV is durable before its nonce is used;
  - stores that throw, before or after the write, are cancelled mid-write, or always fail never let a
    restarted context reuse a number;
  - a restored server challenges, also for a request it accepted before the restart, and its
    challenge's Partial IV is above every one it used before;
  - each `FreshnessPolicy` is tested for new, restored, re-derived and durable contexts;
  - a request replayed between a challenge and its answer gets the same Echo value, and the answer
    still proves freshness;
  - a store that fails while a context is derived reaches the caller before any key exists, and a
    backend that cannot build the AEAD gets its keys wiped.
- `EphemeralSequenceNumberStore` shared by parallel contexts on `Dispatchers.Default`: every number once,
  and none used before it was stored.
- `InMemoryContextRepository` as a `MutableSecurityContextRepository`: replacing `add`, `addIfAbsent` that never
  replaces, `remove` of that very context only (a replaced one leaves its successor), and a request without
  'kid context' finding the only context of its Recipient ID (`InMemoryContextRepositoryTest`).
- Interop with libcoap v4.3.5b in both directions, raw and through the CoAP stack, including B.1.2
  challenges both ways, and Q-Block under OSCORE: Inner Block1 and Block2 with the defaults; libcoap's single
  protected response per request, which breaks Q-Block2 bodies once `withMessageSecurity` is on, is pinned
  (`interop/README.md`).
