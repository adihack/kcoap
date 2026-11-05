package dev.kcoap.coap.server

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.OptionSet
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.stack.Blockwise
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.InboundRequest
import dev.kcoap.coap.stack.OperationKey
import dev.kcoap.coap.stack.RequestHandler
import dev.kcoap.log.LogTags
import dev.kcoap.transport.ConnectionListener
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * A CoAP server: routes requests on a [CoapEndpoint] to a tree of [CoapResource]s.
 *
 * For each request, in the order of RFC 8613 Figure 1:
 * 1. message security verifies and decrypts it ([CoapServerConfig.security]); a failure is answered
 *    unprotected with Max-Age 0 (RFC 8613 §8.2), a freshness challenge replaces the response;
 * 2. Proxy-Uri and Proxy-Scheme get 5.05 (RFC 7252 §5.10.2), unknown paths 4.04, and unrecognized
 *    critical options 4.02 with a diagnostic, or a silent rejection for Non-confirmable requests
 *    (RFC 7252 §5.4.1);
 * 3. Block1 bodies are assembled (RFC 7959 §2.5) and Observe registrations recorded (RFC 7641 §4.1);
 * 4. the resource's handler runs; large responses go out in Block2 blocks (RFC 7959 §2.4);
 * 5. the response is protected with the request's security context and sent, piggybacked when it is
 *    ready in time, otherwise separately (RFC 7252 §5.2). No-Response (RFC 7967) suppresses it. A
 *    response that [CoapServerConfig.amplification] holds back from a client whose address is not yet
 *    verified is replaced by a 4.01 Unauthorized with an Echo option, piggybacked or Non-confirmable
 *    (RFC 9175 §2.4 item 3, §2.6).
 *
 * The pipeline is one failure boundary: whatever throws in it, a resource handler, the security layer, an
 * application callback behind it (a context repository, a sequence-number store) or the sending, the
 * client gets an answer instead of a timeout, and nothing escapes to the application's exception handler.
 * A security failure is answered as the security layer says (RFC 8613 §8.2), anything else with an
 * unprotected 5.00 Internal Server Error with Max-Age 0. A failure while sending a notification removes
 * that observer. When even the 5.00 cannot be sent, the failure is logged.
 *
 * The server is safe to use from any number of coroutines. Its handlers run on the endpoint's scope, one
 * coroutine per request, and [close] cancels those still running.
 *
 * Block-wise state is kept per peer [Endpoint] and per message-security identity, compared with
 * `equals`, so blocks of different security contexts never mix: a [CoapServerConfig.security]
 * implementation should return equal [dev.kcoap.coap.security.SecurityIdentity] objects for requests of
 * one context, otherwise its Block1 transfers end in 4.08.
 *
 * ```
 * val server = CoapServer(KtorUdpTransport.bind(port = 5683), scope) {
 *     resource("hello") { get { CoapResponse.content("Hello, ${peer}") } }
 * }
 * ```
 */
