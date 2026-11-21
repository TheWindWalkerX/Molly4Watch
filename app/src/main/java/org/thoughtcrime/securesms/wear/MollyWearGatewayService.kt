/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.wear // MOLLY-WEAR (new file)

import android.app.Service
import android.content.Intent
import android.os.IBinder
import im.molly.wear.ipc.IMollyWearGateway
import im.molly.wear.ipc.WearIpc
import im.molly.wear.protocol.MollyGateway.Outcome
import im.molly.wear.protocol.Snapshot
import org.signal.core.util.logging.Log

/**
 * Exposes [MollyWearGateway] to the relay app (:wear-relay). Binding requires the signature-level
 * [WearIpc.PERMISSION], enforced by the manifest, so only apps signed with Molly's key get in.
 *
 * Molly checks its own lock here too (defense in depth): while locked it returns a content-free
 * snapshot and refuses every other call, whatever the relay does.
 */
class MollyWearGatewayService : Service() {

  private val binder = object : IMollyWearGateway.Stub() {
    private val gateway by lazy { MollyWearGateway(applicationContext) }

    override fun ipcVersion(): Int = WearIpc.IPC_VERSION

    override fun isLocked(): Boolean = gateway.isLocked()

    override fun loadSnapshot(now: Long): ByteArray = guarded("loadSnapshot") {
      WearIpc.encodeSnapshot(if (gateway.isLocked()) Snapshot.locked(now) else gateway.loadSnapshot(now))
    }

    override fun loadHistory(threadId: Long, offset: Int, limit: Int): ByteArray? = guarded("loadHistory") {
      if (gateway.isLocked()) null else gateway.loadHistory(threadId, offset, limit)?.let(WearIpc::encodeHistory)
    }

    override fun sendText(threadId: Long, body: String, quoteMessageId: Long): Int =
      outcome("sendText") { gateway.sendText(threadId, body, quoteMessageId.takeIf { it > 0 }) }

    override fun react(threadId: Long, messageId: Long, emoji: String): Int = outcome("react") { gateway.react(threadId, messageId, emoji) }

    override fun removeReaction(threadId: Long, messageId: Long): Int = outcome("removeReaction") { gateway.removeReaction(threadId, messageId) }

    override fun markRead(threadId: Long): Int = outcome("markRead") { gateway.markRead(threadId) }

    private fun outcome(name: String, block: () -> Outcome): Int = guarded(name) {
      check(!gateway.isLocked()) { "Molly is locked" }
      WearIpc.outcomeToInt(block())
    }

    /** Exceptions cross the binder as a generic failure; log the real cause on Molly's side. */
    private fun <T> guarded(name: String, block: () -> T): T {
      return try {
        block()
      } catch (e: Exception) {
        Log.w(TAG, "$name failed", e)
        throw IllegalStateException("$name failed")
      }
    }
  }

  override fun onBind(intent: Intent): IBinder? = if (intent.action == WearIpc.ACTION_BIND_GATEWAY) binder else null

  companion object {
    private val TAG = Log.tag(MollyWearGatewayService::class.java)
  }
}
