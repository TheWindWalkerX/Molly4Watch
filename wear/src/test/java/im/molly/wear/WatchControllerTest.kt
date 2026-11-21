/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import im.molly.wear.protocol.Command
import im.molly.wear.protocol.CommandResult
import im.molly.wear.protocol.ErrorCode
import im.molly.wear.protocol.Snapshot
import im.molly.wear.protocol.ThreadSummary
import im.molly.wear.protocol.WearMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchControllerTest {

  private class FakeLink : PhoneLink {
    override val snapshots = MutableStateFlow<Snapshot?>(null)
    var reachable = true
    val sent = mutableListOf<Command>()
    var respond: (Command) -> CommandResult = { cmd ->
      when (cmd) {
        is Command.FetchHistory -> CommandResult.History(cmd.requestId, cmd.threadId, listOf(msg(1), msg(2)), hasMore = true)
        else -> CommandResult.Ok(cmd.requestId)
      }
    }
    override suspend fun isPhoneReachable() = reachable
    override suspend fun send(command: Command): CommandResult {
      if (!reachable) throw PhoneUnreachableException()
      sent += command
      return respond(command)
    }
  }

  companion object {
    fun msg(id: Long, body: String = "m$id", reactions: List<im.molly.wear.protocol.WearReaction> = emptyList()) =
      WearMessage(messageId = id, threadId = 7, isOutgoing = false, body = body, sentAt = id * 10, reactions = reactions)

    val readySnapshot = Snapshot(
      generatedAt = 1,
      locked = false,
      unreadThreadCount = 1,
      threads = listOf(ThreadSummary(7, "Climbing", isGroup = true, unreadCount = 2, lastActivityAt = 30, recentMessages = listOf(msg(3))))
    )
  }

  private val link = FakeLink()
  private var ids = 0

  private fun TestScope.controller(): WatchController =
    WatchController(link, backgroundScope) { "r${++ids}" }

  @Test
  fun `starts connecting, asks for a snapshot, then shows chats`() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.start()
    assertThat(link.sent.single()).isInstanceOf(Command.RequestSnapshot::class)
    assertThat(c.home.value).isEqualTo(HomeState.Connecting)
    link.snapshots.value = readySnapshot
    assertThat(c.home.value).isEqualTo(HomeState.Ready(readySnapshot))
  }

  @Test
  fun `no phone reachable shows NoPhone`() = runTest(UnconfinedTestDispatcher()) {
    link.reachable = false
    val c = controller()
    c.start()
    assertThat(c.home.value).isEqualTo(HomeState.NoPhone)
  }

  @Test
  fun `locked snapshot shows Locked and drops the open thread`() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.start()
    link.snapshots.value = readySnapshot
    c.openThread(7)
    link.snapshots.value = Snapshot.locked(2)
    assertThat(c.home.value).isEqualTo(HomeState.Locked)
    assertThat(c.thread.value).isNull()
  }

  @Test
  fun `LOCKED error from the phone also locks down`() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.start()
    link.snapshots.value = readySnapshot
    c.openThread(7)
    link.respond = { CommandResult.Error(it.requestId, ErrorCode.LOCKED) }
    c.sendText("hi")
    assertThat(c.home.value).isEqualTo(HomeState.Locked)
    assertThat(c.thread.value).isNull()
  }

  @Test
  fun `opening a thread shows snapshot messages merged with the first history page`() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.start()
    link.snapshots.value = readySnapshot
    c.openThread(7)
    val fetch = link.sent.last() as Command.FetchHistory
    assertThat(fetch.offset).isEqualTo(0)
    assertThat(fetch.limit).isEqualTo(WatchController.PAGE_SIZE)
    val t = c.thread.value!!
    assertThat(t.title).isEqualTo("Climbing")
    assertThat(t.messages.map { it.messageId }).containsExactly(1L, 2L, 3L)
    assertThat(t.hasMore).isEqualTo(true)
    assertThat(t.loading).isEqualTo(false)
  }

  @Test
  fun `load older asks for the next offset`() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.start()
    link.snapshots.value = readySnapshot
    c.openThread(7)
    c.loadOlder()
    assertThat((link.sent.last() as Command.FetchHistory).offset).isEqualTo(3)
  }

  @Test
  fun `actions send the right commands for the open thread`() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.start()
    link.snapshots.value = readySnapshot
    c.openThread(7)
    link.sent.clear()
    c.sendText("  on my way  ")
    c.react(2, "👍")
    c.removeReaction(2)
    c.markRead()
    val actions = link.sent.filter { it !is Command.FetchHistory }
    assertThat(actions).containsExactly(
      Command.SendText("r3", 7, "on my way"),
      Command.React("r5", 7, 2, "👍"),
      Command.RemoveReaction("r7", 7, 2),
      Command.MarkRead("r9", 7)
    )
    assertThat(c.status.value).isEqualTo(ActionStatus.Idle)
  }

  @Test
  fun `quoted reply carries the quoted message id`() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.start()
    link.snapshots.value = readySnapshot
    c.openThread(7)
    link.sent.clear()
    c.sendText("sure", quoteMessageId = 2)
    assertThat(link.sent.filterIsInstance<Command.SendText>().single().quoteMessageId).isEqualTo(2L)
  }

  @Test
  fun `blank reply is not sent`() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.start()
    link.snapshots.value = readySnapshot
    c.openThread(7)
    link.sent.clear()
    c.sendText("   ")
    assertThat(link.sent.size).isEqualTo(0)
  }

  @Test
  fun `failed action reports why`() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.start()
    link.snapshots.value = readySnapshot
    c.openThread(7)
    link.respond = { CommandResult.Error(it.requestId, ErrorCode.UNKNOWN_MESSAGE) }
    c.react(99, "👍")
    assertThat(c.status.value).isEqualTo(ActionStatus.Failed("message not found"))
  }

  @Test
  fun `phone vanishing mid-action reports unreachable`() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.start()
    link.snapshots.value = readySnapshot
    c.openThread(7)
    link.reachable = false
    c.markRead()
    assertThat(c.status.value).isEqualTo(ActionStatus.Failed("phone not reachable"))
  }

  @Test
  fun `newer snapshot updates reactions in the open thread`() = runTest(UnconfinedTestDispatcher()) {
    val c = controller()
    c.start()
    link.snapshots.value = readySnapshot
    c.openThread(7)
    val reacted = msg(3, reactions = listOf(im.molly.wear.protocol.WearReaction("❤️", 1, includesMe = true)))
    link.snapshots.value = readySnapshot.copy(threads = listOf(readySnapshot.threads[0].copy(recentMessages = listOf(reacted))))
    assertThat(c.thread.value!!.messages.last()).isEqualTo(reacted)
  }

  @Test
  fun `merge dedupes by id, newer copy wins, ordered oldest first`() {
    val merged = WatchController.merge(listOf(msg(2), msg(1)), listOf(msg(2, body = "edited"), msg(3)))
    assertThat(merged.map { it.messageId }).containsExactly(1L, 2L, 3L)
    assertThat(merged[1].body).isEqualTo("edited")
  }
}
