// Applied to the root project: one API reference for the whole library.
//
//   ./gradlew dokkaGenerate   -> build/dokka/html/index.html

plugins {
    id("org.jetbrains.dokka")
}

dependencies {
    subprojects.forEach { add("dokka", it) }
}

dokka {
    moduleName.set("kcoap")
}
