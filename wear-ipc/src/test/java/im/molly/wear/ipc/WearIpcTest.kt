/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.ipc

import assertk.assertThat
import assertk.assertions.isEqualTo
import im.molly.wear.protocol.MollyGateway.HistoryPage
import im.molly.wear.protocol.MollyGateway.Outcome
import im.molly.wear.protocol.Snapshot
import im.molly.wear.protocol.ThreadSummary
import im.molly.wear.protocol.WearMessage
import im.molly.wear.protocol.WearReaction
import org.junit.Test

class WearIpcTest {
  private val message = WearMessage(
    messageId = 7, threadId = 3, isOutgoing = false, authorName = "Ana", body = "hi 👋",
    hasAttachment = true, sentAt = 1_000, reactions = listOf(WearReaction("❤️", 2, includesMe = true))
  )

  @Test
  fun `snapshot round trip`() {
    val snapshot = Snapshot(5, locked = false, unreadThreadCount = 1, threads = listOf(ThreadSummary(3, "Ana", false, 1, 1_000, listOf(message))))
    assertThat(WearIpc.decodeSnapshot(WearIpc.encodeSnapshot(snapshot))).isEqualTo(snapshot)
  }

  @Test
  fun `snapshot larger than the Data Layer limit still crosses IPC`() {
    val big = message.copy(body = "x".repeat(1000))
    val threads = (1L..20L).map { ThreadSummary(it, "T$it", false, 0, it, List(10) { big }) }
    val snapshot = Snapshot(5, locked = false, unreadThreadCount = 0, threads = threads)
    assertThat(WearIpc.decodeSnapshot(WearIpc.encodeSnapshot(snapshot))).isEqualTo(snapshot)
  }

  @Test
  fun `history round trip`() {
    val page = HistoryPage(listOf(message), hasMore = true)
    assertThat(WearIpc.decodeHistory(WearIpc.encodeHistory(page))).isEqualTo(page)
  }

  @Test
  fun `outcomes round trip and unknown values are safe`() {
    Outcome.entries.forEach { assertThat(WearIpc.outcomeFromInt(WearIpc.outcomeToInt(it))).isEqualTo(it) }
    assertThat(WearIpc.outcomeFromInt(99)).isEqualTo(Outcome.UNKNOWN_THREAD)
    assertThat(WearIpc.outcomeFromInt(-1)).isEqualTo(Outcome.UNKNOWN_THREAD)
  }
}
