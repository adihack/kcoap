package dev.kcoap.coap

import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.security.ServerMessageSecurity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.server.AccessPolicy
import dev.kcoap.coap.server.AccessRequest
import dev.kcoap.coap.server.AmplificationPolicy
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.QBlockServerConfig
import dev.kcoap.coap.server.ResourceAttributes
import dev.kcoap.coap.server.ResourceBuilder
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * The server's access policies (ADR 0008), with a toy message security in which a request is protected when it
 * carries the OSCORE option, whose value names the sender, so a scripted client ([QBlockPeer]) can send protected
 * and unprotected requests from one address with one token.
 */
class AccessPolicyTest {
    private data class Kid(val kid: Int) : SecurityIdentity {
        override val description: String get() = "kid $kid"
    }

    /** Protected requests carry OSCORE with the sender's kid; responses to them get an empty OSCORE option. */
    private object Toy : ServerMessageSecurity {
        override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest? {
            val kid = request.options.oscore ?: return null
            return object : UnprotectedRequest {
                override val message = request.toBuilder().options { remove(CoapOptions.OSCORE.number) }.build()
                override val identity: SecurityIdentity = Kid(kid.firstOrNull()?.toInt() ?: 0)

                override suspend fun protectResponse(response: CoapMessage): CoapMessage =
                    response.toBuilder().options { oscore(ByteArray(0)) }.build()
            }
        }
    }

    private class Fixture(test: TestScope, config: CoapServerConfig, routes: ResourceBuilder.() -> Unit) {
        val stack = StackFixture(test)
        val config = config
        val routes = routes
        lateinit var server: CoapServer
        lateinit var peer: QBlockPeer

        suspend fun start(): Fixture {
            server = stack.server(config, routes = routes)
            peer = QBlockPeer.of(stack, server)
            return this
        }

        /** Sends [message] and returns the server's answer. */
        suspend fun ask(message: CoapMessage): CoapMessage {
            peer.send(message)
            return assertNotNull(peer.next(), "an answer to $message")
        }
    }

    private suspend fun TestScope.fixture(
        config: CoapServerConfig = CoapServerConfig(security = Toy),
        routes: ResourceBuilder.() -> Unit,
    ) = Fixture(this, config, routes).start()

    private fun request(
        peer: QBlockPeer,
        path: String,
        kid: Int? = null,
        code: CoapCode = CoapCode.GET,
        type: CoapMessageType = CoapMessageType.CON,
        token: ByteArray = peer.token(),
        payload: ByteArray = ByteArray(0),
        options: OptionSet.Builder.() -> Unit = {},
    ) = CoapMessage.request(code) {
        this.type = type
        this.token = token
        this.payload = payload
        options {
            uriPath(path)
            kid?.let { oscore(byteArrayOf(it.toByte())) }
            options()
        }
    }

    @Test
    fun adr0008TheDefaultFailsClosedWithMessageSecurityAndStaysOpenWithout() {
        assertEquals(AccessPolicy.MessageSecurityRequired, CoapServerConfig(security = Toy).defaultAccess)
        assertEquals(AccessPolicy.Public, CoapServerConfig().defaultAccess)
        assertEquals(AccessPolicy.Authenticated, CoapServerConfig(security = Toy, defaultAccess = AccessPolicy.Authenticated).defaultAccess)
        assertTrue("defaultAccess=MessageSecurityRequired" in CoapServerConfig(security = Toy).toString())
    }

    @Test
    fun adr0008AnUnprotectedRequestToAProtectedResourceGets401AndNeverReachesTheHandler() = runTest {
        var handled = 0
        val f = fixture {
            resource("secret") {
                get {
                    handled++
                    CoapResponse.content("classified for ${securityIdentity?.description}")
                }
            }
            resource("open") {
                access = AccessPolicy.Public
                get { CoapResponse.content("open") }
            }
        }

        val refused = f.ask(request(f.peer, "secret"))
        assertEquals(CoapCode.UNAUTHORIZED, refused.code)
        assertNull(refused.options.oscore, "an unprotected request gets an unprotected refusal")
        assertEquals(0, refused.payloadSize, "no diagnostic for an unauthenticated caller")
        assertEquals(0, handled)

        val served = f.ask(request(f.peer, "secret", kid = 1))
        assertEquals("classified for kid 1", served.payloadAsText())
        assertNotNull(served.options.oscore, "protected")
        assertEquals("open", f.ask(request(f.peer, "open")).payloadAsText())
        assertEquals(1, handled)
    }

    @Test
    fun rfc7252Section5_9_2_4AnAuthenticatedRequestThePolicyRefusesGets403Protected() = runTest {
        val f = fixture {
            resource("admin") {
                access = AccessPolicy { it.securityIdentity == Kid(7) }
                post { CoapResponse.changed() }
            }
        }

        val refused = f.ask(request(f.peer, "admin", kid = 1, code = CoapCode.POST))
        assertEquals(CoapCode.FORBIDDEN, refused.code)
        assertNotNull(refused.options.oscore, "refused under the request's protection")
        assertEquals(CoapCode.UNAUTHORIZED, f.ask(request(f.peer, "admin", code = CoapCode.POST)).code)
        assertEquals(CoapCode.CHANGED, f.ask(request(f.peer, "admin", kid = 7, code = CoapCode.POST)).code)
    }

