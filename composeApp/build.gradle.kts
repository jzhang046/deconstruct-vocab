import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// API keys for translation providers, read generically from local.properties
// ("<provider>.apiKey", gitignored) or "<PROVIDER>_API_KEY" env vars. No
// provider is privileged here or anywhere below this file — provider identity
// only matters at the network factory (createTranslationService()). Add a
// provider id to this list to bundle another one; no other change needed.
val apiKeyProviders = listOf("qwen", "gemini")

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

val apiKeys: Map<String, String> = apiKeyProviders.associateWith { provider ->
    localProperties.getProperty("$provider.apiKey") ?: System.getenv("${provider.uppercase()}_API_KEY") ?: ""
}

// Release signing — read from keystore.properties (gitignored). Absent => release
// builds are unsigned (debug/CI still work).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile") != null

// Auto-increment versionCode from the git commit count — monotonic, no manual
// bumps. Release builds need full git history (not a shallow clone); falls back
// to 1 if git is unavailable. Avoid squashing already-released history, or the
// count can regress below a code Play has already accepted.
val gitCommitCount: Int = run {
    try {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            workingDir = rootProject.projectDir
        }.standardOutput.asText.get().trim().toIntOrNull() ?: 1
    } catch (e: Exception) {
        1
    }
}

// None of Android/iOS/Desktop get treated differently here: all three get
// their bundled keys the same way, via a generated Map<String, String> source
// file. (Android *could* use BuildConfig instead, but that would special-case
// it back into a per-platform, per-provider mess — this keeps all three
// symmetric.) Web is intentionally excluded — a key in the JS bundle is
// readable by anyone.
fun registerGenerateSecretsTask(taskName: String, constName: String, outputDirName: String) =
    tasks.register(taskName) {
        val outDir = layout.buildDirectory.dir("generated/$outputDirName/kotlin")
        val keys = apiKeys
        inputs.property("apiKeys", keys)
        outputs.dir(outDir)
        doLast {
            val pkgDir = outDir.get().asFile.resolve("com/mettyoung/deconstructchinese/config")
            pkgDir.mkdirs()
            val entries = keys.entries.joinToString(",\n    ") { (provider, key) -> "\"$provider\" to \"$key\"" }
            pkgDir.resolve("SecretsGenerated.kt").writeText(
                """
                package com.mettyoung.deconstructchinese.config

                internal val $constName: Map<String, String> = mapOf(
                    $entries
                )
                """.trimIndent() + "\n"
            )
        }
    }

val generateAndroidSecrets = registerGenerateSecretsTask("generateAndroidSecrets", "androidDefaultApiKeys", "androidSecrets")
val generateIosSecrets = registerGenerateSecretsTask("generateIosSecrets", "iosDefaultApiKeys", "iosSecrets")
val generateDesktopSecrets = registerGenerateSecretsTask("generateDesktopSecrets", "desktopDefaultApiKeys", "desktopSecrets")

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }
    
    // Default hierarchy template wires iosMain -> ios{Arm64,SimulatorArm64}Main
    // and a webMain grouping js + wasmJs, matching the src/iosMain and src/webMain dirs.
    applyDefaultHierarchyTemplate()

    sourceSets {
        androidMain {
            kotlin.srcDir(generateAndroidSecrets)
            dependencies {
                implementation(libs.compose.uiToolingPreview)
                implementation(libs.androidx.activity.compose)
                implementation(libs.ktor.client.okhttp)
            }
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)

            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.serialization)
        }
        
        iosMain {
            kotlin.srcDir(generateIosSecrets)
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }

        val desktopMain by getting {
            kotlin.srcDir(generateDesktopSecrets)
            dependencies {
                implementation(compose.desktop.currentOs)
                // OkHttp (not the Java engine) — same engine as Android, which
                // streams SSE incrementally so the two-phase stage-1 flow completes.
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "com.mettyoung.deconstructchinese"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.mettyoung.deconstructchinese"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = gitCommitCount
        versionName = "1.0.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Bundle native debug symbols (Compose/Skia .so libs) into the AAB so
            // Play can symbolicate native crashes — clears the upload warning.
            ndk {
                debugSymbolLevel = "FULL"
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "com.mettyoung.deconstructchinese.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "DeconstructChinese"
            packageVersion = "1.0.1"
        }
    }
}
