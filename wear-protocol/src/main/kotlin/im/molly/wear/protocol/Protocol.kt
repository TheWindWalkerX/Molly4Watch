/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Major protocol version. Bump it for any change an older peer can't safely ignore.
 * Adding optional fields (with defaults) does NOT need a bump: decoders ignore unknown keys.
 */
const val PROTOCOL_VERSION = 1

/** Size and count limits shared by both sides. */
object WearLimits {
  /** The Data Layer rejects payloads around 100 KB. Stay well under that. */
  const val MAX_PAYLOAD_BYTES = 90 * 1024
  const val MAX_SNAPSHOT_THREADS = 10
  const val MAX_MESSAGES_PER_THREAD = 5
  const val MAX_BODY_CHARS = 1000
  const val MAX_OUTGOING_BODY_CHARS = 2000
  const val MAX_EMOJI_CHARS = 32
  const val MAX_HISTORY_PAGE = 50
  const val MAX_QUOTE_CHARS = 120
  const val MAX_AVATAR_BYTES = 4 * 1024
  const val AVATAR_SIZE_PX = 48
}

// ---- Phone -> watch state ----

@Serializable
data class Snapshot(
  val generatedAt: Long,
  /** True while Molly is locked. The phone then sends no threads at all, and the watch shows "Molly locked". */
  val locked: Boolean,
  val unreadThreadCount: Long,
  val threads: List<ThreadSummary> = emptyList(),
  /** The phone's Material 3 (Material You) palette, so the watch matches it. Null: watch defaults. */
  val theme: WearTheme? = null
) {
  companion object {
    fun locked(now: Long) = Snapshot(generatedAt = now, locked = true, unreadThreadCount = 0)
  }
}

@Serializable
data class ThreadSummary(
  val threadId: Long,
  val title: String,
  val isGroup: Boolean,
  val unreadCount: Int,
  val lastActivityAt: Long,
  /** Newest last. At most [WearLimits.MAX_MESSAGES_PER_THREAD]. */
  val recentMessages: List<WearMessage> = emptyList(),
  /** The chat's bubble color in Molly, as ARGB. Null: the watch uses its default color. */
  val chatColor: Int? = null,
  /** Small round avatar (base64 WebP, at most [WearLimits.MAX_AVATAR_BYTES] before encoding). */
  val avatar: String? = null
)

/** Dark-scheme Material 3 roles as ARGB, from the phone's dynamic (Material You) palette. */
@Serializable
data class WearTheme(
  val primary: Int,
  val onPrimary: Int,
  val primaryContainer: Int,
  val onPrimaryContainer: Int,
  val secondary: Int,
  val onSecondary: Int,
  val secondaryContainer: Int,
  val onSecondaryContainer: Int,
  val tertiary: Int,
  val onTertiary: Int,
  val tertiaryContainer: Int,
  val onTertiaryContainer: Int,
  val surfaceContainerLow: Int,
  val surfaceContainer: Int,
  val surfaceContainerHigh: Int,
  val onSurface: Int,
  val onSurfaceVariant: Int,
  val outline: Int,
  val outlineVariant: Int
)

@Serializable
data class WearMessage(
  val messageId: Long,
  val threadId: Long,
  val isOutgoing: Boolean,
  /** Sender display name in group threads; null for 1:1 and outgoing messages. */
  val authorName: String? = null,
  /** Null for media-only messages. Truncated to [WearLimits.MAX_BODY_CHARS]. */
  val body: String? = null,
  val hasAttachment: Boolean = false,
  val sentAt: Long,
  val reactions: List<WearReaction> = emptyList(),
  /** The message this one quotes (a reply), if any. */
  val quote: WearQuote? = null
)

@Serializable
data class WearQuote(
  /** Display name of the quoted message's author; null when it was this device's user. */
  val authorName: String? = null,
  /** Truncated to [WearLimits.MAX_QUOTE_CHARS]. Null for media-only or missing originals. */
  val body: String? = null
)

@Serializable
data class WearReaction(
  val emoji: String,
  val count: Int,
  /** Whether this device's user is one of the reactors (so the watch can offer "remove"). */
  val includesMe: Boolean
)

