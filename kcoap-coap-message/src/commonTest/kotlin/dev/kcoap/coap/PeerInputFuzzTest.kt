package dev.kcoap.coap

import dev.kcoap.testkit.ByteMutator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * Text a peer sends: a `/.well-known/core` document (RFC 6690) and the URIs in it. What a peer supplies goes
 * through `parseOrNull`, which must never throw, `IllegalArgumentException` least of all (that is for mistakes of
 * the caller); `parse` of the same text must agree with it and throw nothing but `IllegalArgumentException`.
 */
class PeerInputFuzzTest {
    /** The RFC 6690 §5 examples, and links with flags, escapes and absolute targets. */
    private val documents = listOf(
        "</sensors>;ct=40;title=\"Sensor Index\",</sensors/temp>;rt=\"temperature-c\";if=\"sensor\"," +
            "</sensors/light>;rt=\"light-lux\";if=\"sensor\"," +
            "<http://www.example.com/sensors/t123>;anchor=\"/sensors/temp\";rel=\"describedby\"," +
            "</t>;anchor=\"/sensors/temp\";rel=\"alternate\"",
        "</firmware/v2.1>;rt=\"firmware\";sz=262144",
        " </a>;obs;sz=262144 , </b> ; title=\"x, \\\"y\\\"\";rt=\"one two\";ct=\"0 40\" ",
        "<coap://[2001:db8::1]:5683/sensors/temp?u=Cel>;anchor=\"coap+tcp://example.com/\";osc",
        "<coaps+ws://example.com:8443/a%20b/c>;rt=\"x\"",
    )

    /** Absolute URIs a document may name as targets or anchors. */
    private val uris = listOf(
        "coap://example.com/sensors/temp?u=Cel",
        "coaps://[2001:DB8::1]:6000/a//b?",
        "coap+tcp://192.0.2.1/%C3%BC?x=%26",
        "coaps+ws://example.com:8443/.well-known/core",
        "coap://h/temp%20a",
    )

    @Test
    fun rfc6690LinkFormatFromAPeerNeverThrowsIllegalArgumentException() {
        val random = Random(0x6690)
        repeat(ITERATIONS) { round ->
            val text = ByteMutator.mutate(documents[round % documents.size].encodeToByteArray(), random).decodeToString()
            val links = peerInput(text) { LinkFormat.parseOrNull(text) }
            if (links == null) {
                assertFailsWith<IllegalArgumentException>(text) { LinkFormat.parse(text) }
                return@repeat
            }
            assertEquals(links, LinkFormat.parse(text), text)
            for (link in links) {
                // The targets and anchors of a peer's links are its URIs too.
                peerInput(link.target) { CoapUri.parseOrNull(link.target) }
                link["anchor"]?.let { anchor -> peerInput(anchor) { CoapUri.parseOrNull(anchor) } }
                peerInput(link.toString()) {
                    listOf(link.resourceTypes, link.interfaces, link.contentFormats, link.maximumSize, link.title, link.isObservable)
                }
            }
        }
    }

    @Test
    fun rfc7252Section6_4UrisFromAPeerNeverThrowIllegalArgumentException() {
        val random = Random(0x7252)
        repeat(ITERATIONS) { round ->
            val text = ByteMutator.mutate(uris[round % uris.size].encodeToByteArray(), random).decodeToString()
            val uri = peerInput(text) { CoapUri.parseOrNull(text) }
            if (uri == null) {
                assertFailsWith<IllegalArgumentException>(text) { CoapUri.parse(text) }
                return@repeat
            }
            assertEquals(uri, CoapUri.parse(text), text)
            // RFC 7252 §6.5 composes what §6.4 decomposed: the normal form parses to the same URI.
            assertEquals(uri, CoapUri.parseOrNull(uri.toString()), "$text -> $uri")
        }
    }

    /** Runs [block] on [input] a peer supplied, failing on any exception. */
    private inline fun <T> peerInput(input: String, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        fail("Unexpected ${e::class.simpleName} for peer input \"$input\": $e")
    }

    private companion object {
        const val ITERATIONS = 5_000
    }
}
