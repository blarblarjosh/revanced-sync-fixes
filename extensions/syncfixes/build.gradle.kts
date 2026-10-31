extension {
    name = "extensions/syncfixes.rve"
}

android {
    namespace = "app.revanced.extension"

    // Sync runs on minSdk 21.
    defaultConfig {
        minSdk = 21
    }

    lint {
        checkOnly += setOf("NewApi", "InlinedApi")
        abortOnError = true
    }
}

// The extension plugin applies Kotlin, which puts kotlin-stdlib on the runtime classpath. This extension is pure
// Java, so keep it (and its 1000+ classes) out of the bundle.
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
