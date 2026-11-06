package dev.kcoap.sample.jvm

import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.cose.CoseCurve
import dev.kcoap.cose.CoseKey
import dev.kcoap.cose.CoseKeyType
import dev.kcoap.cose.CwtClaimsSet
import dev.kcoap.crypto.KeyType
import dev.kcoap.edhoc.AuthenticationCredential
import dev.kcoap.edhoc.AuthenticationKind
import dev.kcoap.edhoc.CipherSuite
import dev.kcoap.edhoc.CredentialIdentifier
import dev.kcoap.edhoc.EdhocInitiatorConfig
import dev.kcoap.edhoc.EdhocMethod
import dev.kcoap.edhoc.EdhocResponderConfig
import dev.kcoap.edhoc.LocalCredential
import dev.kcoap.edhoc.TrustedCredentials
import dev.kcoap.edhoc.oscore.CombinedRequestServer
import dev.kcoap.edhoc.oscore.EdhocClientHandshake
import dev.kcoap.edhoc.oscore.EdhocIdentity
import dev.kcoap.edhoc.oscore.EdhocResource
import dev.kcoap.edhoc.oscore.EdhocServer
import dev.kcoap.edhoc.oscore.PeerSecurity
import dev.kcoap.oscore.InMemoryContextRepository
import dev.kcoap.oscore.OscoreServer
import dev.kcoap.transport.ktor.KtorUdpTransport
import kotlinx.coroutines.CoroutineScope

// docs/getting-started.md, "EDHOC, then OSCORE": EDHOC (RFC 9528) authenticates both sides with their credentials,
// here CWT Claims Sets with Ed25519 keys, and gives them a fresh OSCORE context; with the combined request of
// RFC 9668 the whole handshake takes two round trips.

suspend fun credential(subject: String, kid: Byte): LocalCredential {
    val keys = crypto.generateKeyPair(KeyType.ED25519)
    val key = CoseKey(CoseKeyType.OKP, CoseCurve.ED25519, keys.publicKey, kid = byteArrayOf(kid))
    return LocalCredential(
        AuthenticationCredential.ccs(CwtClaimsSet(subject, key)),
        CredentialIdentifier.KeyId(byteArrayOf(kid)),
        keys.privateKey,
        AuthenticationKind.SIGNATURE,
    )
}

fun initiatorConfig(client: LocalCredential, server: LocalCredential) = EdhocInitiatorConfig(
    method = EdhocMethod.SIGNATURE_SIGNATURE,
    cipherSuites = listOf(CipherSuite.SUITE_0),
    credentials = listOf(client),
    credentialResolver = TrustedCredentials(crypto, listOf(server.credential)),
)

fun responderConfig(server: LocalCredential, client: LocalCredential) = EdhocResponderConfig(
    methods = setOf(EdhocMethod.SIGNATURE_SIGNATURE),
    cipherSuites = listOf(CipherSuite.SUITE_0),
    credentials = listOf(server),
    credentialResolver = TrustedCredentials(crypto, listOf(client.credential)),
)

suspend fun edhocServer(scope: CoroutineScope, config: EdhocResponderConfig, port: Int): CoapServer {
    val contexts = InMemoryContextRepository() // one context per handshake: bound it in production
    val edhoc = EdhocServer(config, crypto, contexts)
    val security = CombinedRequestServer(edhoc, OscoreServer(contexts))
    return CoapServer(KtorUdpTransport.bind("127.0.0.1", port), scope, CoapServerConfig(security = security)) {
        resource(".well-known") { add(EdhocResource(edhoc)) } // POST /.well-known/edhoc, public
        resource("lights") {
            get {
                val peer = (securityIdentity as EdhocIdentity).peerCredential // who EDHOC authenticated
                CoapResponse.content("on for ${peer.claimsSet?.subject}")
            }
        }
    }
}

suspend fun edhocClient(scope: CoroutineScope, config: EdhocInitiatorConfig, port: Int): String {
    val security = PeerSecurity()
    val client = CoapClient(KtorUdpTransport.bind(), scope, CoapClientConfig { this.security = security })
    try {
        val server = CoapUri.parse("coap://127.0.0.1:$port")
        // message_1 and message_2 here; message_3 rides on the first OSCORE request (RFC 9668).
        val (_, protection) = EdhocClientHandshake.over(client, server, config, crypto).runCombined()
        security.install(client, server, protection)
        return client.get("coap://127.0.0.1:$port/lights").payloadAsText()
    } finally {
        client.close()
    }
}
