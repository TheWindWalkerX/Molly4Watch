/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.relay

import im.molly.wear.ipc.IMollyWearGateway
import im.molly.wear.ipc.WearIpc
import im.molly.wear.protocol.MollyGateway
import im.molly.wear.protocol.MollyGateway.HistoryPage
import im.molly.wear.protocol.MollyGateway.Outcome
import im.molly.wear.protocol.Snapshot
import im.molly.wear.protocol.WearTheme

/**
 * [MollyGateway] backed by Molly's gateway service over :wear-ipc.
 *
 * Privacy rule: if Molly can't be reached, or speaks another IPC version, Molly counts as LOCKED, so the
 * bridge publishes a content-free snapshot instead of stale data. Data calls then throw, and the bridge
 * reports INTERNAL to the watch.
 */
class IpcGateway(
  /** The phone's Material 3 palette, attached to every snapshot so the watch matches the phone. */
  private val theme: () -> WearTheme? = { null },
  private val connect: () -> IMollyWearGateway?
) : MollyGateway {

  override fun isLocked(): Boolean = try {
    call { it.isLocked }
  } catch (e: Exception) {
    true
  }

  override fun loadSnapshot(now: Long): Snapshot {
    val snapshot = WearIpc.decodeSnapshot(call { it.loadSnapshot(now) })
    return snapshot.copy(theme = runCatching { theme() }.getOrNull())
  }

  override fun loadHistory(threadId: Long, offset: Int, limit: Int): HistoryPage? =
    call { it.loadHistory(threadId, offset, limit) }?.let(WearIpc::decodeHistory)

  override fun sendText(threadId: Long, body: String, quoteMessageId: Long?): Outcome = outcome { it.sendText(threadId, body, quoteMessageId ?: 0L) }

  override fun react(threadId: Long, messageId: Long, emoji: String): Outcome = outcome { it.react(threadId, messageId, emoji) }

  override fun removeReaction(threadId: Long, messageId: Long): Outcome = outcome { it.removeReaction(threadId, messageId) }

  override fun markRead(threadId: Long): Outcome = outcome { it.markRead(threadId) }

  private fun outcome(block: (IMollyWearGateway) -> Int): Outcome = WearIpc.outcomeFromInt(call(block))

  private fun <T> call(block: (IMollyWearGateway) -> T): T {
    val service = connect() ?: throw MollyUnavailableException("Molly isn't reachable")
    if (service.ipcVersion() != WearIpc.IPC_VERSION) throw MollyUnavailableException("Molly speaks another IPC version")
    return block(service)
  }

  class MollyUnavailableException(message: String) : IllegalStateException(message)
}
