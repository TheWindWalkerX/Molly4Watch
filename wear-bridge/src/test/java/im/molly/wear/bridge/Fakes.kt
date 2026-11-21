/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.bridge

import im.molly.wear.protocol.MollyGateway
import im.molly.wear.protocol.MollyGateway.HistoryPage
import im.molly.wear.protocol.MollyGateway.Outcome
import im.molly.wear.protocol.Snapshot
import im.molly.wear.protocol.ThreadSummary
import im.molly.wear.protocol.WearMessage

/** Records every call so tests can assert exactly what reached Molly. */
class FakeGateway : MollyGateway {
  var locked = false
  var threads = mutableMapOf(7L to listOf(1L, 2L, 3L)) // threadId -> messageIds
  var throwOnSend: Exception? = null
  var historyFactory: (Long, Int, Int) -> List<WearMessage> = { threadId, offset, limit ->
    (offset until minOf(offset + limit, 3)).map { WearMessage(messageId = it + 1L, threadId = threadId, isOutgoing = false, body = "m$it", sentAt = it.toLong()) }
  }
  val calls = mutableListOf<String>()

  override fun isLocked() = locked

  override fun loadSnapshot(now: Long): Snapshot {
    calls += "loadSnapshot"
    return Snapshot(
      generatedAt = now,
      locked = false,
      unreadThreadCount = 1,
      threads = threads.keys.map { ThreadSummary(it, "Thread $it", false, 1, now) }
    )
  }

  override fun loadHistory(threadId: Long, offset: Int, limit: Int): HistoryPage? {
    calls += "loadHistory($threadId,$offset,$limit)"
    if (threadId !in threads) return null
    val messages = historyFactory(threadId, offset, limit)
    return HistoryPage(messages, hasMore = offset + messages.size < (threads[threadId]?.size ?: 0))
  }

  override fun sendText(threadId: Long, body: String, quoteMessageId: Long?): Outcome {
    calls += if (quoteMessageId == null) "sendText($threadId,$body)" else "sendText($threadId,$body,quote=$quoteMessageId)"
    throwOnSend?.let { throw it }
    return if (threadId in threads) Outcome.OK else Outcome.UNKNOWN_THREAD
  }

  override fun react(threadId: Long, messageId: Long, emoji: String): Outcome {
    calls += "react($threadId,$messageId,$emoji)"
    return messageOutcome(threadId, messageId)
  }

  override fun removeReaction(threadId: Long, messageId: Long): Outcome {
    calls += "removeReaction($threadId,$messageId)"
    return messageOutcome(threadId, messageId)
  }

  override fun markRead(threadId: Long): Outcome {
    calls += "markRead($threadId)"
    return if (threadId in threads) Outcome.OK else Outcome.UNKNOWN_THREAD
  }

  private fun messageOutcome(threadId: Long, messageId: Long): Outcome = when {
    threadId !in threads -> Outcome.UNKNOWN_THREAD
    messageId !in threads.getValue(threadId) -> Outcome.UNKNOWN_MESSAGE
    else -> Outcome.OK
  }
}

class FakeTransport(var watchPresent: Boolean = true) : WearTransport {
  val snapshots = mutableListOf<ByteArray>()
  val results = mutableListOf<Pair<String, ByteArray>>()
  override fun hasWatch() = watchPresent
  override fun putSnapshot(bytes: ByteArray) {
    snapshots += bytes
  }
  override fun sendResult(nodeId: String, bytes: ByteArray) {
    results += nodeId to bytes
  }
}
