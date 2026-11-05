package dev.kcoap.sample.android

import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.bc.BouncyCastleCryptoBackend

/**
 * The post-quantum flavor: ML-KEM and ML-DSA on Bouncy Castle (kcoap-crypto-bc), everything else on the
 * platform's providers as in the classical flavor.
 */
internal const val CRYPTO_SETUP: String = "BouncyCastleCryptoBackend over DefaultCryptoBackend"

internal fun cryptoBackend(): CryptoBackend = BouncyCastleCryptoBackend()
