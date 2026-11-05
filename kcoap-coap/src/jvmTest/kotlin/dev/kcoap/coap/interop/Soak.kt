package dev.kcoap.coap.interop

/**
 * How long the interop soaks run (L9 and the K soak of docs/plan-qblock-and-platforms.md §1.5): [RUNS] runs
 * in `scripts/test interop`, [NIGHTLY_RUNS] in the nightly soak, which Gradle turns on with
 * `-Pkcoap.soak=true` (the system property `kcoap.soak` of the test JVM; docs/testing.md).
 */
object Soak {
    /** Runs of each soak case in the ordinary interop suite. */
    const val RUNS: Int = 3

    /** Runs of each soak case in the nightly soak. */
    const val NIGHTLY_RUNS: Int = 20

    /** Whether this is the nightly soak. */
    val isNightly: Boolean get() = System.getProperty("kcoap.soak") == "true"

    /** Runs of each soak case in this test run. */
    val runs: Int get() = if (isNightly) NIGHTLY_RUNS else RUNS
}
