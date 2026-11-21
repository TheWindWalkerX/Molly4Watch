/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

// Phone-side relay app (decision D4). It owns the Wear Data Layer (Play services, via :wear-bridge) so
// Molly doesn't have to, and reaches Molly's data through :wear-ipc. Shares the watch app's
// applicationId (Molly's + ".wear") and must be signed with Molly's key.

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
}

// Read Molly's package ID exactly as :app does (app/gradle.properties, overridable by CI_PACKAGE_ID).
val ciEnabled = project.hasProperty("CI")
val appProperties = Properties().apply { rootProject.file("app/gradle.properties").inputStream().use { load(it) } }
fun getCiEnv(name: String) = if (ciEnabled) System.getenv(name).takeUnless { it.isNullOrBlank() } else null
val mollyPackageId = getCiEnv("CI_PACKAGE_ID") ?: appProperties.getProperty("basePackageId")

android {
  namespace = "im.molly.wear.relay"
  buildToolsVersion = libs.versions.buildTools.get()
  compileSdkVersion(libs.versions.compileSdk.get())

  defaultConfig {
    applicationId = "$mollyPackageId.wear" // = Molly's package + WearIpc.RELAY_PACKAGE_SUFFIX
    minSdk = libs.versions.minSdk.get().toInt()
    targetSdk = libs.versions.targetSdk.get().toInt()
    versionCode = 1
    versionName = "0.1.0"
    manifestPlaceholders["mollyPackage"] = mollyPackageId
  }

  // Same keystore env vars as :app and :wear, so all three are signed with the same key.
  signingConfigs {
    System.getenv("CI_KEYSTORE_PATH")?.let { path ->
      create("ci") {
        storeFile = file(path)
        storePassword = System.getenv("CI_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("CI_KEYSTORE_ALIAS")
        keyPassword = System.getenv("CI_KEYSTORE_PASSWORD")
      }
    }
  }

  buildTypes {
    getByName("release") {
      isMinifyEnabled = true
      signingConfig = signingConfigs.findByName("ci")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
  }

  compileOptions {
    isCoreLibraryDesugaringEnabled = true // required by signal-library modules (:wear-bridge, :wear-ipc)
    sourceCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
  }

  testOptions {
    unitTests.isReturnDefaultValues = true
  }

  lint {
    checkReleaseBuilds = false
  }
}

kotlin {
  compilerOptions {
    jvmTarget = JvmTarget.fromTarget(libs.versions.kotlinJvmTarget.get())
  }
}

dependencies {
  coreLibraryDesugaring(libs.android.tools.desugar)

  implementation(project(":wear-bridge"))
  implementation(project(":wear-ipc"))

  testImplementation(testLibs.junit.junit)
  testImplementation(testLibs.assertk)
}
