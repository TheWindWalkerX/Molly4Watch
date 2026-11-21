/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.protocol

import kotlinx.serialization.Serializable

/**
 * Everything the bridge needs from Molly. :app implements this on top of ThreadTable, MessageTable,
 * MessageSender and MarkReadReceiver. The relay app (:wear-relay) reaches that implementation over
 * :wear-ipc, so the bridge never touches Molly internals and its logic is testable with a fake.
 * Lives here (pure JVM, no Google code) so Molly can implement it without depending on :wear-bridge.
 *
 * All methods except [isLocked] are only called while unlocked, and are called off the main thread.
 */
interface MollyGateway {
  /** Molly's passphrase lock (KeyCachingService.isLocked()). While true, the database must not be read. */
  fun isLocked(): Boolean

  fun loadSnapshot(now: Long): Snapshot

  /** Returns null if the thread doesn't exist. */
  fun loadHistory(threadId: Long, offset: Int, limit: Int): HistoryPage?

  /** [quoteMessageId]: send as a quoted reply to that message (UNKNOWN_MESSAGE if it isn't in the thread). */
  fun sendText(threadId: Long, body: String, quoteMessageId: Long? = null): Outcome

  fun react(threadId: Long, messageId: Long, emoji: String): Outcome

  /** Removes this user's own reaction, if any. Removing a reaction that doesn't exist is OK. */
  fun removeReaction(threadId: Long, messageId: Long): Outcome

  fun markRead(threadId: Long): Outcome

  @Serializable
  data class HistoryPage(val messages: List<WearMessage>, val hasMore: Boolean)

  enum class Outcome { OK, UNKNOWN_THREAD, UNKNOWN_MESSAGE }
}
