// Applied to the root project: one coverage report and one coverage rule for the whole library.
//
//   ./gradlew koverHtmlReport   -> build/reports/kover/html/index.html
//   ./gradlew koverVerify       -> fails when line coverage drops below the bound

plugins {
    id("org.jetbrains.kotlinx.kover")
}

// Every module that applies Kover: each library module through its tier plugin, and kcoap-swift-smoke.
// kcoap-bom has no code and no Kover, so it stays out.
val aggregator = project
subprojects {
    val module = this
    pluginManager.withPlugin("org.jetbrains.kotlinx.kover") {
        aggregator.dependencies.add("kover", module)
    }
}

kover {
    reports {
        verify {
            rule("Line coverage of the whole library") {
                minBound(90)
            }
        }
    }
}
