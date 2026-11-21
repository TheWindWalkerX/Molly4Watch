/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

// Local IPC between Molly (:app) and the relay app (:wear-relay). No Google code here: Molly depends
// on this module, and Molly stays free of Play services (decision D4).

plugins {
  id("signal-library")
}

android {
  namespace = "im.molly.wear.ipc"

  buildFeatures {
    aidl = true
  }
}

dependencies {
  api(project(":wear-protocol"))

  testImplementation(testLibs.junit.junit)
  testImplementation(testLibs.assertk)
}
