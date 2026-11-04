plugins {
    id("kcoap.kmp-library")
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
            // The RFC 9529 traces need real X25519, Ed25519, P-256 and AES; post-quantum uses a test double.
            implementation(project(":kcoap-crypto-default"))
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
