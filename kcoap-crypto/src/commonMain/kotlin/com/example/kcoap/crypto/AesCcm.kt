package com.example.kcoap.crypto

/**
 * AES-CCM (RFC 3610 / NIST SP 800-38C) on top of a raw AES block cipher.
 *
 * Written in common code because no Apple framework and neither the stock JDK nor Android providers
 * offer CCM, yet it is the default AEAD of OSCORE and of EDHOC cipher suites 0-3.
 *
 * @param tagLength M in RFC 3610: 4, 6, 8, 10, 12, 14 or 16 bytes.
 * @param nonceLength 15 - L: 7..13 bytes (13 for AES-CCM-16-*, 7 for AES-CCM-64-*).
 */
public class AesCcm(
    private val block: AesBlockCipher,
    override val tagLength: Int,
    override val nonceLength: Int,
) : Aead {
    init {
        require(tagLength in 4..16 && tagLength % 2 == 0) { "Invalid CCM tag length: $tagLength" }
        require(nonceLength in 7..13) { "Invalid CCM nonce length: $nonceLength" }
    }

    /** Size of the length field L, in bytes. */
    private val lengthFieldSize = 15 - nonceLength

    override fun encrypt(nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        checkNonce(nonce)
        checkMessageLength(plaintext.size)
        val tag = cbcMac(nonce, associatedData, plaintext)
        val output = ByteArray(plaintext.size + tagLength)
        ctrXor(nonce, plaintext, output)
        val s0 = block.encryptBlock(counterBlock(nonce, 0))
        for (i in 0 until tagLength) {
            output[plaintext.size + i] = (tag[i].toInt() xor s0[i].toInt()).toByte()
        }
        return output
    }

    override fun decrypt(nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        checkNonce(nonce)
        if (ciphertext.size < tagLength) throw AeadAuthenticationException()
        val messageLength = ciphertext.size - tagLength
        checkMessageLength(messageLength)

        val plaintext = ByteArray(messageLength)
        ctrXor(nonce, ciphertext.copyOfRange(0, messageLength), plaintext)
        val tag = cbcMac(nonce, associatedData, plaintext)
        val s0 = block.encryptBlock(counterBlock(nonce, 0))

        var diff = 0
        for (i in 0 until tagLength) {
            diff = diff or (tag[i].toInt() xor s0[i].toInt() xor ciphertext[messageLength + i].toInt())
        }
        if (diff != 0) {
            plaintext.fill(0)
            throw AeadAuthenticationException()
        }
        return plaintext
    }

    private fun checkNonce(nonce: ByteArray) {
        require(nonce.size == nonceLength) { "CCM nonce must be $nonceLength bytes, got ${nonce.size}" }
    }

    private fun checkMessageLength(length: Int) {
        // The message length must fit in L bytes; for L >= 4 any Int fits.
        if (lengthFieldSize < 4) {
            require(length < (1 shl (8 * lengthFieldSize))) {
                "Message of $length bytes is too long for a ${lengthFieldSize}-byte CCM length field"
            }
        }
    }

    /** CBC-MAC over B0 || encoded AAD || message (RFC 3610 §2.2). Returns the full 16-byte MAC state. */
    private fun cbcMac(nonce: ByteArray, aad: ByteArray, message: ByteArray): ByteArray {
        val b0 = ByteArray(BLOCK)
        val adataFlag = if (aad.isNotEmpty()) 0x40 else 0
        b0[0] = (adataFlag or (((tagLength - 2) / 2) shl 3) or (lengthFieldSize - 1)).toByte()
        nonce.copyInto(b0, destinationOffset = 1)
        writeBigEndian(message.size.toLong(), b0, offset = 1 + nonceLength, size = lengthFieldSize)

        var x = block.encryptBlock(b0)
        if (aad.isNotEmpty()) {
            x = absorb(x, encodeAadLength(aad.size) + aad)
        }
        if (message.isNotEmpty()) {
            x = absorb(x, message)
        }
        return x
    }

    /** XORs [data] into the CBC-MAC state block by block, zero-padding the last block. */
    private fun absorb(state: ByteArray, data: ByteArray): ByteArray {
        var x = state
        var offset = 0
        while (offset < data.size) {
            val chunk = minOf(BLOCK, data.size - offset)
            val input = x.copyOf()
            for (i in 0 until chunk) {
                input[i] = (input[i].toInt() xor data[offset + i].toInt()).toByte()
            }
            x = block.encryptBlock(input)
            offset += chunk
        }
        return x
    }

    /** CTR-mode keystream starting at counter 1 (counter 0 is reserved for the tag). */
    private fun ctrXor(nonce: ByteArray, input: ByteArray, output: ByteArray) {
        var counter = 1L
        var offset = 0
        while (offset < input.size) {
            val keystream = block.encryptBlock(counterBlock(nonce, counter))
            val chunk = minOf(BLOCK, input.size - offset)
            for (i in 0 until chunk) {
                output[offset + i] = (input[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            offset += chunk
            counter++
        }
    }

    private fun counterBlock(nonce: ByteArray, counter: Long): ByteArray {
        val a = ByteArray(BLOCK)
        a[0] = (lengthFieldSize - 1).toByte()
        nonce.copyInto(a, destinationOffset = 1)
        writeBigEndian(counter, a, offset = 1 + nonceLength, size = lengthFieldSize)
        return a
    }

    private companion object {
        const val BLOCK = 16

        /** RFC 3610 §2.2: 2-byte length below 2^16 - 2^8, otherwise 0xFFFE followed by a 4-byte length. */
        fun encodeAadLength(length: Int): ByteArray =
            if (length < 0xFF00) {
                byteArrayOf((length ushr 8).toByte(), length.toByte())
            } else {
                byteArrayOf(
                    0xFF.toByte(), 0xFE.toByte(),
                    (length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte(),
                )
            }

        fun writeBigEndian(value: Long, target: ByteArray, offset: Int, size: Int) {
            var v = value
            for (i in offset + size - 1 downTo offset) {
                target[i] = (v and 0xFF).toByte()
                v = v ushr 8
            }
        }
    }
}
