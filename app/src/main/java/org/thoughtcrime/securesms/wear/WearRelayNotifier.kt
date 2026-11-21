/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.wear // MOLLY-WEAR (new file)

import android.content.Context
import android.content.Intent
import im.molly.wear.ipc.WearIpc

/**
 * Tells the relay app that something the watch shows may have changed: notifications, lock state or
 * thread state. The broadcast carries no data (the relay asks [MollyWearGatewayService]) and only
 * reaches a same-signed relay, because it requires [WearIpc.PERMISSION]. No relay installed: a no-op.
 */
object WearRelayNotifier {
  @JvmStatic
  fun onStateChanged(context: Context) {
    val intent = Intent(WearIpc.ACTION_STATE_CHANGED)
      .setPackage(context.packageName + WearIpc.RELAY_PACKAGE_SUFFIX)
      // The relay may never have been opened; explicit broadcasts may wake stopped packages.
      .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
    context.sendBroadcast(intent, WearIpc.PERMISSION)
  }
}
