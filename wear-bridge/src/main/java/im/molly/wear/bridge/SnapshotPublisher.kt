/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.bridge

import im.molly.wear.protocol.MollyGateway
import im.molly.wear.protocol.Snapshot
import im.molly.wear.protocol.WearCodec
import im.molly.wear.protocol.WearProtocolException
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Publishes the current [Snapshot] to the watch. Requests are coalesced: Molly calls
 * updateNotification very often, and a burst of calls produces one publish (plus one more if a
 * request arrives while a publish is already running).
 */
class SnapshotPublisher(
  private val gateway: MollyGateway,
  private val transport: WearTransport,
  private val executor: Executor,
  private val clock: () -> Long = System::currentTimeMillis
) {
  private val pending = AtomicBoolean(false)

  fun request() {
    if (pending.compareAndSet(false, true)) {
      executor.execute {
        pending.set(false)
        try {
          publishNow()
        } catch (e: Exception) {
          Log.w(TAG, "Snapshot publish failed", e)
        }
      }
    }
  }

  internal fun publishNow() {
    val now = clock()
    if (gateway.isLocked()) {
      // Always publish the lock, even if no watch is reachable right now: it replaces the last
      // unlocked snapshot so message content doesn't linger in the Data Layer.
      transport.putSnapshot(WearCodec.encodeSnapshot(Snapshot.locked(now)))
      return
    }
    if (!transport.hasWatch()) {
      // No watch app installed or seen: don't put message content into the Data Layer at all.
      Log.i(TAG, "No watch with the Molly app found; not publishing")
      return
    }
    transport.putSnapshot(encodeWithinLimit(gateway.loadSnapshot(now)))
  }

  /** The gateway already bounds the snapshot. As a backstop, drop the oldest threads until it fits. */
  private fun encodeWithinLimit(snapshot: Snapshot): ByteArray {
    var current = snapshot
    while (true) {
      try {
        return WearCodec.encodeSnapshot(current)
      } catch (e: WearProtocolException) {
        if (current.threads.isEmpty()) throw e
        current = current.copy(threads = current.threads.dropLast(1))
      }
    }
  }

  companion object {
    private const val TAG = "MollyWear/SnapshotPublisher"
  }
}
