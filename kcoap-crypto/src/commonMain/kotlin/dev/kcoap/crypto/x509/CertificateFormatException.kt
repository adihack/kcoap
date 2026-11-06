package dev.kcoap.crypto.x509

import dev.kcoap.common.KcoapException

/**
 * A certificate or SubjectPublicKeyInfo that kcoap cannot use: malformed DER (ITU-T X.690), a structure
 * that is not an X.509 certificate (RFC 5280 §4.1), or a public key algorithm or curve kcoap does not
 * support. The parsers in this package throw nothing else for any input, so DER from a peer cannot
 * surface as an [IllegalArgumentException]. A [KcoapException].
 */
public class CertificateFormatException(message: String, cause: Throwable? = null) : KcoapException(message, cause)
