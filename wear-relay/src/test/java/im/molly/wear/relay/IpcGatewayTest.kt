/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.relay

import android.os.IBinder
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import im.molly.wear.ipc.IMollyWearGateway
import im.molly.wear.ipc.WearIpc
import im.molly.wear.protocol.MollyGateway.HistoryPage
import im.molly.wear.protocol.MollyGateway.Outcome
import im.molly.wear.protocol.Snapshot
import im.molly.wear.protocol.WearTheme
import org.junit.Test

class IpcGatewayTest {

  private open class FakeMolly(
    var locked: Boolean = false,
    var version: Int = WearIpc.IPC_VERSION
  ) : IMollyWearGateway {
    val calls = mutableListOf<String>()
    override fun ipcVersion() = version
    override fun isLocked() = locked
    override fun loadSnapshot(now: Long) = WearIpc.encodeSnapshot(Snapshot(now, locked = false, unreadThreadCount = 2))
    override fun loadHistory(threadId: Long, offset: Int, limit: Int): ByteArray? =
      if (threadId == 404L) null else WearIpc.encodeHistory(HistoryPage(emptyList(), hasMore = offset == 0))
    override fun sendText(threadId: Long, body: String, quoteMessageId: Long): Int { calls += "send:$threadId:$body:$quoteMessageId"; return WearIpc.outcomeToInt(Outcome.OK) }
    override fun react(threadId: Long, messageId: Long, emoji: String) = WearIpc.outcomeToInt(Outcome.UNKNOWN_MESSAGE)
    override fun removeReaction(threadId: Long, messageId: Long) = WearIpc.outcomeToInt(Outcome.OK)
    override fun markRead(threadId: Long) = WearIpc.outcomeToInt(Outcome.UNKNOWN_THREAD)
    override fun asBinder(): IBinder? = null
  }

  @Test
  fun `unreachable Molly counts as locked and data calls fail`() {
    val gateway = IpcGateway { null }
    assertThat(gateway.isLocked()).isTrue()
    assertFailure { gateway.loadSnapshot(1) }.isInstanceOf(IpcGateway.MollyUnavailableException::class)
    assertFailure { gateway.sendText(1, "hi") }.isInstanceOf(IpcGateway.MollyUnavailableException::class)
  }

  @Test
  fun `IPC version mismatch counts as locked`() {
    val gateway = IpcGateway { FakeMolly(version = WearIpc.IPC_VERSION + 1) }
    assertThat(gateway.isLocked()).isTrue()
    assertFailure { gateway.loadSnapshot(1) }.isInstanceOf(IpcGateway.MollyUnavailableException::class)
  }

  @Test
  fun `a failing binder counts as locked`() {
    val gateway = IpcGateway {
      object : FakeMolly() {
        override fun isLocked(): Boolean = throw IllegalStateException("binder died")
      }
    }
    assertThat(gateway.isLocked()).isTrue()
  }

  @Test
  fun `lock state is passed through`() {
    val molly = FakeMolly(locked = false)
    val gateway = IpcGateway { molly }
    assertThat(gateway.isLocked()).isFalse()
    molly.locked = true
    assertThat(gateway.isLocked()).isTrue()
  }

  @Test
  fun `snapshot and history are decoded`() {
    val gateway = IpcGateway { FakeMolly() }
    assertThat(gateway.loadSnapshot(42)).isEqualTo(Snapshot(42, locked = false, unreadThreadCount = 2))
    assertThat(gateway.loadHistory(1, 0, 10)).isEqualTo(HistoryPage(emptyList(), hasMore = true))
    assertThat(gateway.loadHistory(404, 0, 10)).isNull()
  }

  @Test
  fun `the phone theme is attached to snapshots`() {
    val theme = WearTheme(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19)
    val gateway = IpcGateway(theme = { theme }) { FakeMolly() }
    assertThat(gateway.loadSnapshot(1).theme).isEqualTo(theme)
  }

  @Test
  fun `a failing theme lookup doesn't break snapshots`() {
    val gateway = IpcGateway(theme = { throw IllegalStateException("no palette") }) { FakeMolly() }
    assertThat(gateway.loadSnapshot(1).theme).isNull()
  }

  @Test
  fun `commands and outcomes are forwarded`() {
    val molly = FakeMolly()
    val gateway = IpcGateway { molly }
    assertThat(gateway.sendText(3, "hello")).isEqualTo(Outcome.OK)
    assertThat(gateway.sendText(3, "re", quoteMessageId = 9)).isEqualTo(Outcome.OK)
    assertThat(molly.calls).isEqualTo(listOf("send:3:hello:0", "send:3:re:9"))
    assertThat(gateway.react(3, 9, "👍")).isEqualTo(Outcome.UNKNOWN_MESSAGE)
    assertThat(gateway.removeReaction(3, 9)).isEqualTo(Outcome.OK)
    assertThat(gateway.markRead(3)).isEqualTo(Outcome.UNKNOWN_THREAD)
  }
}
