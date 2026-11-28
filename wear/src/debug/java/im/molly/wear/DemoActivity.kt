/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import im.molly.wear.protocol.Command
import im.molly.wear.protocol.CommandResult
import im.molly.wear.protocol.Snapshot
import im.molly.wear.protocol.ThreadSummary
import im.molly.wear.protocol.WearMessage
import im.molly.wear.protocol.WearQuote
import im.molly.wear.protocol.WearReaction
import im.molly.wear.protocol.WearTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * DEBUG BUILDS ONLY. The real watch UI fed with made-up chats (no phone, no Data Layer), so the
 * glass theme can be checked and screenshotted on an emulator:
 *   adb shell am start -n im.molly.app.wear/im.molly.wear.DemoActivity
 */
class DemoActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val controller = WatchController(DemoLink(), lifecycleScope)
    controller.start()
    setContent { WatchApp(controller) }
  }
}

private class DemoLink : PhoneLink {
  private val now = System.currentTimeMillis()
  private val min = 60_000L

  private val crew = listOf(
    WearMessage(1, 7, isOutgoing = false, authorName = "Rin", body = "Bouldering at 6?", sentAt = now - 42 * min),
    WearMessage(2, 7, isOutgoing = true, body = "I'm in! Bringing the new shoes", sentAt = now - 40 * min,
      reactions = listOf(WearReaction("🔥", 2, includesMe = false))),
    WearMessage(3, 7, isOutgoing = false, authorName = "Sol", body = "Same, see you there", sentAt = now - 12 * min,
      quote = WearQuote(authorName = "Rin", body = "Bouldering at 6?")),
    WearMessage(4, 7, isOutgoing = false, authorName = "Rin", body = "Glass theme looks so clean", sentAt = now - 3 * min,
      reactions = listOf(WearReaction("👍", 1, includesMe = true)))
  )
  private val kai = listOf(
    WearMessage(10, 8, isOutgoing = false, body = "Did the build finish?", sentAt = now - 95 * min),
    WearMessage(11, 8, isOutgoing = true, body = "Yep, booting it now", sentAt = now - 90 * min)
  )
  private val notes = listOf(
    WearMessage(20, 9, isOutgoing = true, body = "Buy coffee beans", sentAt = now - 26 * 60 * min)
  )

  override val snapshots = MutableStateFlow<Snapshot?>(
    Snapshot(
      generatedAt = now,
      locked = false,
      unreadThreadCount = 2,
      threads = listOf(
        ThreadSummary(7, "Climbing crew", isGroup = true, unreadCount = 2, lastActivityAt = now - 3 * min,
          recentMessages = crew, chatColor = 0xFF2E7D5B.toInt()),
        ThreadSummary(8, "Kai", isGroup = false, unreadCount = 1, lastActivityAt = now - 90 * min,
          recentMessages = kai, chatColor = 0xFF3F5FB5.toInt()),
        ThreadSummary(9, "Note to self", isGroup = false, unreadCount = 0, lastActivityAt = now - 26 * 60 * min,
          recentMessages = notes)
      ),
      theme = WearTheme(
        primary = 0xFFA3D1B7.toInt(), onPrimary = 0xFF0B3826.toInt(),
        primaryContainer = 0xFF254F3C.toInt(), onPrimaryContainer = 0xFFBFEDD2.toInt(),
        secondary = 0xFFB4CCBD.toInt(), onSecondary = 0xFF20352A.toInt(),
        secondaryContainer = 0xFF374B40.toInt(), onSecondaryContainer = 0xFFD0E8D8.toInt(),
        tertiary = 0xFFA4CDDD.toInt(), onTertiary = 0xFF053542.toInt(),
        tertiaryContainer = 0xFF234C59.toInt(), onTertiaryContainer = 0xFFC0E9FA.toInt(),
        surfaceContainerLow = 0xFF171D1A.toInt(), surfaceContainer = 0xFF1B211E.toInt(),
        surfaceContainerHigh = 0xFF252B28.toInt(), onSurface = 0xFFDEE4DF.toInt(),
        onSurfaceVariant = 0xFFC0C9C2.toInt(), outline = 0xFF8A938C.toInt(),
        outlineVariant = 0xFF404944.toInt()
      )
    )
  )

  override suspend fun isPhoneReachable() = true

  override suspend fun send(command: Command): CommandResult = when (command) {
    is Command.FetchHistory -> CommandResult.History(
      command.requestId, command.threadId,
      when (command.threadId) {
        7L -> crew
        8L -> kai
        else -> notes
      },
      hasMore = false
    )
    else -> CommandResult.Ok(command.requestId)
  }
}
