/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper
import im.molly.wear.protocol.ThreadSummary
import im.molly.wear.protocol.WearMessage
import java.util.Date

private const val REPLY_KEY = "reply"

/** A new time label after this much silence, like Google Messages. */
private const val TIME_GAP_MS = 5 * 60 * 1000L

/** Quick reaction set offered by the message actions screen. */
val REACTION_CHOICES = listOf("❤️", "👍", "👎", "😂", "😮", "😢")

@Composable
fun WatchApp(controller: WatchController) {
  val navController = rememberSwipeDismissableNavController()
  val home by controller.home.collectAsStateWithLifecycle()
  val phoneTheme by controller.theme.collectAsStateWithLifecycle()

  // Molly locked: drop back to the home screen, which shows the locked state.
  LaunchedEffect(home) {
    if (home !is HomeState.Ready) navController.popBackStack("home", inclusive = false)
  }

  MaterialTheme(colorScheme = phoneTheme.toColorScheme()) {
    // Liquid glass: every screen floats over the aurora backdrop. The backdrop is drawn inside
    // each destination, because the swipe-to-dismiss host paints every screen opaque.
    AppScaffold(containerColor = Color.Transparent) {
      SwipeDismissableNavHost(navController = navController, startDestination = "home") {
        composable("home") {
          // Back on the chat list: forget the thread. (Not done when the thread screen leaves
          // composition, because opening the actions screen does that too.)
          LaunchedEffect(Unit) { controller.closeThread() }
          GlassBackdrop {
            HomeScreen(home, onRetry = controller::refresh, onOpen = { id ->
              controller.openThread(id)
              navController.navigate("thread")
            })
          }
        }
        composable("thread") {
          GlassBackdrop {
            ThreadScreen(controller, onActions = { messageId -> navController.navigate("actions/$messageId") })
          }
        }
        composable("actions/{messageId}") { entry ->
          val messageId = entry.arguments?.getString("messageId")?.toLongOrNull() ?: return@composable
          GlassBackdrop {
            MessageActionsScreen(controller, messageId, onDone = { navController.popBackStack() })
          }
        }
      }
    }
  }
}

/** Primary actions: dense accent glass with the edge band. */
@Composable
private fun primaryGlassPainter() = rememberGlassPainter(MaterialTheme.colorScheme.primary, PRIMARY_GLASS)

@Composable
private fun primaryGlassColors() =
  ButtonDefaults.buttonColors(contentColor = Glass.onGlass(MaterialTheme.colorScheme.primary, PRIMARY_GLASS))

private const val PRIMARY_GLASS = 0.80f

@Composable
fun HomeScreen(state: HomeState, onRetry: () -> Unit, onOpen: (Long) -> Unit) {
  when (state) {
    HomeState.Connecting -> StatusScreen(tag = "connecting") { CenteredText(stringResource(R.string.connecting)) }
    HomeState.NoPhone -> StatusScreen(tag = "no-phone") {
      CenteredText(stringResource(R.string.no_phone))
      Button(
        onClick = onRetry,
        containerPainter = primaryGlassPainter(),
        colors = primaryGlassColors(),
        border = Glass.border,
        label = { Text(stringResource(R.string.retry)) }
      )
    }
    HomeState.Locked -> StatusScreen(tag = "locked") {
      CenteredText(stringResource(R.string.locked_title), style = MaterialTheme.typography.titleMedium)
      CenteredText(stringResource(R.string.locked_body))
    }
    is HomeState.Ready -> ChatList(state, onOpen)
  }
}

/** Non-scrolling, centered message: reads well on a round screen and has nothing to scroll. */
@Composable
private fun StatusScreen(tag: String, content: @Composable ColumnScope.() -> Unit) {
  Box(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp).testTag(tag), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
  }
}

/** Scales and fades list items near the round screen's top and bottom edges. */
private fun TransformingLazyColumnItemScope.edgeTransform(spec: TransformationSpec): Modifier =
  Modifier.transformedHeight(this, spec).graphicsLayer { with(spec) { applyContainerTransformation(scrollProgress) } }

