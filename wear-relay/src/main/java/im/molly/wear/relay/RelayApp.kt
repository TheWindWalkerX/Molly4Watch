/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.relay

import android.app.Application
import im.molly.wear.bridge.WearBridge

class RelayApp : Application() {
  lateinit var molly: MollyConnection
    private set

  override fun onCreate() {
    super.onCreate()
    molly = MollyConnection(this)
    WearBridge.install(this, IpcGateway(theme = { MaterialYouTheme.read(this) }, connect = molly::get))
  }
}
