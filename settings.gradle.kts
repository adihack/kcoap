pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        mavenCentral()
        // The js and wasmJs tests run on Node.js, which the Kotlin Gradle plugin downloads. It would add
        // these repositories to the project, but project repositories are ignored here.
        ivy("https://nodejs.org/dist") {
            name = "Node.js distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
    }
}

rootProject.name = "kcoap"

include(
    // Shared basics every module uses: logging, hex, constant-time comparison.
    ":kcoap-common",
    // Crypto seam: interfaces + pure-Kotlin AES-CCM and HKDF (no platform dependencies).
    ":kcoap-crypto",
    // Default backend on cryptography-kotlin (JDK on JVM/Android, CryptoKit + CommonCrypto on Apple).
    ":kcoap-crypto-default",
    // Post-quantum backend on Bouncy Castle (JVM/Android only): ML-KEM (FIPS 203) and ML-DSA (FIPS 204).
    ":kcoap-crypto-bc",
    // Deterministic CBOR (RFC 8949 §4.2) and CBOR sequences (RFC 8742).
    ":kcoap-cbor",
    // COSE structures used by OSCORE and EDHOC (RFC 9052/9053, RFC 9360).
    ":kcoap-cose",
    // Transport interfaces: datagram, byte stream, message stream; endpoints, peer identities, schemes.
    ":kcoap-transport",
    // In-memory datagram network with loss, duplication and reordering, for tests of kcoap and its users.
    ":kcoap-testkit",
    // UDP and TCP transports on ktor-network (JVM, Apple, Linux).
    ":kcoap-transport-ktor",
    // WebSocket client transport on the ktor client: any ktor engine, browsers included.
    ":kcoap-transport-ktor-websocket",
    // WebSocket server transport on the ktor server: standalone on CIO, or mounted in a ktor application.
    ":kcoap-transport-ktor-websocket-server",
    // DTLS 1.2 for coaps (RFC 6347, RFC 7925, RFC 9146). Interfaces and registries only for now.
    ":kcoap-dtls",
    // CoAP message model, codec and exchange layer (RFC 7252, 7641, 7959, 8132, 9175).
    ":kcoap-coap",
    // OSCORE (RFC 8613).
    ":kcoap-oscore",
    // EDHOC (RFC 9528).
    ":kcoap-edhoc",
    // EDHOC + OSCORE glue (RFC 9668, RFC 9528 Appendix A).
    ":kcoap-edhoc-oscore",
)