@Composable
private fun ChatList(state: HomeState.Ready, onOpen: (Long) -> Unit) {
  val listState = rememberTransformingLazyColumnState()
  val spec = rememberTransformationSpec()
  ScreenScaffold(scrollState = listState) { padding ->
    TransformingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.testTag("home")) {
      item { ListHeader(modifier = edgeTransform(spec)) { Text(stringResource(R.string.chats)) } }
      if (state.snapshot.threads.isEmpty()) {
        item { CenteredText(stringResource(R.string.no_unread), modifier = edgeTransform(spec)) }
      }
      items(state.snapshot.threads, key = { it.threadId }) { thread ->
        Button(
          onClick = { onOpen(thread.threadId) },
          containerPainter = rememberGlassPainter(MaterialTheme.colorScheme.surfaceContainerHigh),
          modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).testTag("thread-${thread.threadId}"),
          colors = ButtonDefaults.filledTonalButtonColors(),
          border = Glass.border,
          contentPadding = PaddingValues(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
          transformation = SurfaceTransformation(spec)
        ) {
          Avatar(thread)
          Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
            val unread = thread.unreadCount > 0
            val weight = if (unread) FontWeight.Bold else FontWeight.Normal
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                thread.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = weight,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
              )
              Text(
                listTime(thread.lastActivityAt),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = weight,
                color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
              )
            }
            // The latest message, bold while unread, like Google Messages.
            thread.recentMessages.lastOrNull()?.let { last ->
              val prefix = if (last.isOutgoing) stringResource(R.string.you) + ": " else ""
              Text(
                prefix + previewText(last),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (unread && !last.isOutgoing) FontWeight.Bold else FontWeight.Normal,
                color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      }
    }
  }
}

/** Profile picture from Molly (or an initial in the chat's color), with an unread badge like Google Messages. */
@Composable
private fun Avatar(thread: ThreadSummary) {
  val color = thread.chatColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
  val picture = remember(thread.avatar) { decodeAvatar(thread.avatar) }
  Box {
    if (picture != null) {
      Image(picture, contentDescription = null, modifier = Modifier.size(36.dp).clip(CircleShape).testTag("avatar-${thread.threadId}"))
    } else {
      Box(Modifier.size(36.dp).background(color, CircleShape).border(Glass.border, CircleShape), contentAlignment = Alignment.Center) {
        Text(
          thread.title.firstOrNull()?.uppercase() ?: "?",
          color = if (color.luminance() > 0.5f) Color.Black else Color.White,
          style = MaterialTheme.typography.labelMedium
        )
      }
    }
    if (thread.unreadCount > 0) {
      Box(
        Modifier.align(Alignment.TopEnd).size(16.dp).background(MaterialTheme.colorScheme.error, CircleShape).testTag("unread-${thread.threadId}"),
        contentAlignment = Alignment.Center
      ) {
        Text(
          if (thread.unreadCount > 9) "9+" else thread.unreadCount.toString(),
          color = MaterialTheme.colorScheme.onError,
          style = MaterialTheme.typography.labelSmall
        )
      }
    }
  }
}

