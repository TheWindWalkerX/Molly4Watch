/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

// Phone side of the Wear companion. Owns the Data Layer (Google Play services) dependency so :app
// doesn't: :app only implements [im.molly.wear.bridge.MollyGateway] and calls WearBridge.

plugins {
  id("signal-library")
}

android {
  namespace = "im.molly.wear.bridge"

  defaultConfig {
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // android.util.Log calls become no-ops in JVM unit tests.
  testOptions.unitTests.isReturnDefaultValues = true
}

// Deliberately no dependency on Molly modules: the bridge talks to Molly only through MollyGateway.
dependencies {
  api(project(":wear-protocol"))
  implementation(wearLibs.play.services.wearable)

  testImplementation(testLibs.assertk)

  androidTestImplementation(testLibs.androidx.test.runner)
  androidTestImplementation(testLibs.androidx.test.ext.junit)
  androidTestImplementation(testLibs.assertk)
}
