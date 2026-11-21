/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import im.molly.wear.protocol.WearMessage
import im.molly.wear.protocol.WearReaction
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Bubble colors for one chat: outgoing uses the chat's color from Molly, incoming a neutral surface. */
data class BubbleColors(val outgoing: Color, val onOutgoing: Color, val incoming: Color, val onIncoming: Color)

@Composable
fun bubbleColors(chatColor: Int?): BubbleColors {
  val scheme = MaterialTheme.colorScheme
  val outgoing = chatColor?.let { Color(it) } ?: scheme.primary
  return BubbleColors(
    outgoing = outgoing,
    onOutgoing = if (outgoing.luminance() > 0.5f) Color.Black else Color.White,
    incoming = scheme.surfaceContainerHigh,
    onIncoming = scheme.onSurface
  )
}

private val SWIPE_REPLY_THRESHOLD = 48.dp
private val SWIPE_MAX = 72.dp

/**
 * One message, Google Messages style: mine on the right in the chat color, theirs on the left.
 * Swipe LEFT to reply (swiping right is Wear OS's system "back"), long-press for actions.
 *
 * [showAuthor]: first message of a run from another group member. [showTime]: last of a run.
 */
@Composable
fun MessageBubble(
  message: WearMessage,
  colors: BubbleColors,
  showAuthor: Boolean,
  showTime: Boolean,
  timeText: String,
  onLongPress: () -> Unit,
  onSwipeReply: () -> Unit,
  modifier: Modifier = Modifier
) {
  val outgoing = message.isOutgoing
  val density = LocalDensity.current
  val thresholdPx = with(density) { SWIPE_REPLY_THRESHOLD.toPx() }
  val maxPx = with(density) { SWIPE_MAX.toPx() }
  val offsetX = remember { Animatable(0f) }
  val scope = rememberCoroutineScope()

  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
    horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start
  ) {
    if (showAuthor && !outgoing && message.authorName != null) {
      Text(
        message.authorName!!,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
      )
    }

    Box(contentAlignment = Alignment.CenterEnd) {
      // Reply hint revealed behind the bubble while swiping.
      if (offsetX.value < -8f) {
        Text(
          "↩",
          modifier = Modifier.alpha((-offsetX.value / thresholdPx).coerceIn(0f, 1f)).padding(end = 4.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      val shape = if (outgoing) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
      } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
      }
      Column(
        modifier = Modifier
          .offset { IntOffset(offsetX.value.roundToInt(), 0) }
          .widthIn(max = 160.dp)
          .width(IntrinsicSize.Max)
          .background(if (outgoing) colors.outgoing else colors.incoming, shape)
          .combinedClickable(onClick = {}, onLongClick = onLongPress)
          .pointerInput(message.messageId) {
            // Only claim LEFTWARD drags, so rightward swipes still reach swipe-to-dismiss.
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false)
              var leftward = false
              val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                if (over < 0f) {
                  leftward = true
                  change.consume()
                }
              }
              if (drag == null || !leftward) return@awaitEachGesture
              horizontalDrag(drag.id) { change ->
                val next = (offsetX.value + change.positionChange().x).coerceIn(-maxPx, 0f)
                scope.launch { offsetX.snapTo(next) }
                change.consume()
              }
              if (offsetX.value <= -thresholdPx) onSwipeReply()
              scope.launch { offsetX.animateTo(0f) }
            }
          }
          .padding(horizontal = 12.dp, vertical = 8.dp)
          .testTag("message-${message.messageId}")
      ) {
        val onBubble = if (outgoing) colors.onOutgoing else colors.onIncoming
        message.quote?.let { quote ->
          // Inset quote card like Google Messages: tinted background, accent bar, author and snippet.
          Row(
            modifier = Modifier
              .padding(bottom = 6.dp)
              .fillMaxWidth()
              .height(IntrinsicSize.Min)
              .clip(RoundedCornerShape(10.dp))
              .background(onBubble.copy(alpha = 0.14f))
              .testTag("quote-${message.messageId}")
          ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(onBubble.copy(alpha = 0.75f)))
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
              Text(
                quote.authorName ?: stringResource(R.string.you),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = onBubble,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                quote.body ?: stringResource(R.string.attachment),
                style = MaterialTheme.typography.labelSmall,
                color = onBubble.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
              )
            }
          }
        }
        Text(previewText(message), color = onBubble, style = MaterialTheme.typography.bodyMedium)
      }
    }

    if (message.reactions.isNotEmpty()) {
      ReactionChips(message.reactions, colors, modifier = Modifier.padding(horizontal = 8.dp).offset(y = (-4).dp))
    }
    if (showTime) {
      Text(
        timeText,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
      )
    }
  }
}

@Composable
private fun ReactionChips(reactions: List<WearReaction>, colors: BubbleColors, modifier: Modifier = Modifier) {
  Row(modifier = modifier.testTag("reaction-chips"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    reactions.take(4).forEach { reaction ->
      val chipModifier = Modifier
        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
        .let { if (reaction.includesMe) it.border(1.dp, colors.outgoing, RoundedCornerShape(10.dp)) else it }
        .padding(horizontal = 6.dp, vertical = 1.dp)
      Text(
        if (reaction.count > 1) "${reaction.emoji}${reaction.count}" else reaction.emoji,
        modifier = chipModifier,
        style = MaterialTheme.typography.labelSmall
      )
    }
  }
}

@Composable
fun previewText(message: WearMessage): String = when {
  message.body != null -> message.body!!
  message.hasAttachment -> stringResource(R.string.attachment)
  else -> ""
}