@Composable
fun ThreadScreen(controller: WatchController, onActions: (Long) -> Unit) {
  val thread by controller.thread.collectAsStateWithLifecycle()
  val status by controller.status.collectAsStateWithLifecycle()
  val replyTarget by controller.replyTarget.collectAsStateWithLifecycle()
  val open = thread ?: return
  val listState = rememberTransformingLazyColumnState()
  val spec = rememberTransformationSpec()
  val colors = bubbleColors(open.chatColor)
  val context = LocalContext.current
  val timeFormat = DateFormat.getTimeFormat(context)

  val replyLabel = stringResource(R.string.reply)
  val replyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    val text = if (result.resultCode == Activity.RESULT_OK) {
      result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(REPLY_KEY)?.toString()
    } else {
      null
    }
    if (controller.replyTarget.value != null) controller.finishReply(text) else if (!text.isNullOrBlank()) controller.sendText(text)
  }

  // A swipe or the "Reply" action set a target: open the system input for it.
  LaunchedEffect(replyTarget) {
    if (replyTarget != null) replyLauncher.launch(replyIntent(replyLabel))
  }

  // Start at the newest message.
  LaunchedEffect(open.threadId, open.messages.size) {
    if (open.messages.isNotEmpty()) listState.scrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
  }

  ScreenScaffold(scrollState = listState) { padding ->
    TransformingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.testTag("thread")) {
      item { ListHeader(modifier = edgeTransform(spec)) { Text(open.title, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
      if (open.hasMore) {
        item {
          Button(
            onClick = controller::loadOlder,
          containerPainter = primaryGlassPainter(),
          colors = primaryGlassColors(),
          border = Glass.border,
            modifier = Modifier.transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
            label = { Text(stringResource(R.string.load_older)) }
          )
        }
      }
      itemsIndexed(open.messages, key = { _, m -> m.messageId }) { index, message ->
        val previous = open.messages.getOrNull(index - 1)
        val next = open.messages.getOrNull(index + 1)
        MessageBubble(
          message = message,
          colors = colors,
          showAuthor = open.isGroup && (previous == null || previous.isOutgoing || previous.authorName != message.authorName),
          showTime = next == null || next.isOutgoing != message.isOutgoing || next.sentAt - message.sentAt > TIME_GAP_MS,
          timeText = timeFormat.format(Date(message.sentAt)),
          onLongPress = { onActions(message.messageId) },
          onSwipeReply = { controller.startReply(message) },
          modifier = edgeTransform(spec)
        )
      }
      when (val s = status) {
        ActionStatus.Sending -> item { CenteredText(stringResource(R.string.sending), modifier = edgeTransform(spec)) }
        is ActionStatus.Failed -> item {
          CenteredText(stringResource(R.string.failed, s.reason), modifier = edgeTransform(spec).testTag("status-failed"))
        }
        ActionStatus.Idle -> Unit
      }
      item {
        Button(
          onClick = { replyLauncher.launch(replyIntent(replyLabel)) },
          containerPainter = primaryGlassPainter(),
          colors = primaryGlassColors(),
          border = Glass.border,
          modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).testTag("reply"),
          transformation = SurfaceTransformation(spec),
          label = { Text(replyLabel) }
        )
      }
      item {
        Button(
          onClick = controller::markRead,
          containerPainter = primaryGlassPainter(),
          colors = primaryGlassColors(),
          border = Glass.border,
          modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).testTag("mark-read"),
          transformation = SurfaceTransformation(spec),
          label = { Text(stringResource(R.string.mark_read)) }
        )
      }
    }
  }
}

/** System reply UI: voice, keyboard and emoji. */
private fun replyIntent(label: String): Intent {
  val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
  RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(REPLY_KEY).setLabel(label).build()))
  return intent
}

/** Long-press on a message: quick reactions, reply, and removing my reaction. */
@Composable
fun MessageActionsScreen(controller: WatchController, messageId: Long, onDone: () -> Unit) {
  val thread by controller.thread.collectAsStateWithLifecycle()
  val message: WearMessage = thread?.messages?.firstOrNull { it.messageId == messageId } ?: return
  val listState = rememberTransformingLazyColumnState()
  val spec = rememberTransformationSpec()
  ScreenScaffold(scrollState = listState) { padding ->
    TransformingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.testTag("message-actions")) {
      item {
        Text(
          previewText(message),
          modifier = edgeTransform(spec).fillMaxWidth().padding(horizontal = 16.dp),
          textAlign = TextAlign.Center,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      // Two rows of three, like the reaction bar on the phone.
      REACTION_CHOICES.chunked(3).forEach { row ->
        item {
          Row(modifier = edgeTransform(spec).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            row.forEach { emoji ->
              val mine = message.reactions.any { it.includesMe && it.emoji == emoji }
              Box(
                Modifier
                  .size(44.dp)
                  .glass(
                    if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    CircleShape,
                    if (mine) 0.62f else Glass.SURFACE_ALPHA
                  )
                  .clickable {
                    controller.react(messageId, emoji)
                    onDone()
                  }
                  .testTag("react-$emoji"),
                contentAlignment = Alignment.Center
              ) { Text(emoji, style = MaterialTheme.typography.titleMedium) }
            }
          }
        }
      }
      item {
        Button(
          onClick = {
            controller.startReply(message)
            onDone()
          },
          containerPainter = primaryGlassPainter(),
          colors = primaryGlassColors(),
          border = Glass.border,
          modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).testTag("action-reply"),
          transformation = SurfaceTransformation(spec),
          label = { Text(stringResource(R.string.reply)) }
        )
      }
      if (message.reactions.any { it.includesMe }) {
        item {
          Button(
            onClick = {
              controller.removeReaction(messageId)
              onDone()
            },
            containerPainter = rememberGlassPainter(MaterialTheme.colorScheme.surfaceContainerHigh),
            border = Glass.border,
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).testTag("remove-reaction"),
            transformation = SurfaceTransformation(spec),
            label = { Text(stringResource(R.string.remove_my_reaction)) }
          )
        }
      }
    }
  }
}

@Composable
private fun CenteredText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium) {
  Text(text, modifier = modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = style)
}
