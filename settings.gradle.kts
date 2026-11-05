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

// Each module applies the convention plugin of its target tier (build-logic, docs/target-architecture.md
// §2.1): kcoap.kmp-library (UNIVERSAL: every target, browsers included), kcoap.kmp-jvm-native-library
// (SOCKETS: JVM and native) or kcoap.kmp-jvm-library (JVM_LIBRARY). Core modules (protocol, wire and SPI
// code) also apply kcoap.core, which keeps their main code in commonMain. Every module is published, with
// ABI dumps under <module>/api, except those that apply kcoap.unpublished.
include(
    // Core. Shared basics every module uses: logging, hex, constant-time comparison, the opt-in markers.
    ":kcoap-common",
    // Core. Crypto seam: interfaces + pure-Kotlin AES-CCM and HKDF (no platform dependencies).
    ":kcoap-crypto",
    // Adapter, UNIVERSAL. Default backend on cryptography-kotlin: JDK on JVM/Android, CryptoKit and
    // CommonCrypto on Apple, OpenSSL 3 on Linux, WebCrypto in browsers and Node.js.
    ":kcoap-crypto-default",
    // Adapter, JVM_LIBRARY. Post-quantum backend on Bouncy Castle: ML-KEM (FIPS 203) and ML-DSA (FIPS 204).
    ":kcoap-crypto-bc",
    // Core. Deterministic CBOR (RFC 8949 §4.2) and CBOR sequences (RFC 8742).
    ":kcoap-cbor",
    // Core. COSE structures used by OSCORE and EDHOC (RFC 9052/9053, RFC 9360).
    ":kcoap-cose",
    // Core. Transport interfaces: datagram, byte stream, message stream; endpoints, peer identities, schemes.
    ":kcoap-transport",
    // Support, UNIVERSAL. In-memory datagram network and connections with loss, duplication and
    // reordering, for tests of kcoap and its users.
    ":kcoap-testkit",
    // Adapter, SOCKETS. UDP and TCP transports on ktor-network (JVM, Apple, Linux; not the web).
    ":kcoap-transport-ktor",
    // Adapter, UNIVERSAL. WebSocket client transport on the ktor client: any ktor engine, browsers included.
    ":kcoap-transport-ktor-websocket",
    // Adapter, SOCKETS. WebSocket server transport on the ktor server: standalone on CIO, or mounted in a
    // ktor application.
    ":kcoap-transport-ktor-websocket-server",
    // Core, unpublished. DTLS 1.2 for coaps (RFC 6347, RFC 7925, RFC 9146): interfaces and registries only,
    // so it is not published until the engine exists.
    ":kcoap-dtls",
    // Core. CoAP message model, codec and exchange layer (RFC 7252, 7641, 7959, 8132, 9175, 8323).
    ":kcoap-coap",
    // Core. OSCORE (RFC 8613).
    ":kcoap-oscore",
    // Core. EDHOC (RFC 9528).
    ":kcoap-edhoc",
    // Core. EDHOC + OSCORE glue (RFC 9668, RFC 9528 Appendix A).
    ":kcoap-edhoc-oscore",
)
