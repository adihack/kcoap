package dev.kcoap.crypto.testsuite

/**
 * Random bytes from a script, handed out in order, so that a backend under test replays a known-answer
 * vector through its public operations: the `d || z` and `m` of ML-KEM (FIPS 203 Algorithms 19 and 20), the
 * `ξ` and `rnd` of ML-DSA (FIPS 204 Algorithms 1 and 2).
 *
 * A backend draws its randomness from here through its own `CryptoBackend.randomBytes`, usually that of a
 * classical backend it wraps (see [MlKemConformanceTest]). Asking for more than is left fails, and
 * [remaining] shows whether an operation drew exactly what the standard prescribes. Not thread-safe: one
 * test uses one script.
 *
 * @param script the bytes to hand out, in order; the arrays are copied.
 */
public class ScriptedRandom(vararg script: ByteArray) {
    private val bytes: ByteArray = script.fold(ByteArray(0)) { all, next -> all + next }
    private var offset = 0

    /** How many scripted bytes are left. */
    public val remaining: Int get() = bytes.size - offset

    /**
     * The next [size] scripted bytes.
     *
     * @throws IllegalStateException when fewer than [size] are left: the backend draws more randomness than
     *   the vector holds.
     */
    public fun next(size: Int): ByteArray {
        require(size >= 0) { "Cannot draw $size random bytes" }
        check(size <= remaining) { "$size random bytes requested, $remaining scripted" }
        return bytes.copyOfRange(offset, offset + size).also { offset += size }
    }
}
