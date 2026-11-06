package dev.kcoap.coap.server

import dev.kcoap.coap.CoapResponse

/**
 * Thrown by a resource handler to answer its request with [response], a 4.xx or 5.xx error (RFC 7252 §5.9.2,
 * §5.9.3), as if the handler had returned it, from however deep in the application's code: a validation, a
 * repository that finds nothing, an authorization check. The client gets [response] as it is, diagnostic payload
 * and options included, and the server logs it at DEBUG only, since it is an ordinary outcome and not a failure.
 *
 * Thrown while a notification is produced, it ends the observation as any error does (RFC 7641 §3.2, §4.2): the
 * client gets [response] without Observe and the server removes its observer.
 *
 * Every other exception of a handler is still answered with a bare 5.00 Internal Server Error and logged at ERROR.
 * To turn an application's own exceptions into responses in one place, override [CoapResource.handle] in a base
 * class of its resources and catch them there.
 *
 * ```
 * get {
 *     val reading = repository.find(path) ?: throw CoapResponseException(CoapResponse.error(CoapCode.NOT_FOUND))
 *     CoapResponse.content(reading)
 * }
 * ```
 *
 * Its message shows the response's code, options and payload size, never the payload.
 *
 * @throws IllegalArgumentException for a [response] that is not a 4.xx or 5.xx.
 */
public class CoapResponseException(public val response: CoapResponse) : Exception("Answered with $response") {
    init {
        require(response.code.isClientError || response.code.isServerError) {
            "Only a 4.xx or 5.xx response answers a request through an exception: $response"
        }
    }
}
