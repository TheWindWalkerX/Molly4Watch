/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

// Shared phone <-> watch protocol: Data Layer paths, DTOs and a versioned codec.
// Pure JVM so it is unit-testable without a device and usable from both :wear-bridge and :wear.

plugins {
  id("java-library")
  id("org.jetbrains.kotlin.jvm")
  alias(libs.plugins.kotlinx.serialization)
}

java {
  sourceCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
  targetCompatibility = JavaVersion.toVersion(libs.versions.javaVersion.get())
}

kotlin {
  jvmToolchain {
    languageVersion = JavaLanguageVersion.of(libs.versions.kotlinJvmTarget.get())
  }
}

dependencies {
  api(libs.kotlinx.serialization.json)

  testImplementation(testLibs.junit.junit)
  testImplementation(testLibs.assertk)
}
