/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import im.molly.wear.bridge.WearBridge
import im.molly.wear.ipc.WearIpc

/** Molly says something changed. The broadcast carries no data; the bridge asks Molly for a fresh snapshot. */
class StateChangedReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == WearIpc.ACTION_STATE_CHANGED) WearBridge.onStateChanged()
  }
}
