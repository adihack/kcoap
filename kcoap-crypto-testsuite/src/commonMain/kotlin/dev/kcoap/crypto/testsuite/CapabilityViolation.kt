package dev.kcoap.crypto.testsuite

/**
 * A capability question whose answer a backend does not live up to ([CapabilityInvariant]): it says yes and
 * an operation fails or gives a wrong result, or it says no and the operations run, or fail with another
 * exception than `UnsupportedAlgorithmException`, or the answer changes.
 */
public class CapabilityViolation(
    /** The question: an `AeadAlgorithm`, `KeyType`, `SignatureAlgorithm`, `KemAlgorithm` or `HashAlgorithm`. */
    public val question: Enum<*>,
    /** What the backend's `supports` member answered (its first answer, if it changed). */
    public val answer: Boolean,
    /** What went wrong, for the test log. */
    public val problem: String,
) {
    override fun toString(): String = "${question::class.simpleName}.${question.name}: supports answered " +
        "${if (answer) "yes" else "no"}, but $problem"
}
