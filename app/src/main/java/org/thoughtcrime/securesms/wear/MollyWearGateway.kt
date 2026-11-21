/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.wear // MOLLY-WEAR (new file)

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.util.AvatarUtil
import java.io.ByteArrayOutputStream
import im.molly.wear.protocol.MollyGateway
import im.molly.wear.protocol.MollyGateway.HistoryPage
import im.molly.wear.protocol.MollyGateway.Outcome
import im.molly.wear.protocol.Snapshot
import im.molly.wear.protocol.ThreadSummary
import im.molly.wear.protocol.WearLimits
import im.molly.wear.protocol.WearMessage
import im.molly.wear.protocol.WearQuote
import im.molly.wear.protocol.WearReaction
import im.molly.wear.protocol.truncateForWatch
import org.thoughtcrime.securesms.components.settings.app.chats.folders.ChatFolderRecord
import org.thoughtcrime.securesms.conversationlist.model.ConversationFilter
import org.thoughtcrime.securesms.database.MentionUtil
import org.thoughtcrime.securesms.database.MessageTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.ReactionRecord
import org.thoughtcrime.securesms.database.model.ThreadWithRecipient
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.mms.QuoteModel
import org.thoughtcrime.securesms.mms.SlideDeck
import org.thoughtcrime.securesms.notifications.MarkReadReceiver
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.service.KeyCachingService
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.util.MessageUtil

/**
 * Molly's side of the Wear bridge. The bridge calls everything except [isLocked] off the main thread and
 * only while Molly is unlocked.
 *
 * The watch pages history by "how many messages I already have", so offsets here count *visible*
 * messages: update/system rows and remote-deleted messages are skipped consistently everywhere.
 */
class MollyWearGateway(context: Context) : MollyGateway {

  private val context: Context = context.applicationContext

  override fun isLocked(): Boolean = KeyCachingService.isLocked()

  override fun loadSnapshot(now: Long): Snapshot {
    val threads = SignalDatabase.threads
    val allChats = ChatFolderRecord(folderType = ChatFolderRecord.FolderType.ALL)
    val records = ArrayList<ThreadWithRecipient>(WearLimits.MAX_SNAPSHOT_THREADS)

    // Same order as Molly's chat list: pinned chats first, then the rest by recent activity.
    for (pinned in listOf(true, false)) {
      val remaining = WearLimits.MAX_SNAPSHOT_THREADS - records.size
      if (remaining <= 0) break
      threads.getUnarchivedConversationList(ConversationFilter.OFF, pinned, 0, remaining.toLong(), allChats).use { cursor ->
        val reader = threads.readerFor(cursor)
        while (records.size < WearLimits.MAX_SNAPSHOT_THREADS) {
          records += reader.getNext() ?: break
        }
      }
    }

    val summaries = records.map { thread ->
      val recipient = thread.recipient
      ThreadSummary(
        threadId = thread.threadId,
        title = recipient.getDisplayName(context).truncateForWatch(MAX_TITLE_CHARS),
        isGroup = recipient.isGroup,
        unreadCount = thread.unreadCount,
        lastActivityAt = thread.date,
        // Short bodies in the snapshot so all threads fit; full bodies come with history pages.
        recentMessages = loadVisible(thread.threadId, 0, WearLimits.MAX_MESSAGES_PER_THREAD, SNAPSHOT_BODY_CHARS).messages,
        // The chat's bubble color as set in Molly (gradients collapse to one representative color).
        chatColor = runCatching { recipient.chatColors.asSingleColor() }.getOrNull(),
        avatar = avatarFor(recipient)
      )
    }

    return Snapshot(generatedAt = now, locked = false, unreadThreadCount = threads.getUnreadThreadCount(), threads = summaries)
  }

  override fun loadHistory(threadId: Long, offset: Int, limit: Int): HistoryPage? {
    if (SignalDatabase.threads.getThreadRecord(threadId) == null) return null
    return loadVisible(threadId, offset, limit, WearLimits.MAX_BODY_CHARS)
  }

