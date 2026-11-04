// Applied to the root project: one coverage report and one coverage rule for the whole library.
//
//   ./gradlew koverHtmlReport   -> build/reports/kover/html/index.html
//   ./gradlew koverVerify       -> fails when line coverage drops below the bound

plugins {
    id("org.jetbrains.kotlinx.kover")
}

dependencies {
    subprojects.forEach { add("kover", it) }
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
