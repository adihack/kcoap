package com.example.kcoap.crypto

internal fun String.hex(): ByteArray {
    require(length % 2 == 0) { "Odd-length hex string" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

internal fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