// ---- Watch -> phone commands ----

@Serializable
sealed interface Command {
  /** Chosen by the watch, echoed in the [CommandResult]. */
  val requestId: String

  @Serializable
  @SerialName("send_text")
  data class SendText(
    override val requestId: String,
    val threadId: Long,
    val body: String,
    /** Send as a quoted reply to this message (swipe to reply). Null: a plain message. */
    val quoteMessageId: Long? = null
  ) : Command

  @Serializable
  @SerialName("react")
  data class React(override val requestId: String, val threadId: Long, val messageId: Long, val emoji: String) : Command

  /** Removes this user's own reaction from the message, whatever emoji it was. */
  @Serializable
  @SerialName("remove_reaction")
  data class RemoveReaction(override val requestId: String, val threadId: Long, val messageId: Long) : Command

  @Serializable
  @SerialName("mark_read")
  data class MarkRead(override val requestId: String, val threadId: Long) : Command

  /** Page backwards through a thread. [offset] counts from the newest message. */
  @Serializable
  @SerialName("fetch_history")
  data class FetchHistory(override val requestId: String, val threadId: Long, val offset: Int, val limit: Int) : Command

  /** Ask the phone to republish the snapshot now (e.g. when the watch app opens). */
  @Serializable
  @SerialName("request_snapshot")
  data class RequestSnapshot(override val requestId: String) : Command
}

/** Returns null if the command is well-formed, otherwise a human-readable reason. */
fun Command.validationError(): String? = when (this) {
  is Command.SendText -> when {
    body.isBlank() -> "body is blank"
    body.length > WearLimits.MAX_OUTGOING_BODY_CHARS -> "body longer than ${WearLimits.MAX_OUTGOING_BODY_CHARS} chars"
    threadId <= 0 -> "invalid threadId"
    quoteMessageId != null && quoteMessageId <= 0 -> "invalid quoteMessageId"
    else -> null
  }
  is Command.React -> when {
    emoji.isBlank() -> "emoji is blank"
    emoji.length > WearLimits.MAX_EMOJI_CHARS -> "emoji too long"
    threadId <= 0 || messageId <= 0 -> "invalid id"
    else -> null
  }
  is Command.RemoveReaction -> if (threadId <= 0 || messageId <= 0) "invalid id" else null
  is Command.MarkRead -> if (threadId <= 0) "invalid threadId" else null
  is Command.FetchHistory -> when {
    threadId <= 0 -> "invalid threadId"
    offset < 0 -> "negative offset"
    limit !in 1..WearLimits.MAX_HISTORY_PAGE -> "limit must be 1..${WearLimits.MAX_HISTORY_PAGE}"
    else -> null
  }
  is Command.RequestSnapshot -> null
}

// ---- Phone -> watch command results ----

@Serializable
sealed interface CommandResult {
  val requestId: String

  @Serializable
  @SerialName("ok")
  data class Ok(override val requestId: String) : CommandResult

  @Serializable
  @SerialName("history")
  data class History(
    override val requestId: String,
    val threadId: Long,
    /** Newest last. */
    val messages: List<WearMessage>,
    val hasMore: Boolean
  ) : CommandResult

  @Serializable
  @SerialName("error")
  data class Error(override val requestId: String, val code: ErrorCode, val detail: String? = null) : CommandResult
}

@Serializable
enum class ErrorCode {
  /** Molly is locked; nothing was done. */
  LOCKED,
  INVALID_COMMAND,
  UNKNOWN_THREAD,
  UNKNOWN_MESSAGE,
  INTERNAL
}

/**
 * Truncates a message body to the protocol limit, marking the cut with an ellipsis.
 * Never splits a surrogate pair, so an emoji at the cut point isn't corrupted.
 */
fun String.truncateForWatch(max: Int = WearLimits.MAX_BODY_CHARS): String {
  if (length <= max) return this
  var end = max - 1
  if (end > 0 && this[end - 1].isHighSurrogate()) end--
  return substring(0, end) + "…"
}