    @Test
    fun adr0008APolicyGovernsItsSubtreeAndAChildMayOpenItself() = runTest {
        val f = fixture(CoapServerConfig()) {
            assertNull(access, "the root inherits the server's default")
            resource("api") {
                access = AccessPolicy.MessageSecurityRequired
                assertEquals(AccessPolicy.MessageSecurityRequired, access)
                get { CoapResponse.content("api") }
                resource("config") { put { CoapResponse.changed() } }
                resource("health") {
                    access = AccessPolicy.Public
                    get { CoapResponse.content("up") }
                }
            }
            resource("hello") { get { CoapResponse.content("hello") } }
        }

        assertEquals(CoapCode.UNAUTHORIZED, f.ask(request(f.peer, "api")).code)
        assertEquals(CoapCode.UNAUTHORIZED, f.ask(request(f.peer, "api/config", code = CoapCode.PUT)).code, "inherited")
        assertEquals("up", f.ask(request(f.peer, "api/health")).payloadAsText())
        assertEquals("hello", f.ask(request(f.peer, "hello")).payloadAsText(), "the server's default is Public")
    }

    @Test
    fun adr0008APathThatDoesNotExistIsRefusedLikeItsNearestAncestorSoNamesDoNotLeak() = runTest {
        val f = fixture {
            resource("open") {
                access = AccessPolicy.Public
                get { CoapResponse.content("open") }
            }
            resource("secret") { resource("real") { get { CoapResponse.content("real") } } }
        }

        assertEquals(CoapCode.UNAUTHORIZED, f.ask(request(f.peer, "secret/real")).code)
        assertEquals(CoapCode.UNAUTHORIZED, f.ask(request(f.peer, "secret/imagined")).code, "the same answer as for one that exists")
        assertEquals(CoapCode.UNAUTHORIZED, f.ask(request(f.peer, "nothing/here")).code, "the root's policy is the default")
        assertEquals(CoapCode.NOT_FOUND, f.ask(request(f.peer, "open/nothing")).code, "a Public parent permits the 4.04")
        assertEquals(CoapCode.NOT_FOUND, f.ask(request(f.peer, "secret/imagined", kid = 1)).code)
    }

    @Test
    fun adr0008ARefusedBlock1UploadIsNotBuffered() = runTest {
        var stored: String? = null
        val f = fixture {
            resource("store") {
                put {
                    stored = payloadAsText()
                    CoapResponse.changed()
                }
            }
        }
        val token = f.peer.token()

        // RFC 7959 §2.5: a buffered block would be answered 2.31 Continue.
        val first = f.ask(request(f.peer, "store", code = CoapCode.PUT, token = token, payload = ByteArray(16) { 'a'.code.toByte() }) {
            block1(BlockOption(0, true, 0))
        })
        assertEquals(CoapCode.UNAUTHORIZED, first.code)
        val last = f.ask(request(f.peer, "store", kid = 1, code = CoapCode.PUT, token = token, payload = "b".encodeToByteArray()) {
            block1(BlockOption(1, false, 0))
        })
        assertEquals(CoapCode.REQUEST_ENTITY_INCOMPLETE, last.code, "no earlier block of the body is kept, under any identity")
        assertNull(stored)
    }

    @Test
    fun adr0008ARefusedQBlockBodyIsNotBufferedAndGetsNoBurst() = runTest {
        val qBlock = QBlockServerConfig(isEnabled = true, withMessageSecurity = true)
        var stored = 0
        val f = fixture(CoapServerConfig(security = Toy, qBlock = qBlock, amplification = AmplificationPolicy.OFF)) {
            resource("store") {
                put {
                    stored++
                    CoapResponse.changed()
                }
            }
            resource("big") { get { CoapResponse.content(ByteArray(3000), ContentFormat.OCTET_STREAM) } }
        }
        val body = ByteArray(640)
        val tag = byteArrayOf(9, 9)

        for (num in 0L..9L) {
            f.peer.send(
                request(f.peer, "store", code = CoapCode.PUT, type = CoapMessageType.NON, payload = body.copyOfRange((num * 64).toInt(), ((num + 1) * 64).toInt())) {
                    requestTag(tag)
                    size1(body.size.toLong())
                    qBlock1(BlockOption(num, num < 9, 2))
                },
            )
        }
        val answers = f.peer.drain()
        assertEquals(List(10) { CoapCode.UNAUTHORIZED }, answers.map { it.code }, "every block refused")
        assertEquals(0, f.server.qBlock.state().partialBodies, "nothing of the upload is kept")
        assertEquals(0, stored)

        f.peer.send(request(f.peer, "big", type = CoapMessageType.NON) { qBlock2(BlockOption(0, true, 2)) })
        assertEquals(listOf(CoapCode.UNAUTHORIZED), f.peer.drain().map { it.code }, "no burst, one refusal")
        assertTrue(f.server.qBlock.state().isEmpty)
    }

