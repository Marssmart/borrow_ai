plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.cocoapods)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val sherpaOnnxVersion = "1.13.8"
val sherpaOnnxAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")

// The AAR is a 50 MB binary, so it is fetched from the pinned upstream release instead of committed.
val downloadSherpaOnnxAar by tasks.registering {
    outputs.file(sherpaOnnxAar)
    doLast {
        val target = sherpaOnnxAar.asFile
        if (!target.exists()) {
            target.parentFile.mkdirs()
            val partial = target.resolveSibling("${target.name}.part")
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
            partial.delete()
            uri(url).toURL().openStream().use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            check(partial.renameTo(target)) { "Failed to move $partial to $target" }
        }
    }
}

tasks.matching { it.name.startsWith("preBuild") }.configureEach {
    dependsOn(downloadSherpaOnnxAar)
}

kotlin {
    androidTarget()

    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    cocoapods {
        summary = "BorrowAI shared application"
        homepage = "https://github.com/Marssmart/borrow_ai"
        podfile = project.file("../iosApp/Podfile")
        version = rootProject.version.toString()
        ios.deploymentTarget = "15.0"
        pod("CouchbaseLite", version = "3.2.4", linkOnly = true)
        framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation(compose.ui)
            implementation(libs.arrow.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.okio)
            implementation(libs.kotbase.couchbase.lite)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.okio.fakefilesystem)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.ktor.client.okhttp)
            implementation(files(sherpaOnnxAar).builtBy(downloadSherpaOnnxAar))
            implementation(libs.litertlm.android)
        }

        // Kotbase needs the native Couchbase Lite library, so its CRUD tests run on a device.
        androidInstrumentedTest.dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.test.runner)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

android {
    namespace = "org.deer.borrowai"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.deer.borrowai"
        minSdk = 24
        targetSdk = 36
        versionCode = 51
        versionName = rootProject.version.toString()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        // Unit tests run on the JVM; android.util.Log is a no-op stub instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
}
