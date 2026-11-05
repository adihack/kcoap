package dev.kcoap.common

/**
 * Marks a published SPI that nothing implements yet, such as `DatagramSecurity`, which the DTLS engine
 * will implement. Its shape may still change when the first implementation lands; the marker is removed
 * in the same release as that implementation and its interop tests.
 *
 * Using or implementing such an SPI needs `@OptIn(ExperimentalKcoapApi::class)`, or the annotation on
 * the using declaration to pass the requirement on.
 */
@RequiresOptIn(
    message = "An SPI with no implementation yet: its shape may change when the first one lands.",
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
public annotation class ExperimentalKcoapApi
