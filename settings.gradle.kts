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
    }
}

rootProject.name = "kcoap"

include(
    // Crypto seam: interfaces + pure-Kotlin AES-CCM and HKDF (no platform dependencies).
    ":kcoap-crypto",
    // Default backend on cryptography-kotlin (JDK on JVM/Android, CryptoKit + CommonCrypto on Apple).
    ":kcoap-crypto-default",
    // Deterministic CBOR (RFC 8949 §4.2) and CBOR sequences (RFC 8742).
    ":kcoap-cbor",
    // COSE structures used by OSCORE and EDHOC (RFC 9052/9053, RFC 9360).
    ":kcoap-cose",
    // Transport interfaces: datagram, byte stream, message stream; endpoints, peer identities, schemes.
    ":kcoap-transport",
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
