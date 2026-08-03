plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :domain is pure Kotlin JVM. Zero Android imports — enforced by the
// NoAndroidImportsTest in the test sources. Runs on the JDK 21 from the
// dev shell but emits Java 17 bytecode so Android modules can consume it.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
