package dev.kcoap.sample.android

import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.edhoc.CipherSuite
import dev.kcoap.edhoc.EdhocMethod

/**
 * What [crypto] runs on this device, as its `supports` members answer them
 * (docs/adr/0001-crypto-capabilities.md), and the EDHOC cipher suites it runs for each method
 * (RFC 9528 §3.6, [CipherSuite.isSupportedBy]). Android's providers differ by API level; PL-2 records this
 * report for each.
 */
internal suspend fun capabilityReport(crypto: CryptoBackend): String = buildString {
    appendLine("AEAD: ${AeadAlgorithm.entries.filter { crypto.supports(it) }.joinToString()}")
    appendLine("Hash: ${HashAlgorithm.entries.filter { crypto.supports(it) }.joinToString()}")
    appendLine("Key types: ${KeyType.entries.filter { crypto.supports(it) }.joinToString()}")
    appendLine("Signatures: ${SignatureAlgorithm.entries.filter { crypto.supports(it) }.joinToString()}")
    appendLine("KEM: ${KemAlgorithm.entries.filter { crypto.supports(it) }.joinToString()}")
    val suites = CipherSuite.registered + CipherSuite.SUITE_PQC_1
    for (method in EdhocMethod.entries) {
        val runnable = suites.filter { it.isSupportedBy(crypto, method) }
        appendLine("EDHOC $method: suites ${runnable.joinToString { "${it.value}" }}")
    }
}