    @Test
    fun adr0008AnUnauthenticatedRequestRegistersNoObservation() = runTest {
        var handled = 0
        lateinit var counter: ObservableResource
        val f = fixture {
            counter = resource("counter") {
                observable()
                get {
                    handled++
                    CoapResponse.content("tick")
                }
            }
        }

        assertEquals(CoapCode.UNAUTHORIZED, f.ask(request(f.peer, "counter") { observe(0) }).code)
        assertEquals(0, counter.observerCount)
        assertEquals(0, handled)
    }

    @Test
    fun rfc7641Section3_6OnlyTheRegistrationsIdentityCancelsOrReplacesIt() = runTest {
        lateinit var counter: ObservableResource
        val f = fixture {
            counter = resource("counter") {
                access = AccessPolicy.Public
                observable()
                get { CoapResponse.content("tick for ${securityIdentity?.description ?: "anyone"}") }
            }
        }
        val token = f.peer.token()

        val registered = f.ask(request(f.peer, "counter", kid = 1, token = token) { observe(0) })
        assertNotNull(registered.options.observe)
        assertEquals(1, counter.observerCount)

        // From the same address with the same token: without message security, and with another identity.
        f.ask(request(f.peer, "counter", token = token) { observe(1) })
        f.ask(request(f.peer, "counter", kid = 2, token = token) { observe(1) })
        assertEquals(1, counter.observerCount, "neither cancelled kid 1's registration")
        val other = f.ask(request(f.peer, "counter", token = token) { observe(0) })
        assertNotNull(other.options.observe)
        assertEquals(2, counter.observerCount, "another identity's registration does not replace it")

        // RFC 7641 §4.1: the same identity, endpoint and token replaces; its own deregistration ends it.
        f.ask(request(f.peer, "counter", kid = 1, token = token) { observe(0) })
        assertEquals(2, counter.observerCount)
        f.ask(request(f.peer, "counter", kid = 1, token = token) { observe(1) })
        assertEquals(1, counter.observerCount)
        f.ask(request(f.peer, "counter", token = token) { observe(1) })
        assertEquals(0, counter.observerCount)
    }

    @Test
    fun rfc6690Section4WellKnownCoreListsOnlyWhatTheCallerMayUseWithOscForOscoreOnly() = runTest {
        val f = fixture {
            resource("open") {
                access = AccessPolicy.Public
                get { CoapResponse.content("open") }
            }
            resource("secret") { get { CoapResponse.content("secret") } }
            resource("named") {
                access = AccessPolicy { it.securityIdentity == Kid(7) }
                get { CoapResponse.content("named") }
            }
            resource("marked") {
                attributes = ResourceAttributes(extra = listOf(LinkFormat.OSCORE to null))
                get { CoapResponse.content("marked") }
            }
        }
        suspend fun discover(kid: Int?): String {
            val answer = f.ask(request(f.peer, ".well-known/core", kid = kid))
            assertEquals(CoapCode.CONTENT, answer.code, "/.well-known/core is Public")
            return answer.payloadAsText()
        }

        assertEquals("</open>", discover(kid = null))
        // RFC 8613 §9: osc marks a target reachable with OSCORE only.
        assertEquals("</open>,</secret>;osc,</marked>;osc", discover(kid = 1))
        assertEquals("</open>,</secret>;osc,</named>,</marked>;osc", discover(kid = 7), "osc is never added twice")
    }

    @Test
    fun adr0008AFailingPolicyIsAnsweredWith500() = runTest {
        var handled = 0
        val f = fixture(CoapServerConfig()) {
            resource("broken") {
                access = AccessPolicy { error("the ACL store is down") }
                get {
                    handled++
                    CoapResponse.content("never")
                }
            }
        }

        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, f.ask(request(f.peer, "broken")).code)
        assertEquals(0, handled)
    }

    @Test
    fun adr0008TheBuiltInPoliciesDecideOnTheIdentities() = runTest {
        val peer = Endpoint.Udp("192.0.2.1", 5683)
        fun of(identity: SecurityIdentity?, channel: PeerIdentity) = AccessRequest(CoapCode.GET, listOf("x"), null, peer, identity, channel)
        val nobody = of(null, PeerIdentity.None)
        val oscore = of(Kid(1), PeerIdentity.None)
        val dtls = of(null, PeerIdentity.PreSharedKey(byteArrayOf(1)))

        assertEquals(listOf(true, true, true), listOf(nobody, oscore, dtls).map { AccessPolicy.Public.permits(it) })
        assertEquals(listOf(false, true, false), listOf(nobody, oscore, dtls).map { AccessPolicy.MessageSecurityRequired.permits(it) })
        assertEquals(listOf(false, true, true), listOf(nobody, oscore, dtls).map { AccessPolicy.Authenticated.permits(it) })
        assertEquals("AccessRequest(0.01 GET /x from $peer, kid 1)", oscore.toString())
        assertEquals("AccessRequest(0.01 GET /x from $peer, no message security)", nobody.toString())
        assertEquals("Public", AccessPolicy.Public.toString())
    }
}
