/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.bridge

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import im.molly.wear.protocol.WearPaths

/**
 * Receives commands from the watch. Play services only delivers Data Layer messages from apps with
 * the same applicationId and signing key, so other apps can't send commands here.
 * onMessageReceived runs on a background thread.
 */
class WearBridgeListenerService : WearableListenerService() {
  override fun onMessageReceived(messageEvent: MessageEvent) {
    if (messageEvent.path != WearPaths.COMMAND) return
    WearBridge.onCommand(messageEvent.sourceNodeId, messageEvent.data)
  }
}
