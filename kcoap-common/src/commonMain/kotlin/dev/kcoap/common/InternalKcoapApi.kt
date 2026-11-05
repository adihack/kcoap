package dev.kcoap.common

/**
 * Marks plumbing that kcoap's modules share with each other, such as byte builders and option codecs.
 * It is public only because Kotlin has no visibility between modules of one library: it may change or
 * disappear in any release, and it is left out of the published ABI dumps.
 *
 * kcoap's own modules opt in through their build. Applications should not use it; a call needs
 * `@OptIn(InternalKcoapApi::class)`.
 */
@RequiresOptIn(
    message = "kcoap's internal plumbing between its modules: it may change or disappear in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class InternalKcoapApi
