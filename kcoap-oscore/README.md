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

`SecurityContext` never exposes its keys, its Common IV or the Master Secret. `toString()` shows only
the identifiers. `SecurityContextParameters.masterSecret()` returns a copy for provisioning code.

## Sequence numbers and freshness

AES-CCM is broken when one key encrypts twice under the same nonce, and OSCORE nonces come from the
Sender Sequence Number. A context that restarts at 0 with the same key reuses nonces, so
`SecurityContext.derive` has no default store. The caller chooses one:

| Where the keys come from | `sequenceStore` | `FreshnessPolicy.AUTO` (the default) |
|---|---|---|
| A provisioned Master Secret, used across restarts | a durable store (recipes below) | the first request after every start is answered with an Echo challenge (Appendix B.1.2) |
| EDHOC (`EdhocSession.oscoreContext()` in `kcoap-edhoc-oscore`) | `EphemeralSequenceNumberStore`, its default | trusted from the start: the keys are new |
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
            if (Files.exists(file)) Files.readString(file).trim().toLong() else null
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
needs API 26. Below that, use `FileOutputStream.getFD().sync()` and `File.renameTo`.

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
| §4 | Inner/Outer option split from the option registry; unknown options are Class E |
| §4.1.3.3 | Proxy-Uri decomposition |
| §4.1.3.5 | Observe: Inner and Outer, FETCH / 2.05 outer codes, notification Partial IVs, freshness and replay of notifications |
| §4.2 | Outer Code POST / 2.04 |
| §5 | COSE_Encrypt0, nonce construction, AAD (Enc_structure over aad_array) |
| §6 | OSCORE option compression ('kid', 'kid context', Partial IV) |
| §7.2.1 | Sequence numbers below 2^40; the context stops when exhausted |
| §8 | Request and response protection and verification, error responses with the RFC's diagnostics |
| App. B.1.1 | Sequence-number persistence through the application's `SequenceNumberStore`: blocks reserved ahead, each durable before its first number is used; a failed or cancelled write hands out nothing and is retried |
| App. B.1.2 | Echo freshness proof for contexts whose replay window may be lost (`FreshnessPolicy`, `AUTO` by default), surfaced to the stack as `UnprotectedRequest.challenge` |

Not yet: Appendix B.2
(context re-derivation), outer Block-wise for proxies, OSCORE over HTTP (§11).

## Verified by

- RFC 8613 Appendix C.1 to C.8: every key, IV, nonce, `info`, AAD and protected message, byte for byte,
  on both the client and the server side.
- End-to-end exchanges through the SPI, Observe ordering and replay, every error path.
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
- Interop with libcoap v4.3.5b in both directions, raw and through the CoAP stack, including B.1.2
  challenges both ways (`interop/README.md`).