  override fun sendText(threadId: Long, body: String, quoteMessageId: Long?): Outcome {
    val recipient = SignalDatabase.threads.getRecipientForThreadId(threadId) ?: return Outcome.UNKNOWN_THREAD

    val quote = if (quoteMessageId != null) {
      findMessage(threadId, quoteMessageId)?.let { return it }
      quoteOf(SignalDatabase.messages.getMessageRecord(quoteMessageId))
    } else {
      null
    }

    // Same path as replying from a notification (RemoteReplyReceiver), plus an optional quote.
    val split = MessageUtil.getSplitMessage(context, body)
    val slideDeck = split.textSlide.orElse(null)?.let { SlideDeck().apply { addSlide(it) } }
    val reply = OutgoingMessage.quickReply(recipient, slideDeck, split.body, null).copy(outgoingQuote = quote)
    val sentThreadId = MessageSender.send(context, reply, threadId, MessageSender.SendType.SIGNAL, null, null)

    markThreadRead(sentThreadId)
    return Outcome.OK
  }

  override fun react(threadId: Long, messageId: Long, emoji: String): Outcome {
    findMessage(threadId, messageId)?.let { return it }
    MessageSender.sendNewReaction(context, MessageId(messageId), emoji)
    return Outcome.OK
  }

  override fun removeReaction(threadId: Long, messageId: Long): Outcome {
    findMessage(threadId, messageId)?.let { return it }
    val self = Recipient.self().id
    val mine: ReactionRecord = SignalDatabase.reactions.getReactions(MessageId(messageId)).firstOrNull { it.author == self }
      ?: return Outcome.OK
    MessageSender.sendReactionRemoval(context, MessageId(messageId), mine)
    return Outcome.OK
  }

  override fun markRead(threadId: Long): Outcome {
    if (SignalDatabase.threads.getThreadRecord(threadId) == null) return Outcome.UNKNOWN_THREAD
    markThreadRead(threadId)
    return Outcome.OK
  }

  /** Null if the message exists in that thread, otherwise the failure to report. */
  private fun findMessage(threadId: Long, messageId: Long): Outcome? {
    if (SignalDatabase.threads.getThreadRecord(threadId) == null) return Outcome.UNKNOWN_THREAD
    val record = SignalDatabase.messages.getMessageRecordOrNull(messageId)
    return if (record == null || record.threadId != threadId || !record.isVisibleOnWatch()) Outcome.UNKNOWN_MESSAGE else null
  }

  /**
   * The chat's avatar as Molly shows it in notifications (blur setting respected; generated avatar when
   * there's no photo), as a small base64 WebP. Cached briefly: snapshots are republished often.
   */
  private fun avatarFor(recipient: Recipient): String? {
    val key = recipient.id
    val now = System.currentTimeMillis()
    avatarCache[key]?.takeIf { now - it.first < AVATAR_CACHE_MS }?.let { return it.second }
    val encoded = runCatching {
      val bitmap = AvatarUtil.getBitmapForNotification(context, recipient, WearLimits.AVATAR_SIZE_PX)
      val out = ByteArrayOutputStream()
      @Suppress("DEPRECATION")
      val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
      bitmap.compress(format, 75, out)
      out.toByteArray().takeIf { it.size <= WearLimits.MAX_AVATAR_BYTES }?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    }.onFailure { Log.w(TAG, "Avatar failed", it) }.getOrNull()
    avatarCache[key] = now to encoded
    return encoded
  }

  /** A quote of [record], built like the phone's swipe-to-reply (InputPanel / StoryDirectReplyRepository). */
  private fun quoteOf(record: MessageRecord): QuoteModel = QuoteModel(
    id = record.dateSent,
    author = record.fromRecipient.id,
    text = record.body,
    isOriginalMissing = false,
    attachment = SignalDatabase.attachments.getAttachmentsForMessage(record.id).firstOrNull(),
    mentions = SignalDatabase.mentions.getMentionsForMessage(record.id),
    type = QuoteModel.Type.NORMAL,
    bodyRanges = record.messageRanges
  )

