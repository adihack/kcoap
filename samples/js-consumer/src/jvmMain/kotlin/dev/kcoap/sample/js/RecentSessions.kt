package dev.kcoap.sample.js

import dev.kcoap.oscore.MutableSecurityContextRepository
import dev.kcoap.oscore.SecurityContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The OSCORE contexts of the newest [max] page loads, in [repository]: each new one past [max] takes the place of
 * the oldest, so a client that keeps asking for sessions costs the server a bounded amount of memory. The page
 * whose context goes gets an unprotected 4.01 for its next request (RFC 8613 §8.2), which its client refuses as
 * unverified, and a reload gets it a new session.
 */
class RecentSessions(private val repository: MutableSecurityContextRepository, private val max: Int) {
    private val lock = Mutex()
    private val order = ArrayDeque<SecurityContext>()

    init {
        require(max > 0) { "At least one session: $max" }
    }

    /** Adds [context] to the repository and removes the oldest context when there are more than [max]. */
    suspend fun add(context: SecurityContext) {
        lock.withLock {
            repository.add(context)
            order.addLast(context)
            if (order.size > max) repository.remove(order.removeFirst())
        }
    }
}
