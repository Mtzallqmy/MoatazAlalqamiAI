import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("KaiDatabase") {
            packageName.set("com.inspiredandroid.kai.db")
            verifyMigrations.set(true)
        }
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(project.layout.projectDirectory.file("compose_stability.conf"))
}

kotlin {
    android {
        namespace = "com.inspiredandroid.kai.shared"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        androidResources {
            enable = true
        }
        withHostTest {}
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/src/commonMain/kotlin"))
            dependencies {
                implementation(libs.compose.material3)
                implementation(libs.compose.material.icons.core)
                implementation(libs.compose.material.icons.extended)
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.components.uiToolingPreview)

                implementation(libs.androidx.navigation.compose)
                implementation(libs.androidx.lifecycle.viewmodel)
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.androidx.lifecycle.viewmodel.compose)

                implementation(libs.kotlinx.collections.immutable)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)

                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.auth)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.logging)

                implementation(libs.tts)
                implementation(libs.tts.compose)

                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)
                implementation(libs.koin.core)

                implementation(libs.multiplatform.settings)
                implementation(libs.multiplatform.settings.no.arg)

                implementation(libs.filekit.core)
                implementation(libs.filekit.compose)

                implementation(libs.coil.compose)
                implementation(libs.coil.svg)
                implementation(libs.coil.network.ktor3)

                implementation(libs.reorderable)

                implementation(libs.sqldelight.runtime)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
                implementation(libs.multiplatform.settings.test)
            }
        }
        androidMain {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.lifecycle.process)
                implementation(libs.spght.encryptedprefs)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.koin.android)
                implementation(libs.material)
                implementation(libs.bouncycastle.provider)
                implementation(libs.litert.lm)
                implementation(libs.sqldelight.android.driver)
                implementation(libs.xz)
            }
        }
    }
}

// BouncyCastle is a cryptographically signed JCE provider jar. ProGuard rewrites
// it and strips the META-INF signatures, causing "SHA-256 digest error" at
// runtime. After ProGuard finishes, replace the processed jar with the original.
afterEvaluate {
    tasks.matching { it.name == "proguardReleaseJars" }.configureEach {
        doLast {
            val proguardDir =
                layout.buildDirectory
                    .dir("compose/tmp/main-release/proguard")
                    .get()
                    .asFile
            val processedJar = proguardDir.listFiles()?.find { it.name.startsWith("bcprov") } ?: return@doLast
            val originalJar =
                configurations["runtimeClasspath"]
                    .resolve()
                    .find { it.name.startsWith("bcprov") } ?: return@doLast
            originalJar.copyTo(processedJar, overwrite = true)
            logger.lifecycle("Restored original signed BouncyCastle jar: ${processedJar.name}")
        }
    }
}

class VersionGeneratorPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.afterEvaluate {
            val appVersion = libs.versions.appVersion.get()

            // Generate Kotlin version file
            val versionFile =
                layout.buildDirectory
                    .file("generated/src/commonMain/kotlin/com/inspiredandroid/kai/Version.kt")
                    .get()
                    .asFile
            versionFile.parentFile?.mkdirs()
            versionFile.writeText(
                """
                package com.inspiredandroid.kai

                object Version {
                    const val appVersion = "$appVersion"
                }
                """.trimIndent(),
            )
        }
    }
}

apply<VersionGeneratorPlugin>()