  private fun markThreadRead(threadId: Long) {
    val marked = SignalDatabase.threads.setRead(threadId)
    AppDependencies.messageNotifier.updateNotification(context)
    MarkReadReceiver.process(marked)
  }

  /**
   * Visible messages [offset] ..< offset + [limit], counted from the newest. Returned oldest first,
   * which is what [ThreadSummary.recentMessages] and history pages use.
   */
  private fun loadVisible(threadId: Long, offset: Int, limit: Int, bodyChars: Int): HistoryPage {
    val picked = ArrayList<MessageRecord>(limit)
    var skipped = 0
    var hasMore = false

    SignalDatabase.messages.getConversation(threadId).use { cursor ->
      val reader = MessageTable.mmsReaderFor(cursor)
      while (true) {
        val record = reader.getNext() ?: break
        if (!record.isVisibleOnWatch()) continue
        when {
          skipped < offset -> skipped++
          picked.size < limit -> picked += record
          else -> {
            hasMore = true
            break
          }
        }
      }
    }

    if (picked.isEmpty()) return HistoryPage(emptyList(), hasMore = false)

    val ids = picked.map { it.id }
    val reactions = SignalDatabase.reactions.getReactionsForMessages(ids)
    val attachments = SignalDatabase.attachments.getAttachmentsForMessages(ids)
    val self = Recipient.self().id

    val messages = picked.asReversed().map { record ->
      WearMessage(
        messageId = record.id,
        threadId = threadId,
        isOutgoing = record.isOutgoing,
        authorName = if (!record.isOutgoing && record.fromRecipient.id != self && isGroupThread(record)) {
          record.fromRecipient.getDisplayName(context).truncateForWatch(MAX_TITLE_CHARS)
        } else {
          null
        },
        body = displayBody(record)?.truncateForWatch(bodyChars),
        hasAttachment = record.isViewOnce || !attachments[record.id].isNullOrEmpty(),
        sentAt = record.dateSent,
        reactions = summarize(reactions[record.id].orEmpty(), self),
        quote = quoteFor(record, self)
      )
    }
    return HistoryPage(messages, hasMore)
  }

  private fun quoteFor(record: MessageRecord, self: RecipientId): WearQuote? {
    val quote = (record as? MmsMessageRecord)?.quote ?: return null
    return WearQuote(
      authorName = if (quote.author == self) null else Recipient.resolved(quote.author).getDisplayName(context).truncateForWatch(MAX_TITLE_CHARS),
      body = if (quote.isOriginalMissing) null else quote.displayText?.toString()?.takeIf { it.isNotBlank() }?.truncateForWatch(WearLimits.MAX_QUOTE_CHARS)
    )
  }

  private fun isGroupThread(record: MessageRecord): Boolean = record.toRecipient.isGroup

  /** The body as Molly shows it, with mention placeholders replaced by names. Null when there's no text. */
  private fun displayBody(record: MessageRecord): String? {
    if (record.isViewOnce) return null
    val body = MentionUtil.updateBodyWithDisplayNames(context, record).body?.toString() ?: record.body
    return body.takeIf { it.isNotBlank() }
  }

  private fun summarize(reactions: List<ReactionRecord>, self: RecipientId): List<WearReaction> =
    reactions.groupBy { it.emoji }
      .map { (emoji, list) -> WearReaction(emoji = emoji, count = list.size, includesMe = list.any { it.author == self }) }
      .sortedByDescending { it.count }

  private fun MessageRecord.isVisibleOnWatch(): Boolean = !isUpdate && !isRemoteDelete

  private val avatarCache = java.util.concurrent.ConcurrentHashMap<RecipientId, Pair<Long, String?>>()

  companion object {
    private val TAG = Log.tag(MollyWearGateway::class.java)
    private const val AVATAR_CACHE_MS = 10 * 60 * 1000L
    private const val MAX_TITLE_CHARS = 100
    private const val SNAPSHOT_BODY_CHARS = 300
  }
}
