package dev.kcoap.coap

import dev.kcoap.coap.server.CoapResource
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.server.ResourceAttributes
import dev.kcoap.coap.stack.CoapException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DiscoveryTest {
    @Test
    fun rfc6690Section5ExamplesSerializeAndParse() {
        // The RFC 6690 §5 example document, without the line feeds.
        val text = "</sensors>;ct=40;title=\"Sensor Index\"," +
            "</sensors/temp>;rt=\"temperature-c\";if=\"sensor\"," +
            "</sensors/light>;rt=\"light-lux\";if=\"sensor\"," +
            "<http://www.example.com/sensors/t123>;anchor=\"/sensors/temp\";rel=\"describedby\"," +
            "</t>;anchor=\"/sensors/temp\";rel=\"alternate\""
        val links = LinkFormat.parse(text)
        assertEquals(5, links.size)
        assertEquals("/sensors", links[0].target)
        assertEquals(listOf(40), links[0].contentFormats)
        assertEquals("Sensor Index", links[0].title)
        assertEquals(listOf("temperature-c"), links[1].resourceTypes)
        assertEquals(listOf("sensor"), links[1].interfaces)
        assertEquals("/sensors/temp", links[3]["anchor"])
        assertEquals(text, LinkFormat.serialize(links))
        assertEquals(links, LinkFormat.parse(LinkFormat.serialize(links)))
    }

    @Test
    fun linkFormatHandlesFlagsSpacesEscapesAndCommasInQuotes() {
        val links = LinkFormat.parse(" </a>;obs;sz=262144 , </b> ; title=\"x, \\\"y\\\"\";rt=\"one two\";ct=\"0 40\" ")
        assertTrue(links[0].isObservable)
        assertEquals(262144L, links[0].maximumSize)
        assertNull(links[0]["obs"])
        assertEquals("x, \"y\"", links[1].title)
        assertEquals(listOf("one", "two"), links[1].resourceTypes)
        assertEquals(listOf(0, 40), links[1].contentFormats)
        assertEquals("</b>;title=\"x, \\\"y\\\"\";rt=\"one two\";ct=\"0 40\"", links[1].toString())
        assertEquals(emptyList(), LinkFormat.parse("  "))
        assertEquals(null, LinkFormat.parse("</big>;sz=99999999999999999999")[0].maximumSize)
        assertEquals(links[0], LinkFormat.parse("</a>;obs;sz=262144")[0])
        assertEquals(links[0].hashCode(), LinkFormat.parse("</a>;obs;sz=262144")[0].hashCode())
        for (broken in listOf("<a", "</a>;", "</a> x", "</a>;t=\"open", "</a>;t=\"\\", "a")) {
            assertFailsWith<IllegalArgumentException>(broken) { LinkFormat.parse(broken) }
        }
    }

    @Test
    fun rfc6690Section4_1QueryFiltering() {
        val links = LinkFormat.parse("</sensors/temp>;rt=\"temperature-c\";if=\"sensor\",</sensors/light>;rt=\"light-lux core.sen-light\";if=\"sensor\",</fw>;sz=262144;obs")
        fun targets(vararg query: String) = LinkFormat.filter(links, query.toList()).map { it.target }
        assertEquals(listOf("/sensors/light"), targets("rt=light-lux"))
        assertEquals(listOf("/sensors/light"), targets("rt=core.sen-light"), "each relation-type value matches alone")
        assertEquals(listOf("/sensors/temp", "/sensors/light"), targets("if=sensor"))
        assertEquals(listOf("/sensors/temp", "/sensors/light"), targets("href=/sensors*"))
        assertEquals(listOf("/fw"), targets("href=/fw"))
        assertEquals(listOf("/sensors/temp"), targets("rt=temp*"))
        assertEquals(listOf("/fw"), targets("sz=*"))
        assertEquals(listOf("/fw"), targets("obs"))
        assertEquals(listOf("/fw"), targets("sz=262144"))
        assertEquals(emptyList(), targets("rt=nothing"))
        assertEquals(listOf("/sensors/light"), targets("if=sensor", "rt=light*"))
        assertEquals(links, LinkFormat.filter(links, emptyList()))
    }

    @Test
    fun rfc6690Section4WellKnownCoreListsTheDiscoverableResources() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            get { CoapResponse.content("root") }
            resource("sensors") {
                attributes = ResourceAttributes(contentFormats = listOf(ContentFormat.LINK_FORMAT), title = "Sensor Index")
                get { CoapResponse.content("") }
                resource("temp") {
                    attributes = ResourceAttributes(
                        resourceTypes = listOf("temperature-c"),
                        interfaces = listOf("sensor"),
                        contentFormats = listOf(0, 50),
                        maximumSize = 64,
                        extra = listOf("foo" to "bar", "flag" to null),
                    )
                    observable()
                    get { CoapResponse.content("22") }
                }
                resource("hidden") {
                    discoverable = false
                    get { CoapResponse.content("") }
                }
            }
            resource("group") { resource("leaf") { put { CoapResponse.changed() } } }
        }
        val client = fixture.client()

        val document = client.get(fixture.uri(server, "/.well-known/core"))
        assertEquals(ContentFormat.LINK_FORMAT, document.contentFormat)
        assertEquals(
            "</>," +
                "</sensors>;title=\"Sensor Index\";ct=40," +
                "</sensors/temp>;rt=\"temperature-c\";if=\"sensor\";ct=\"0 50\";sz=64;obs;foo=\"bar\";flag," +
                "</group/leaf>",
            document.payloadAsText(),
        )
        val filtered = client.discover(fixture.uri(server), "rt=temperature-c")
        assertEquals(listOf("/sensors/temp"), filtered.map { it.target })
        assertTrue(filtered.single().isObservable)
        assertEquals(4, client.discover(CoapUri.parse(fixture.uri(server, "/anything"))).size)
    }

    @Test
    fun wellKnownCoreCanBeDisabledAndDiscoveryFailuresAreReported() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(CoapServerConfig(wellKnownCore = false)) { }
        val client = fixture.client()

        val error = assertFailsWith<CoapException> { client.discover(fixture.uri(server)) }
        assertEquals(CoapException.Reason.BAD_RESPONSE, error.reason)

        val broken = fixture.server {
            resource(".well-known") { resource("core") { get { CoapResponse.content("<not link format") } } }
        }
        assertEquals(CoapException.Reason.BAD_RESPONSE, assertFailsWith<CoapException> { client.discover(fixture.uri(broken)) }.reason)
    }

    @Test
    fun aLargeDiscoveryDocumentGoesBlockwise() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            repeat(80) { index -> resource("resource-number-$index") { get { CoapResponse.content("$index") } } }
        }
        val client = fixture.client()

        assertEquals(80, client.discover(fixture.uri(server)).size)
        assertTrue(fixture.traffic.any { it.message?.options?.block2?.more == true })
    }

    @Test
    fun theResourceTreeCanChangeAtRuntime() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            resource("items") {
                post {
                    val name = "item${resource.children.size}"
                    resource.add(object : CoapResource(name) {
                        override suspend fun handleGet(context: RequestContext) = CoapResponse.content(name)
                        override suspend fun handleDelete(context: RequestContext): CoapResponse {
                            parent?.remove(this.name)
                            return CoapResponse.deleted()
                        }
                    })
                    CoapResponse.created("items", name)
                }
            }
        }
        val client = fixture.client()

        val created = client.post(fixture.uri(server, "/items"))
        assertEquals(CoapCode.CREATED, created.code)
        assertEquals(listOf("items", "item0"), created.options.locationPath)
        assertEquals("item0", client.get(fixture.uri(server, "/items/item0")).payloadAsText())
        assertEquals(CoapCode.DELETED, client.delete(fixture.uri(server, "/items/item0")).code)
        assertEquals(CoapCode.NOT_FOUND, client.get(fixture.uri(server, "/items/item0")).code)
    }

    @Test
    fun resourcesFormATree() {
        val root = object : CoapResource("") {}
        val a = root.add(object : CoapResource("a") {})
        val b = a.add(object : ObservableResource("b") {})
        assertEquals("/", root.path)
        assertEquals("/a/b", b.path)
        assertSame(b, root.find(listOf("a", "b")))
        assertNull(root.find(listOf("a", "c")))
        assertSame(a, b.parent)
        assertTrue(b.toString().contains("/a/b"))
        assertEquals(0, (b as ObservableResource).observerCount, "not attached to a server")
        b.notifyObservers()
        assertFailsWith<IllegalArgumentException> { a.add(b) }
        assertFailsWith<IllegalArgumentException> { root.add(object : CoapResource("a") {}) }
        assertFailsWith<IllegalArgumentException> { root.add(object : CoapResource("") {}) }
        assertFailsWith<IllegalArgumentException> { object : CoapResource("a/b") {} }
        assertNull(root.remove("missing"))
        assertSame(a, root.remove("a"))
        assertNull(a.parent)
        assertFalse(root.children.contains(a))
    }

    @Test
    fun modelTypesValidateAndRender() {
        val response = CoapResponse.content("hi")
        assertEquals("2.05 Content [Content-Format: 0] 2 bytes", response.toString())
        assertEquals(response, CoapResponse.of(response.toMessage(byteArrayOf(1))))
        assertEquals(response.hashCode(), CoapResponse.content("hi").hashCode())
        assertFalse(response == CoapResponse.content("ho"))
        assertTrue(response.isSuccess)
        assertEquals(CoapCode.CONTENT, CoapResponse.content(byteArrayOf(1)).code)
        assertNull(CoapResponse.content(byteArrayOf(1)).contentFormat)
        assertEquals(CoapCode.DELETED, CoapResponse.deleted().code)
        assertEquals("4.04 Not Found", CoapResponse.error(CoapCode.NOT_FOUND).toString())
        assertFailsWith<IllegalArgumentException> { CoapResponse(CoapCode.GET) }
        assertFailsWith<IllegalArgumentException> { CoapResponse.error(CoapCode.CONTENT) }

        val request = CoapRequest.Builder(CoapCode.POST, CoapUri.parse("coap://[::1]/x")).payload("body").accept(50).nonConfirmable().build()
        assertEquals("0.02 POST coap://[::1]/x [Accept: 50] 4 bytes NON", request.toString())
        assertEquals(request.toString(), request.toBuilder().build().toString())
        assertEquals(4, request.payloadSize)
        assertFailsWith<IllegalArgumentException> { CoapRequest(CoapCode.CONTENT, CoapUri.parse("coap://[::1]/x")) }

        val attributes = ResourceAttributes(title = "t")
        assertTrue(attributes.toString().contains("title"))
        assertFailsWith<IllegalArgumentException> { ResourceAttributes(contentFormats = listOf(70000)) }
        assertFailsWith<IllegalArgumentException> { ResourceAttributes(maximumSize = -1) }
        assertFailsWith<IllegalArgumentException> { ResourceAttributes(extra = listOf("href" to "x")) }
    }
}
