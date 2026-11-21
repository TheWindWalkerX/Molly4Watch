/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.protocol

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.messageContains
import org.junit.Test

class WearCodecTest {

  private val message = WearMessage(
    messageId = 42,
    threadId = 7,
    isOutgoing = false,
    authorName = "Alex",
    body = "see you at 5 🙂",
    sentAt = 1_700_000_000_000,
    reactions = listOf(WearReaction("👍", 2, includesMe = true))
  )

  private val snapshot = Snapshot(
    generatedAt = 1_700_000_001_000,
    locked = false,
    unreadThreadCount = 3,
    threads = listOf(
      ThreadSummary(threadId = 7, title = "Climbing", isGroup = true, unreadCount = 2, lastActivityAt = 1_700_000_000_000, recentMessages = listOf(message))
    )
  )

  @Test
  fun `snapshot round-trips`() {
    assertThat(WearCodec.decodeSnapshot(WearCodec.encodeSnapshot(snapshot))).isEqualTo(snapshot)
  }

  @Test
  fun `locked snapshot round-trips and carries no threads`() {
    val decoded = WearCodec.decodeSnapshot(WearCodec.encodeSnapshot(Snapshot.locked(123)))
    assertThat(decoded.locked).isEqualTo(true)
    assertThat(decoded.threads).isEqualTo(emptyList())
  }

  @Test
  fun `every command type round-trips`() {
    val commands = listOf(
      Command.SendText("r1", 7, "on my way"),
      Command.React("r2", 7, 42, "❤️"),
      Command.RemoveReaction("r3", 7, 42),
      Command.MarkRead("r4", 7),
      Command.FetchHistory("r5", 7, offset = 20, limit = 20),
      Command.RequestSnapshot("r6")
    )
    for (command in commands) {
      assertThat(WearCodec.decodeCommand(WearCodec.encodeCommand(command))).isEqualTo(command)
    }
  }

  @Test
  fun `every result type round-trips`() {
    val results = listOf(
      CommandResult.Ok("r1"),
      CommandResult.History("r2", 7, listOf(message), hasMore = true),
      CommandResult.Error("r3", ErrorCode.LOCKED),
      CommandResult.Error("r4", ErrorCode.UNKNOWN_MESSAGE, "gone")
    )
    for (result in results) {
      assertThat(WearCodec.decodeResult(WearCodec.encodeResult(result))).isEqualTo(result)
    }
  }

  @Test
  fun `wire format has version envelope and type discriminator`() {
    val text = WearCodec.encodeCommand(Command.MarkRead("r1", 7)).toString(Charsets.UTF_8)
    assertThat(text).contains("\"v\":$PROTOCOL_VERSION")
    assertThat(text).contains("\"type\":\"mark_read\"")
  }

  @Test
  fun `unknown fields from a newer peer are ignored`() {
    val bytes = """{"v":$PROTOCOL_VERSION,"futureTopLevel":1,"body":{"type":"mark_read","requestId":"r1","threadId":7,"newField":"x"}}"""
      .toByteArray()
    assertThat(WearCodec.decodeCommand(bytes)).isEqualTo(Command.MarkRead("r1", 7))
  }

  @Test
  fun `other major version is rejected`() {
    val bytes = """{"v":${PROTOCOL_VERSION + 1},"body":{"type":"mark_read","requestId":"r1","threadId":7}}""".toByteArray()
    assertFailure { WearCodec.decodeCommand(bytes) }
      .isInstanceOf(WearProtocolException::class)
      .messageContains("unsupported protocol version")
  }

  @Test
  fun `missing version is rejected`() {
    assertFailure { WearCodec.decodeCommand("""{"body":{}}""".toByteArray()) }
      .isInstanceOf(WearProtocolException::class)
  }

  @Test
  fun `garbage and unknown command types are rejected as protocol errors`() {
    val inputs = listOf(
      "not json",
      "[1,2,3]",
      """{"v":$PROTOCOL_VERSION,"body":{"type":"self_destruct","requestId":"r1"}}""",
      """{"v":$PROTOCOL_VERSION,"body":{"type":"mark_read"}}"""
    )
    for (input in inputs) {
      assertFailure { WearCodec.decodeCommand(input.toByteArray()) }.isInstanceOf(WearProtocolException::class)
    }
  }

  @Test
  fun `oversized payloads are refused both ways`() {
    val huge = snapshot.copy(threads = List(400) { snapshot.threads[0].copy(threadId = it.toLong() + 1, recentMessages = List(5) { message.copy(body = "x".repeat(1000)) }) })
    assertFailure { WearCodec.encodeSnapshot(huge) }.isInstanceOf(WearProtocolException::class)
    assertFailure { WearCodec.decodeSnapshot(ByteArray(WearLimits.MAX_PAYLOAD_BYTES + 1)) }.isInstanceOf(WearProtocolException::class)
  }

  @Test
  fun `a full-size snapshot fits the payload limit`() {
    val longBody = "x".repeat(WearLimits.MAX_BODY_CHARS)
    val full = snapshot.copy(
      threads = List(WearLimits.MAX_SNAPSHOT_THREADS) { t ->
        snapshot.threads[0].copy(
          threadId = t.toLong() + 1,
          title = "t".repeat(100),
          recentMessages = List(WearLimits.MAX_MESSAGES_PER_THREAD) { message.copy(body = longBody, authorName = "a".repeat(100)) }
        )
      }
    )
    assertThat(WearCodec.decodeSnapshot(WearCodec.encodeSnapshot(full))).isEqualTo(full)
  }

  @Test
  fun `validation accepts good commands and rejects bad ones`() {
    assertThat(Command.SendText("r", 1, "hi").validationError()).isNull()
    assertThat(Command.SendText("r", 1, "   ").validationError()).isNotNull()
    assertThat(Command.SendText("r", 1, "x".repeat(WearLimits.MAX_OUTGOING_BODY_CHARS + 1)).validationError()).isNotNull()
    assertThat(Command.SendText("r", 0, "hi").validationError()).isNotNull()
    assertThat(Command.React("r", 1, 2, "👍").validationError()).isNull()
    assertThat(Command.React("r", 1, 2, "").validationError()).isNotNull()
    assertThat(Command.RemoveReaction("r", 1, 0).validationError()).isNotNull()
    assertThat(Command.MarkRead("r", -1).validationError()).isNotNull()
    assertThat(Command.FetchHistory("r", 1, 0, 20).validationError()).isNull()
    assertThat(Command.FetchHistory("r", 1, -1, 20).validationError()).isNotNull()
    assertThat(Command.FetchHistory("r", 1, 0, 0).validationError()).isNotNull()
    assertThat(Command.FetchHistory("r", 1, 0, WearLimits.MAX_HISTORY_PAGE + 1).validationError()).isNotNull()
    assertThat(Command.RequestSnapshot("r").validationError()).isNull()
  }

  @Test
  fun `truncation keeps short text and never splits an emoji`() {
    assertThat("hello".truncateForWatch(10)).isEqualTo("hello")
    assertThat("abcdefghij".truncateForWatch(5)).isEqualTo("abcd…")
    // "abc" + 🙂 (2 UTF-16 units) + "def": cutting at 5 would land inside the emoji.
    assertThat("abc🙂def".truncateForWatch(5)).isEqualTo("abc…")
  }
}