public class CoapServer private constructor(
    public val endpoint: CoapEndpoint,
    public val config: CoapServerConfig,
    private val ownsEndpoint: Boolean,
    routes: ResourceBuilder.() -> Unit,
) {
    /** Serves on an existing [endpoint], which a [dev.kcoap.coap.client.CoapClient] may share. */
    public constructor(
        endpoint: CoapEndpoint,
        config: CoapServerConfig = CoapServerConfig(),
        routes: ResourceBuilder.() -> Unit = {},
    ) : this(endpoint, config, false, routes)

    /** Serves on a new endpoint over [transport]; [close] closes both. */
    public constructor(
        transport: DatagramTransport,
        scope: CoroutineScope,
        config: CoapServerConfig = CoapServerConfig(),
        endpointConfig: CoapEndpointConfig = CoapEndpointConfig(),
        routes: ResourceBuilder.() -> Unit = {},
    ) : this(CoapEndpoint(transport, scope, endpointConfig), config, true, routes)

    /**
     * Serves CoAP over reliable transports (RFC 8323) on the connections [listeners] accept, such as TCP
     * and WebSocket listeners, on a new endpoint; [close] closes it and the listeners.
     */
    public constructor(
        listeners: List<ConnectionListener>,
        scope: CoroutineScope,
        config: CoapServerConfig = CoapServerConfig(),
        endpointConfig: CoapEndpointConfig = CoapEndpointConfig(),
        routes: ResourceBuilder.() -> Unit = {},
    ) : this(CoapEndpoint(scope, endpointConfig, listeners = listeners), config, true, routes)

    private val log = endpoint.config.loggerFactory.logger(LogTags.COAP_SERVER)
    private val rootResource = DslResource("")
    internal val observations = ObservationRegistry(this)
    private val blockwise = BlockwiseServer(config, endpoint.parameters.exchangeLifetime)
    internal val addressVerification = AddressVerification(config, endpoint.parameters.exchangeLifetime, endpoint::report)
    private val reserve = if (config.security != null) config.securityReserve else 0

    /** The parent of this server's request coroutines, so [close] stops them on a shared endpoint too. */
    private val handlers = SupervisorJob(endpoint.scope.coroutineContext[Job])

    /** The root of the resource tree; requests to "/" go to its handlers. */
    public val root: CoapResource get() = rootResource

    init {
        rootResource.server = this
        ResourceBuilder(rootResource).routes()
        if (config.wellKnownCore) {
            val container = rootResource.child(WellKnownCore.CONTAINER) ?: rootResource.add(DslResource(WellKnownCore.CONTAINER))
            // A /.well-known/core the application defined itself takes precedence.
            if (container.child(WellKnownCore.NAME) == null) container.add(WellKnownCore(rootResource))
        }
        check(endpoint.requestHandler == null) { "$endpoint already has a server" }
        endpoint.requestHandler = object : RequestHandler {
            override val context: CoroutineContext get() = handlers

            override suspend fun handle(request: InboundRequest) = process(request)

            override suspend fun onSessionEnded(peer: Endpoint) {
                observations.sessionEnded(peer)
                blockwise.forget(peer)
            }

            override suspend fun onEndpointClosed() {
                stop()
            }
        }
        // Only now, with the resources and the handler in place, does the endpoint start receiving.
        endpoint.attach()
        log.info { "Server started on $endpoint" }
    }

    /** Adds [resource] below the root. */
    public fun add(resource: CoapResource): CoapResource = rootResource.add(resource)

    /**
     * Stops serving: requests from now on are rejected as if there were no server, the handlers still
     * running are cancelled, and observers are dropped silently. A request on a connection whose handler
     * was cancelled before it answered gets 5.03 Service Unavailable, since RFC 8323 §3.3 has every
     * request on a connection answered. Closes the endpoint too if the server created it. A server also
     * stops when its endpoint closes.
     */
    public suspend fun close() {
        endpoint.requestHandler = null
        stop()
        if (ownsEndpoint) endpoint.close()
        log.info { "Server stopped" }
    }

    /** Cancels the handlers, not joined (one of them may be the caller), and takes no observers any more. */
    private suspend fun stop() {
        handlers.cancel()
        observations.close()
    }

    // ---------------------------------------------------------------- the request pipeline

    /** What answers a request; [isChallenge] marks a 4.01 with Echo that verifies the client's address. */
    private class Reply(val response: CoapResponse, val observation: ServerObservation? = null, val isChallenge: Boolean = false)

    private suspend fun process(inbound: InboundRequest) {
        val security = config.security
        val unprotected = if (security == null) {
            null
        } else {
            try {
                security.unprotectRequest(inbound.message, inbound.peer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MessageSecurityException) {
                log.warn { "Rejected a protected request from ${inbound.peer}: ${e.message}" }
                answerUnprotected(inbound, securityError(e))
                return
            } catch (e: Exception) {
                // An application callback behind the security layer failed, such as a context repository.
                log.error(e) { "The security layer failed on a request from ${inbound.peer}" }
                answerUnprotected(inbound, internalError())
                return
            }
        }
        val request = unprotected?.message ?: inbound.message
        val challenge = unprotected?.challenge
        if (unprotected != null && challenge != null) {
            // RFC 8613 Appendix B.1.2: the security layer answers instead of the resource.
            log.debug { "Security challenge ${challenge.code} for ${inbound.peer}" }
            try {
                inbound.respond(unprotected.protectResponse(challenge.toBuilder().apply { token = request.token }.build()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Could not send the security challenge to ${inbound.peer}" }
                answerUnprotected(inbound, internalError())
            }
            return
        }
        val reply = try {
            answer(inbound, request, unprotected) ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Failed to answer ${request.code} ${request.options.path}" }
            Reply(CoapResponse(CoapCode.INTERNAL_SERVER_ERROR))
        }
        send(inbound, request, unprotected, reply)
    }

    /** 5.00 with Max-Age 0, the shape of [securityError]: the answer when the pipeline itself failed. */
    private fun internalError(): CoapResponse = CoapResponse(CoapCode.INTERNAL_SERVER_ERROR, OptionSet.build { maxAge(0) })

    /** Answers without message security; a failure to send even that is only logged, as nothing else can be sent. */
    private suspend fun answerUnprotected(inbound: InboundRequest, response: CoapResponse) {
        try {
            inbound.respond(response.toMessage(inbound.message.token))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Could not answer ${inbound.message.code} from ${inbound.peer}" }
        }
    }

    /**
     * RFC 8613 §8.2: a security error is answered unprotected, with Outer Max-Age 0, carrying either the
     * security layer's structured error body (such as an EDHOC error message, RFC 9668 §3.3.1) in its
     * Content-Format, or the diagnostic text (RFC 7252 §5.5.2).
     */
    private fun securityError(e: MessageSecurityException): CoapResponse {
        val body = e.errorPayload
        val options = OptionSet.build {
            if (body != null) e.contentFormat?.let { contentFormat(it) }
            maxAge(0)
        }
        return CoapResponse(e.responseCode, options, body ?: e.diagnostic?.encodeToByteArray() ?: ByteArray(0))
    }

    /** The reply to [request], or null when the request is rejected without one. */
    private suspend fun answer(inbound: InboundRequest, request: CoapMessage, unprotected: UnprotectedRequest?): Reply? {
        val options = request.options
        val peer = inbound.peer
        val resource = rootResource.find(options.uriPath)
        val unrecognized = OptionCheck.unrecognizedCritical(options, OptionCheck.SERVER_RECOGNIZED + (resource?.recognizedOptions ?: emptySet()))
        if (unrecognized.isNotEmpty()) {
            val numbers = unrecognized.joinToString { it.number.toString() }
            if (!inbound.isConfirmable) {
                // RFC 7252 §5.4.1: in a Non-confirmable message they cause a rejection.
                inbound.reject("unrecognized critical options $numbers")
                return null
            }
            return Reply(CoapResponse.error(CoapCode.BAD_OPTION, "Unrecognized critical option $numbers"))
        }
        if (options.proxyUri != null || options.proxyScheme != null) {
            return Reply(CoapResponse.error(CoapCode.PROXYING_NOT_SUPPORTED, "This server is not a forward-proxy"))
        }
        if (resource == null) return Reply(CoapResponse(CoapCode.NOT_FOUND))
        val block1 = options.block1
        val block2 = options.block2
        val limits = endpoint.limits(inbound.connection)
        if (!limits.isReliable && (block1?.isBert == true || block2?.isBert == true)) {
            // RFC 7959 §2.2: SZX 7 is reserved and MUST lead to 4.00; it is BERT on RFC 8323 transports only.
            return Reply(CoapResponse.error(CoapCode.BAD_REQUEST, "Block size exponent 7 is reserved"))
        }
        val now = endpoint.now()
        val checksAddress = checksAddress(inbound, unprotected)
        val echo = options.echo
        if (checksAddress && echo != null && addressVerification.verify(peer, echo, now)) {
            // RFC 9175 §2.4 item 3: the repeat of a request whose response was held back, which must not run twice.
            addressVerification.takeWithheld(peer, request, now)?.let { return Reply(it) }
        }
        val key = OperationKey(peer, unprotected?.identity, request.code.value, Blockwise.operationOptions(options))

        var effective = request
        var block1Control: BlockOption? = null
        if (block1 != null) {
            when (val result = blockwise.receiveBlock1(key, request, block1, now, limits)) {
                is BlockwiseServer.Block1Result.Respond -> return Reply(result.response)
                is BlockwiseServer.Block1Result.Assembled -> {
                    effective = result.request
                    block1Control = result.control
                }
            }
        } else if (request.payloadSize > config.maxBodySize) {
            return Reply(CoapResponse.error(CoapCode.REQUEST_ENTITY_TOO_LARGE).withOptions { size1(config.maxBodySize.toLong()) })
        }

        if (block2 != null && block2.number > 0 && block1 == null) {
            blockwise.representation(key, now)?.let { cached ->
                return Reply(blockwise.serve(key, request.token, block2, cached, reserve, now, limits))
            }
            if (request.code != CoapCode.GET && request.code != CoapCode.FETCH) {
                // Re-running an unsafe method for a later block would repeat its effect.
                return Reply(CoapResponse.error(CoapCode.REQUEST_ENTITY_INCOMPLETE, "No response body to continue"))
            }
        }

        val observable = resource as? ObservableResource
        val observe = options.observe
        val isObserveMethod = request.code == CoapCode.GET || request.code == CoapCode.FETCH
        if (observe == OBSERVE_DEREGISTER && isObserveMethod) {
            observations.remove(peer, request.token, "deregistered")
        }
        val registering = observe == OBSERVE_REGISTER && isObserveMethod && observable != null &&
            observable.isObservable && (block2 == null || block2.number == 0L)
        if (registering && checksAddress && config.amplification == AmplificationPolicy.ALL_RESPONSES &&
            !addressVerification.verify(peer, echo, now)
        ) {
            // RFC 7641 §7: one registration has notifications sent to its address for as long as it lasts.
            return challenge(peer, now)
        }

        val context = RequestContext(effective, peer, unprotected?.identity, resource, inbound, inbound.peerIdentity)
        var response = acceptable(options, invoke(resource, context))
        var observation: ServerObservation? = null
        if (registering && response.isSuccess) {
            observation = ServerObservation(
                this, observable, peer, request.token, effective, unprotected, unprotected?.identity,
                block2?.size, key, now, inbound.connection, inbound.peerIdentity,
            )
            val registration = observations.register(observation)
            if (registration == ObservationRegistry.Registration.ADDED) {
                response = response.withOptions { observe(observable.nextSequence()) }
            } else {
                // RFC 7641 §4.1: a server unwilling or unable to add an observer answers as to a plain GET;
                // here because it stopped meanwhile, or because the observer bounds are reached (§7).
                observation = null
                response = response.withOptions { remove(CoapOptions.OBSERVE.number) }
                limitOf(registration)?.let { endpoint.report(EndpointEvent.LimitReached(peer, it)) }
            }
        } else if (CoapOptions.OBSERVE.number in response.options) {
            // RFC 7641 §4.1: only a registered observer gets a response with Observe.
            response = response.withOptions { remove(CoapOptions.OBSERVE.number) }
        }
        block1Control?.let { control -> response = response.withOptions { block1(control) } }
        val served = blockwise.serve(key, request.token, block2, response, reserve, now, limits)
        if (observation != null && !served.isSuccess) {
            // A representation that fits no block became 5.00, which ends the client's observation
            // (RFC 7641 §3.2), so the server keeps no observer for it either.
            observations.remove(observation, "registration response ${served.code}")
            return Reply(served.withOptions { remove(CoapOptions.OBSERVE.number) })
        }
        return Reply(served, observation)
    }

    private fun limitOf(registration: ObservationRegistry.Registration): EndpointEvent.LimitReached.Limit? = when (registration) {
        ObservationRegistry.Registration.TOO_MANY_OBSERVERS -> EndpointEvent.LimitReached.Limit.OBSERVERS
        ObservationRegistry.Registration.TOO_MANY_FOR_RESOURCE -> EndpointEvent.LimitReached.Limit.OBSERVERS_PER_RESOURCE
        ObservationRegistry.Registration.TOO_MANY_FOR_PEER -> EndpointEvent.LimitReached.Limit.OBSERVERS_PER_PEER
        ObservationRegistry.Registration.ADDED, ObservationRegistry.Registration.CLOSED -> null
    }

    /**
     * RFC 7252 §5.10.4: a successful representation in another Content-Format than the one the client
     * accepts becomes 4.06 Not Acceptable. Error responses take precedence, and responses without a
     * Content-Format say nothing about their format.
     */
    private fun acceptable(options: OptionSet, response: CoapResponse): CoapResponse {
        val accept = options.accept ?: return response
        val format = response.contentFormat
        if (!response.isSuccess || format == null || format == accept) return response
        return CoapResponse.error(CoapCode.NOT_ACCEPTABLE)
    }

    private suspend fun invoke(resource: CoapResource, context: RequestContext): CoapResponse = try {
        resource.handle(context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error(e) { "Handler of ${resource.path} failed on ${context.method}" }
        CoapResponse(CoapCode.INTERNAL_SERVER_ERROR)
    }

    private suspend fun send(inbound: InboundRequest, request: CoapMessage, unprotected: UnprotectedRequest?, reply: Reply) {
        val response = reply.response
        if (reply.observation == null && isSuppressed(request.options, response.code)) {
            // RFC 7967 §2: the client is not interested; a Confirmable request still gets its ACK.
            inbound.finishWithoutResponse()
            return
        }
        val message = response.toMessage(request.token)
        if (!reply.isChallenge && checksAddress(inbound, unprotected) && mustVerifyAddress(inbound, request, message, isBurst = false)) {
            val kept = if (AddressVerification.isRepeatSensitive(request.code)) AddressVerification.Withheld(request, response) else null
            // A registration whose response is not sent registers nothing (RFC 7641 §4.1).
            reply.observation?.let { observations.remove(it, "address not verified") }
            send(inbound, request, unprotected, challenge(inbound.peer, endpoint.now(), kept))
            return
        }
        val observation = reply.observation
        // RFC 9175 §2.4 item 3: a challenge goes piggybacked or Non-confirmable, never as a separate Confirmable
        // response, whose retransmissions would amplify towards the address it is meant to verify.
        val separateType = if (reply.isChallenge) CoapMessageType.NON else CoapMessageType.CON
        val transmission = try {
            val outgoing = unprotected?.protectResponse(message) ?: message
            log.debug { "${response.code} for ${request.code} ${request.options.path} from ${inbound.peer}" }
            inbound.respond(outgoing, owner = observation, separateType = separateType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Protecting or sending failed, for example the protected response does not fit: no observer
            // stays registered for a registration that was never answered, and a plain 5.00 is tried.
            log.error(e) { "Could not send ${response.code} for ${request.code} ${request.options.path} to ${inbound.peer}" }
            observation?.let { observations.remove(it, "registration response failed") }
            answerUnprotected(inbound, internalError())
            return
        }
        observation?.onRegistrationSent(transmission, transmission?.message?.type ?: CoapMessageType.ACK, endpoint.now())
    }

    // ---------------------------------------------------------------- address verification

    /**
     * Whether [inbound] comes from a client that only its address stands for, whose responses
     * [CoapServerConfig.amplification] may hold back: RFC 9175 §2.6 speaks of unauthenticated clients, so
     * neither message security ([unprotected]) nor channel security (DTLS) may have authenticated it, and it
     * must have come in a datagram, since a connection's handshake already showed that the client receives
     * at its address.
     */
    private fun checksAddress(inbound: InboundRequest, unprotected: UnprotectedRequest?): Boolean =
        config.amplification != AmplificationPolicy.OFF && unprotected == null && inbound.connection == null &&
            inbound.peerIdentity == PeerIdentity.None

    /**
     * Whether [response] to [request], [isBurst] when it stands for all the blocks of a Q-Block2 burst, is
     * larger than [CoapServerConfig.amplificationFactor] allows towards an address that is not verified yet
     * (RFC 9175 §2.4 item 3). The sizes are those of the messages on the wire.
     */
    private suspend fun mustVerifyAddress(inbound: InboundRequest, request: CoapMessage, response: CoapMessage, isBurst: Boolean): Boolean {
        if (!addressVerification.appliesTo(isBurst)) return false
        val requestSize = UdpMessageCodec.encode(inbound.message).size
        val responseSize = UdpMessageCodec.encode(response).size
        if (!addressVerification.exceedsLimit(requestSize, responseSize, isBurst)) return false
        return !addressVerification.verify(inbound.peer, request.options.echo, endpoint.now())
    }

    /**
     * RFC 9175 §2.4 item 3: a short 4.01 Unauthorized whose Echo value the client returns to show that it
     * receives at its address, in place of the response, which [kept] holds for the repeat when the request
     * must not run twice.
     */
    private suspend fun challenge(peer: Endpoint, now: Duration, kept: AddressVerification.Withheld? = null): Reply {
        val echo = addressVerification.challenge(peer, now, kept)
        log.debug { "Asked $peer to verify its address with Echo before a large response (RFC 9175 §2.4)" }
        return Reply(CoapResponse(CoapCode.UNAUTHORIZED, OptionSet.build { echo(echo) }), isChallenge = true)
    }

    /** RFC 7967 §2.1: bit 2 suppresses 2.xx, bit 8 4.xx and bit 16 5.xx responses. */
    private fun isSuppressed(options: OptionSet, code: CoapCode): Boolean {
        val value = options.noResponse ?: return false
        val mask = when (code.codeClass) {
            2 -> 0x02
            4 -> 0x08
            5 -> 0x10
            else -> 0
        }
        return value and mask != 0
    }

    // ---------------------------------------------------------------- notifications

    /**
     * A notification for [observation] with the resource's current state (RFC 7641 §4.2): the handler's
     * 2.xx response with a new Observe value, the first block if it is large (RFC 7959 §2.6), protected
     * with the registration's context. A non-2.xx response ends the observation and goes out without
     * Observe.
     */
    internal suspend fun notification(observation: ServerObservation): CoapMessage? {
        if (!observation.isActive) return null
        val resource = observation.resource
        val context = RequestContext(
            observation.request, observation.peer, observation.identity, resource, inbound = null, observation.peerIdentity,
        )
        var response = invoke(resource, context)
        if (response.isSuccess) response = response.withOptions { observe(resource.nextSequence()) }
        val requested = observation.blockSize?.let { Blockwise.block(0, false, it) }
        val limits = endpoint.limits(observation.connection)
        response = blockwise.serve(observation.operationKey, observation.token, requested, response, reserve, endpoint.now(), limits)
        if (!response.isSuccess) {
            // The handler's error, or block-wise's 5.00 for a representation that fits no block: either
            // way the client's observation ends with it, so the server's does too.
            observations.remove(observation, "non-2.xx notification ${response.code}")
            response = response.withOptions { remove(CoapOptions.OBSERVE.number) }
        }
        val message = response.toMessage(observation.token)
        return observation.unprotected?.protectResponse(message) ?: message
    }

    /** RFC 7641 §3.2: the resource is gone; 4.04 without Observe ends the observation. */
    internal suspend fun sendRemovalNotice(observation: ServerObservation) {
        val message = CoapResponse(CoapCode.NOT_FOUND).toMessage(observation.token)
        val outgoing = observation.unprotected?.protectResponse(message) ?: message
        endpoint.sendSeparately(observation.peer, outgoing, CoapMessageType.CON, observation, connection = observation.connection)
    }

    override fun toString(): String = "CoapServer($endpoint)"

    private companion object {
        const val OBSERVE_REGISTER = 0L
        const val OBSERVE_DEREGISTER = 1L
    }
}
