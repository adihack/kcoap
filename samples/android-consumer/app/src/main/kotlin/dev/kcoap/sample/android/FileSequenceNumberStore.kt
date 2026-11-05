package dev.kcoap.sample.android

import dev.kcoap.oscore.SequenceNumberStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/**
 * Sender Sequence Numbers in files under [directory], durable before [store] returns (RFC 8613
 * Appendix B.1.1): the recipe of kcoap-oscore's README, with the calls of API level 26. java.nio.file
 * came with API 26, but `Files.readString` only with 33, so the value is read as bytes.
 */
internal class FileSequenceNumberStore(private val directory: Path) : SequenceNumberStore {
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
                // The rename is on disk too.
                FileChannel.open(directory, READ).use { it.force(true) }
            }
        }
    }
}
