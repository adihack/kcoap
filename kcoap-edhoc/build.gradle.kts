plugins {
    id("kcoap.kmp-library")
    id("kcoap.core")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-common"))
            api(project(":kcoap-cbor"))
            api(project(":kcoap-cose"))
            api(project(":kcoap-crypto"))
        }
        commonTest.dependencies {
            implementation(project(":kcoap-testkit"))
            // The RFC 9529 traces need real X25519, Ed25519, P-256 and AES; post-quantum uses a test double.
            implementation(project(":kcoap-crypto-default"))
        }
        jvmTest.dependencies {
            // Real ML-KEM and ML-DSA for the post-quantum suites, on our side of the libedhoc interop too.
            implementation(project(":kcoap-crypto-bc"))
            // Expanded ML-DSA private keys, the encoding libedhoc (liboqs) imports.
            implementation(libs.bouncycastle.prov)
        }
    }
}

kover {
    reports {
        verify {
            rule("Line coverage of kcoap-edhoc") {
                minBound(95)
            }
        }
    }
}
