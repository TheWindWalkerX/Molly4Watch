/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.bridge

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import im.molly.wear.protocol.Command
import im.molly.wear.protocol.CommandResult
import im.molly.wear.protocol.ErrorCode
import im.molly.wear.protocol.WearCodec
import im.molly.wear.protocol.WearLimits
import im.molly.wear.protocol.WearMessage
import org.junit.Test

class CommandHandlerTest {
  private val gateway = FakeGateway()
  private var publishRequests = 0
  private val handler = CommandHandler(gateway) { publishRequests++ }

  private fun run(command: Command): CommandResult = handler.handle(WearCodec.encodeCommand(command))

  @Test
  fun `locked - every command is refused and nothing reaches Molly`() {
    gateway.locked = true
    val commands = listOf(
      Command.SendText("r1", 7, "hi"),
      Command.React("r2", 7, 1, "👍"),
      Command.RemoveReaction("r3", 7, 1),
      Command.MarkRead("r4", 7),
      Command.FetchHistory("r5", 7, 0, 20),
      Command.RequestSnapshot("r6")
    )
    for (command in commands) {
      assertThat(run(command)).isEqualTo(CommandResult.Error(command.requestId, ErrorCode.LOCKED))
    }
    assertThat(gateway.calls).isEmpty()
    assertThat(publishRequests).isEqualTo(0)
  }

  @Test
  fun `locked check happens before validation`() {
    gateway.locked = true
    assertThat(run(Command.SendText("r1", 7, "   "))).isEqualTo(CommandResult.Error("r1", ErrorCode.LOCKED))
  }

  @Test
  fun `send text maps to sendText and triggers a snapshot publish`() {
    assertThat(run(Command.SendText("r1", 7, "on my way"))).isEqualTo(CommandResult.Ok("r1"))
    assertThat(gateway.calls).containsExactly("sendText(7,on my way)")
    assertThat(publishRequests).isEqualTo(1)
  }

  @Test
  fun `quoted reply passes the quoted message id through`() {
    assertThat(run(Command.SendText("r1", 7, "yes!", quoteMessageId = 42))).isEqualTo(CommandResult.Ok("r1"))
    assertThat(gateway.calls).containsExactly("sendText(7,yes!,quote=42)")
  }

  @Test
  fun `invalid quote id is rejected before reaching Molly`() {
    val result = run(Command.SendText("r1", 7, "yes!", quoteMessageId = 0))
    assertThat((result as CommandResult.Error).code).isEqualTo(ErrorCode.INVALID_COMMAND)
    assertThat(gateway.calls).isEmpty()
  }

  @Test
  fun `react and remove reaction map to the right gateway calls`() {
    assertThat(run(Command.React("r1", 7, 2, "❤️"))).isEqualTo(CommandResult.Ok("r1"))
    assertThat(run(Command.RemoveReaction("r2", 7, 2))).isEqualTo(CommandResult.Ok("r2"))
    assertThat(gateway.calls).containsExactly("react(7,2,❤️)", "removeReaction(7,2)")
    assertThat(publishRequests).isEqualTo(2)
  }

  @Test
  fun `mark read maps to markRead`() {
    assertThat(run(Command.MarkRead("r1", 7))).isEqualTo(CommandResult.Ok("r1"))
    assertThat(gateway.calls).containsExactly("markRead(7)")
  }

  @Test
  fun `unknown thread and message are reported, without a publish`() {
    assertThat(run(Command.MarkRead("r1", 99))).isEqualTo(CommandResult.Error("r1", ErrorCode.UNKNOWN_THREAD))
    assertThat(run(Command.React("r2", 7, 99, "👍"))).isEqualTo(CommandResult.Error("r2", ErrorCode.UNKNOWN_MESSAGE))
    assertThat(run(Command.FetchHistory("r3", 99, 0, 10))).isEqualTo(CommandResult.Error("r3", ErrorCode.UNKNOWN_THREAD))
    assertThat(publishRequests).isEqualTo(0)
  }

  @Test
  fun `invalid commands never reach Molly`() {
    assertThat(run(Command.SendText("r1", 7, "  "))).isInstanceOf(CommandResult.Error::class)
    assertThat(run(Command.FetchHistory("r2", 7, 0, 0))).isInstanceOf(CommandResult.Error::class)
    assertThat((run(Command.React("r3", 7, 1, "")) as CommandResult.Error).code).isEqualTo(ErrorCode.INVALID_COMMAND)
    assertThat(gateway.calls).isEmpty()
  }

  @Test
  fun `undecodable bytes give INVALID_COMMAND with empty requestId`() {
    val result = handler.handle("garbage".toByteArray())
    assertThat(result).isEqualTo(
      CommandResult.Error(CommandHandler.UNKNOWN_REQUEST_ID, ErrorCode.INVALID_COMMAND, (result as CommandResult.Error).detail)
    )
    assertThat(gateway.calls).isEmpty()
  }

  @Test
  fun `gateway exceptions become INTERNAL instead of crashing`() {
    gateway.throwOnSend = IllegalStateException("db closed")
    assertThat(run(Command.SendText("r1", 7, "hi"))).isEqualTo(CommandResult.Error("r1", ErrorCode.INTERNAL))
  }

  @Test
  fun `fetch history returns the page`() {
    val result = run(Command.FetchHistory("r1", 7, 0, 2)) as CommandResult.History
    assertThat(result.messages.map { it.messageId }).containsExactly(1L, 2L)
    assertThat(result.hasMore).isTrue()
    assertThat(publishRequests).isEqualTo(0)
  }

  @Test
  fun `request snapshot triggers a publish`() {
    assertThat(run(Command.RequestSnapshot("r1"))).isEqualTo(CommandResult.Ok("r1"))
    assertThat(publishRequests).isEqualTo(1)
  }

  @Test
  fun `oversized history page is trimmed from the oldest end and marked hasMore`() {
    // 50 messages of 1000 three-byte chars is ~150 KB, well over the limit.
    gateway.historyFactory = { threadId, _, limit ->
      List(limit) { WearMessage(messageId = it + 1L, threadId = threadId, isOutgoing = false, body = "€".repeat(WearLimits.MAX_BODY_CHARS), sentAt = it.toLong()) }
    }
    val bytes = handler.handleToBytes(WearCodec.encodeCommand(Command.FetchHistory("r1", 7, 0, WearLimits.MAX_HISTORY_PAGE)))
    assertThat(bytes.size).isLessThan(WearLimits.MAX_PAYLOAD_BYTES + 1)
    val result = WearCodec.decodeResult(bytes) as CommandResult.History
    assertThat(result.hasMore).isTrue()
    assertThat(result.messages.size).isLessThan(WearLimits.MAX_HISTORY_PAGE)
    // Newest (last) message is kept.
    assertThat(result.messages.last().messageId).isEqualTo(WearLimits.MAX_HISTORY_PAGE.toLong())
  }
}
