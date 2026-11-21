/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

// Wear OS companion app. Talks to the phone relay app (:wear-relay, decision D4) over the Data Layer.

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// The Data Layer only connects apps with the SAME applicationId and signing key. The phone side is the
// relay app (:wear-relay), whose id is Molly's package + ".wear", so read Molly's package ID exactly the
// way :app does (app/gradle.properties, overridable by CI_PACKAGE_ID).
val ciEnabled = project.hasProperty("CI")
val appProperties = Properties().apply { rootProject.file("app/gradle.properties").inputStream().use { load(it) } }
fun getCiEnv(name: String) = if (ciEnabled) System.getenv(name).takeUnless { it.isNullOrBlank() } else null
val basePackageId = getCiEnv("CI_PACKAGE_ID") ?: appProperties.getProperty("basePackageId")

android {
  namespace = "im.molly.wear"
  buildToolsVersion = libs.versions.buildTools.get()
  compileSdkVersion(libs.versions.compileSdk.get())

  defaultConfig {
    applicationId = "$basePackageId.wear" // same as :wear-relay (D4); the Data Layer needs matching ids
    minSdk = wearLibs.versions.wear.minSdk.get().toInt()
    targetSdk = libs.versions.targetSdk.get().toInt()
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Same keystore env vars as :app, so release builds of both are signed with the same key.
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
      isShrinkResources = true
      signingConfig = signingConfigs.findByName("ci")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
    // Release performance (R8, not debuggable, so Compose's baseline profiles apply) but signed with the
    // debug key, so it pairs with a debug relay. For testing on real watches: debug Compose is very janky.
    create("optimized") {
      initWith(getByName("release"))
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += listOf("release")
    }
  }

  buildFeatures {
    compose = true
  }

  compileOptions {
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
  implementation(project(":wear-protocol"))

  implementation(wearLibs.play.services.wearable)
  implementation(wearLibs.wear.input)
  implementation(libs.kotlinx.coroutines.play.services)

  implementation(platform(libs.androidx.compose.bom))
  implementation(wearLibs.wear.compose.material3)
  implementation(wearLibs.wear.compose.foundation)
  implementation(wearLibs.wear.compose.navigation)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.compose.ui.tooling.preview)
  debugImplementation(wearLibs.wear.compose.ui.tooling)

  testImplementation(testLibs.junit.junit)
  testImplementation(testLibs.assertk)
  testImplementation(testLibs.kotlinx.coroutines.test)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  // Newer than Molly's testLibs: needed to run on Android 17 watches (see wear-libs.versions.toml).
  androidTestImplementation(wearLibs.androidx.test.runner)
  androidTestImplementation(wearLibs.androidx.test.core)
  androidTestImplementation(wearLibs.androidx.test.ext.junit)
  androidTestImplementation(wearLibs.espresso.core)
}
