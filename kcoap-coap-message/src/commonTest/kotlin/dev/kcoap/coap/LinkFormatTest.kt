package dev.kcoap.coap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkFormatTest {
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
            assertNull(LinkFormat.parseOrNull(broken), broken)
        }
        val document = " </a>;obs;sz=262144 , </b> ; title=\"x, \\\"y\\\"\";rt=\"one two\";ct=\"0 40\" "
        assertEquals(links, LinkFormat.parseOrNull(document))
    }

    @Test
    fun aWebLinkKeepsACopyOfItsAttributes() {
        val attributes = mutableListOf<Pair<String, String?>>("rt" to "temperature-c")
        val link = WebLink("/sensors/temp", attributes)
        attributes += "obs" to null
        assertEquals(listOf<Pair<String, String?>>("rt" to "temperature-c"), link.attributes)
        assertFalse(link.isObservable)
        assertEquals(emptyList(), WebLink("/plain").attributes)
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
}
