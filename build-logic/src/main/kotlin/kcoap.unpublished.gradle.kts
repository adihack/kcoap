// A module that is built, tested and checked like every other one, but not published: no Maven
// publication, no API reference and no ABI dumps. For kcoap-dtls until its engine exists (its SPIs would
// otherwise ship with no implementation), and later for kcoap-swift-smoke.
//
// Apply it before the tier plugin, which publishes every module that is not marked unpublished by then:
//
//   plugins {
//       id("kcoap.unpublished")
//       id("kcoap.kmp-library")
//   }

pluginManager.withPlugin("kcoap.publishing") {
    throw GradleException(
        "$path applies kcoap.unpublished after its kcoap.kmp-* plugin, which has already published it. " +
            "Put id(\"kcoap.unpublished\") first in the plugins block.",
    )
}
