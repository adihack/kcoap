package dev.kcoap.sample.android

import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.backend.DefaultCryptoBackend

/** The classical flavor: the platform's providers (JCA) through cryptography-kotlin, without Bouncy Castle. */
internal const val CRYPTO_SETUP: String = "DefaultCryptoBackend"

internal fun cryptoBackend(): CryptoBackend = DefaultCryptoBackend()
