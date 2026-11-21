/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import im.molly.wear.protocol.Command
import im.molly.wear.protocol.CommandResult
import im.molly.wear.protocol.Snapshot
import im.molly.wear.protocol.ThreadSummary
import im.molly.wear.protocol.WearMessage
import im.molly.wear.protocol.WearReaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class WatchUiTest {
  @get:Rule
  val compose = createComposeRule()

  private class FakeLink : PhoneLink {
    override val snapshots = MutableStateFlow<Snapshot?>(null)
    val sent = CopyOnWriteArrayList<Command>()
    override suspend fun isPhoneReachable() = true
    override suspend fun send(command: Command): CommandResult {
      sent += command
      return if (command is Command.FetchHistory) {
        CommandResult.History(command.requestId, command.threadId, messages, hasMore = false)
      } else {
        CommandResult.Ok(command.requestId)
      }
    }
  }

  companion object {
    val messages = listOf(
      WearMessage(messageId = 1, threadId = 7, isOutgoing = false, authorName = "Alex", body = "Bouldering at 6?", sentAt = 10),
      WearMessage(messageId = 2, threadId = 7, isOutgoing = true, body = "I'm in", sentAt = 20, reactions = listOf(WearReaction("👍", 1, includesMe = true)))
    )
    val snapshot = Snapshot(
      generatedAt = 1,
      locked = false,
      unreadThreadCount = 1,
      threads = listOf(ThreadSummary(7, "Climbing crew", isGroup = true, unreadCount = 2, lastActivityAt = 20, recentMessages = messages))
    )
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val link = FakeLink()

  @After
  fun tearDown() = scope.cancel()

  private fun launch(initial: Snapshot?): WatchController {
    link.snapshots.value = initial
    val controller = WatchController(link, scope)
    compose.setContent { WatchApp(controller) }
    compose.runOnIdle { controller.start() }
    compose.waitForIdle()
    return controller
  }

  @Test
  fun chatListShowsThreadsWithUnreadCount() {
    launch(snapshot)
    compose.onNodeWithText("Climbing crew").assertIsDisplayed()
    compose.onNodeWithTag("unread-7", useUnmergedTree = true).assertIsDisplayed()
  }

  @Test
  fun lockedStateHidesChats() {
    launch(Snapshot.locked(1))
    compose.onNodeWithTag("locked").assertIsDisplayed()
    compose.onNodeWithText("Climbing crew").assertDoesNotExist()
  }

  @Test
  fun lockingWhileInAThreadReturnsToLockedScreen() {
    launch(snapshot)
    compose.onNodeWithTag("thread-7").performClick()
    compose.onNodeWithText("Bouldering at 6?").assertIsDisplayed()
    compose.runOnIdle { link.snapshots.value = Snapshot.locked(2) }
    compose.waitForIdle()
    compose.onNodeWithTag("locked").assertIsDisplayed()
    compose.onNodeWithText("Bouldering at 6?").assertDoesNotExist()
  }

  @Test
  fun threadViewShowsHistoryAndMarksRead() {
    launch(snapshot)
    compose.onNodeWithTag("thread-7").performClick()
    compose.onNodeWithText("Bouldering at 6?").assertIsDisplayed()
    compose.onNodeWithTag("thread").performScrollToNode(hasTestTag("mark-read"))
    compose.onNodeWithTag("mark-read").performClick()
    compose.waitForIdle()
    assertEquals(Command.MarkRead(link.sent.filterIsInstance<Command.MarkRead>().single().requestId, 7), link.sent.filterIsInstance<Command.MarkRead>().single())
  }

  @Test
  fun longPressOpensActionsAndSendsReaction() {
    launch(snapshot)
    compose.onNodeWithTag("thread-7").performClick()
    compose.onNodeWithTag("message-1").performTouchInput { longClick() }
    compose.onNodeWithTag("message-actions").assertIsDisplayed()
    compose.onNodeWithTag("react-❤️").performClick()
    compose.waitForIdle()
    val react = link.sent.filterIsInstance<Command.React>().single()
    assertEquals(1L, react.messageId)
    assertEquals("❤️", react.emoji)
    // Back on the thread after picking.
    compose.onNodeWithText("Bouldering at 6?").assertIsDisplayed()
  }

  @Test
  fun noRemoveOptionWithoutMyReaction() {
    launch(snapshot)
    compose.onNodeWithTag("thread-7").performClick()
    compose.onNodeWithTag("message-1").performTouchInput { longClick() }
    compose.onNodeWithTag("message-actions").assertIsDisplayed()
    compose.onNodeWithTag("remove-reaction").assertDoesNotExist()
  }

  @Test
  fun replyActionStartsAQuotedReply() {
    val controller = launch(snapshot)
    compose.onNodeWithTag("thread-7").performClick()
    compose.onNodeWithTag("message-1").performTouchInput { longClick() }
    compose.onNodeWithTag("message-actions").performScrollToNode(hasTestTag("action-reply"))
    compose.onNodeWithTag("action-reply").performClick()
    compose.waitForIdle()
    // The system input can't be driven from here; finish the reply as it would.
    compose.runOnIdle {
      assertEquals(1L, controller.replyTarget.value?.messageId)
      controller.finishReply("see you there")
    }
    compose.waitForIdle()
    val send = link.sent.filterIsInstance<Command.SendText>().single()
    assertEquals("see you there", send.body)
    assertEquals(1L, send.quoteMessageId)
  }

  @Test
  fun swipeLeftOnAMessageStartsAQuotedReply() {
    val controller = launch(snapshot)
    compose.onNodeWithTag("thread-7").performClick()
    compose.onNodeWithTag("message-1").performTouchInput { swipeLeft() }
    compose.waitForIdle()
    compose.runOnIdle { assertEquals(1L, controller.replyTarget.value?.messageId) }
  }

  @Test
  fun removeOptionForMyReactionSendsRemoval() {
    launch(snapshot)
    compose.onNodeWithTag("thread-7").performClick()
    compose.onNodeWithTag("message-2").performTouchInput { longClick() }
    compose.onNodeWithTag("message-actions").performScrollToNode(hasTestTag("remove-reaction"))
    compose.onNodeWithTag("remove-reaction").performClick()
    compose.waitForIdle()
    assertEquals(2L, link.sent.filterIsInstance<Command.RemoveReaction>().single().messageId)
  }
}
