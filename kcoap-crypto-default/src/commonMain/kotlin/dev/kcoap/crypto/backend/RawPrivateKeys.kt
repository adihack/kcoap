package dev.kcoap.crypto.backend

import dev.kcoap.common.wipe
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.SoftwarePrivateKey

// The raw private keys that pass between a SoftwarePrivateKey and the platform's cryptography.
//
// The platform takes and hands out private keys as byte arrays, and SoftwarePrivateKey takes and hands out
// copies, so each operation makes one more copy of the key. The backend wipes that copy as soon as the
// platform is done with it, as BouncyCastleCryptoBackend does. The platform's own key objects (JCA keys,
// say) cannot be wiped: this narrows the exposure of the key material, it does not remove it.

/**
 * Runs [block] on a copy of this key's raw material and wipes the copy afterwards, also when [block] throws.
 * The key itself keeps its material.
 *
 * @throws IllegalArgumentException when this is not a [SoftwarePrivateKey]: any other handle belongs to a
 *   different backend.
 * @throws IllegalStateException when the key was destroyed; [block] does not run.
 */
internal inline fun <T> PrivateKey.withRawKey(block: (raw: ByteArray) -> T): T {
    val software = this as? SoftwarePrivateKey
        ?: throw IllegalArgumentException("${this::class.simpleName} was not created by a software backend")
    val raw = software.encoded()
    try {
        return block(raw)
    } finally {
        raw.wipe()
    }
}

/**
 * A [SoftwarePrivateKey] of [type] holding [raw], a private key the platform has just encoded. The key keeps
 * a copy of its own, and [raw] is wiped.
 */
internal fun softwareKeyOf(type: KeyType, raw: ByteArray): SoftwarePrivateKey = try {
    SoftwarePrivateKey(type, raw)
} finally {
    raw.wipe()
}
