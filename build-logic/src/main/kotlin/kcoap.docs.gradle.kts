// Applied to the root project: one API reference for the whole published library.
//
//   ./gradlew dokkaGenerate   -> build/dokka/html/index.html

plugins {
    id("org.jetbrains.dokka")
}

// Every published module, that is every module with kcoap.publishing; a module marked kcoap.unpublished
// ships no artifact, so it gets no reference either.
val aggregator = project
subprojects {
    val module = this
    pluginManager.withPlugin("kcoap.publishing") {
        aggregator.dependencies.add("dokka", module)
    }
}

dokka {
    moduleName.set("kcoap")
}
