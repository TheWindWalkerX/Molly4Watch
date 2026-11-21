/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.bridge

import im.molly.wear.protocol.MollyGateway
import android.content.Context
import java.util.concurrent.Executors

/**
 * Entry point used by Molly. Molly's side is two calls:
 *  - [install] once from Application.onCreate, with Molly's [MollyGateway] implementation;
 *  - [onStateChanged] from places where notifications, lock state or thread state change.
 * Both are safe no-ops when Play services or a watch are missing.
 */
object WearBridge {
  @Volatile private var handler: CommandHandler? = null
  @Volatile private var publisher: SnapshotPublisher? = null
  @Volatile private var transport: WearTransport? = null

  @JvmStatic
  @Synchronized
  fun install(context: Context, gateway: MollyGateway) {
    if (publisher != null) return
    install(gateway, DataLayerTransport(context))
  }

  /** Test seam: install with any transport. */
  @Synchronized
  internal fun install(gateway: MollyGateway, transport: WearTransport) {
    val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "MollyWearBridge").apply { isDaemon = true } }
    val newPublisher = SnapshotPublisher(gateway, transport, executor)
    this.transport = transport
    this.publisher = newPublisher
    this.handler = CommandHandler(gateway, newPublisher::request)
  }

  /** Republish the snapshot soon. Cheap; call it as often as you like, from any thread. */
  @JvmStatic
  fun onStateChanged() {
    publisher?.request()
  }

  /** Called by [WearBridgeListenerService] on a background thread. */
  internal fun onCommand(sourceNodeId: String, bytes: ByteArray) {
    val handler = handler ?: return
    transport?.sendResult(sourceNodeId, handler.handleToBytes(bytes))
  }

  @Synchronized
  internal fun resetForTests() {
    handler = null
    publisher = null
    transport = null
  }
}
