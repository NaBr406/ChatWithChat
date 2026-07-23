@file:Suppress("UnstableApiUsage")

import com.android.build.api.dsl.ApplicationExtension
import java.security.MessageDigest
import org.gradle.kotlin.dsl.aboutLibraries
import org.gradle.kotlin.dsl.configure

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.android.hilt)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.auto.license)
    kotlin(libs.plugins.kotlin.serialization.get().pluginId).version(libs.versions.kotlin)
    alias(libs.plugins.objectbox)
}

val memoryTestBuildType = providers.gradleProperty("memoryTestBuildType").orElse("debug").get()
require(memoryTestBuildType == "debug" || memoryTestBuildType == "release") {
    "memoryTestBuildType must be either debug or release"
}
val defaultMemoryTestInstrumentationRunner = if (memoryTestBuildType == "release") {
    "cn.nabr.chatwithchat.data.memory.vector.Memory16KbReleaseCompatibilityInstrumentedTest"
} else {
    "androidx.test.runner.AndroidJUnitRunner"
}
val memoryTestInstrumentationRunner = providers.gradleProperty("memoryTestInstrumentationRunner")
    .orElse(defaultMemoryTestInstrumentationRunner)
    .get()
require(memoryTestInstrumentationRunner.isNotBlank()) {
    "memoryTestInstrumentationRunner must not be blank"
}
val supportedApkAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val apkAbi = providers.gradleProperty("chatWithChatApkAbi").orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
require(apkAbi == null || apkAbi in supportedApkAbis) {
    "chatWithChatApkAbi must be one of: ${supportedApkAbis.joinToString()}"
}

val productionMemoryModelDirectory = layout.projectDirectory.dir(
    "src/main/assets/memory-model/bge-small-zh-v1.5"
)
val productionMemoryModelArtifacts = listOf(
    Triple("model.onnx", 24_010_842L, "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc"),
    Triple("quantize_config.json", 674L, "2cc488b20fa05fe86aba2fdc2be44d24827e11e2b7c7a0753d1427da6797b46f"),
    Triple("MODEL_CARD.md", 27_670L, "c48a4eeea77f6b1d38b48ec1c5b8d4f86d5550cc43fa345a0db1b2ca1d082369"),
    Triple("config.json", 776L, "3853a7979202c348751b753e36f579c41d8da7d36af617d3d907e1fc9b441f2a"),
    Triple("tokenizer.json", 439_125L, "48cea5d44424912a6fd1ea647bf4fe50b55ab8b1e5879c3275f80e339e8fae26"),
    Triple("tokenizer_config.json", 367L, "e6f3b96db926a37d4039995fbf5ad17de158dfb8f6343d607e4dbaad18d75f5a"),
    Triple("vocab.txt", 109_540L, "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c")
)

val verifyProductionMemoryModelArtifacts by tasks.registering {
    group = "verification"
    description = "Verifies the pinned production memory model assets before a release build"
    inputs.files(productionMemoryModelArtifacts.map { artifact ->
        productionMemoryModelDirectory.file(artifact.first)
    })
    doLast {
        productionMemoryModelArtifacts.forEach { (relativePath, expectedSize, expectedSha256) ->
            val artifact = productionMemoryModelDirectory.file(relativePath).asFile
            check(artifact.isFile) {
                "Missing production memory model artifact: ${artifact.absolutePath}. " +
                    "Run tools/memory-model/provision-bge-small-zh-v1.5-production.ps1."
            }
            check(artifact.length() == expectedSize) {
                "Production memory model artifact has unexpected size: $relativePath " +
                    "(${artifact.length()} != $expectedSize)"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actualSha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            check(actualSha256 == expectedSha256) {
                "Production memory model artifact checksum mismatch: $relativePath " +
                    "($actualSha256 != $expectedSha256)"
            }
        }
    }
}

tasks.matching { task -> task.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyProductionMemoryModelArtifacts)
}

extensions.configure<ApplicationExtension> {
    namespace = "cn.nabr.chatwithchat"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.nabr.chatwithchat"
        minSdk = 31
        targetSdk = 36
        versionCode = 22
        versionName = "1.0.0"

        testInstrumentationRunner = memoryTestInstrumentationRunner
        vectorDrawables {
            useSupportLibrary = true
        }
        apkAbi?.let { abi ->
            ndk {
                abiFilters.add(abi)
            }
        }
    }

    androidResources {
        generateLocaleConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            vcsInfo.include = false
            testProguardFiles("proguard-android-test-rules.pro")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (
                memoryTestBuildType == "release" &&
                memoryTestInstrumentationRunner == "androidx.test.runner.AndroidJUnitRunner"
            ) {
                proguardFiles("proguard-memory-shadow-rules.pro")
            }
        }
    }
    testBuildType = memoryTestBuildType
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.viewmodel)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // SplashScreen
    implementation(libs.splashscreen)

    // DataStore
    implementation(libs.androidx.datastore)

    // Dependency Injection
    implementation(libs.hilt)
    implementation(libs.androidx.lifecycle.runtime.compose.android)
    ksp(libs.hilt.compiler)

    // Ktor
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.logging)
    implementation(libs.ktor.serialization)

    // Token counting
    implementation(libs.jtokkit)

    // Web extraction
    implementation(libs.jsoup)
    implementation(libs.okhttp)

    // License page UI
    implementation(libs.auto.license.core)
    implementation(libs.auto.license.ui)

    // Markdown
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)
    implementation(libs.androidsvg)

    // Navigation
    implementation(libs.hilt.navigation)
    implementation(libs.androidx.navigation)

    // Room
    implementation(libs.room)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Serialization
    implementation(libs.kotlin.serialization)

    // On-device embeddings
    implementation(libs.onnx.runtime.android)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

aboutLibraries {
    // Remove the "generated" timestamp to allow for reproducible builds
    export {
        excludeFields.add("generated")
    }
}
