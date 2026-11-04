package dev.kcoap.crypto

import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex

// Short names for the vector-heavy tests in this module.
internal fun String.hex(): ByteArray = hexToBytes()

internal fun ByteArray.hex(): String = toHex()
