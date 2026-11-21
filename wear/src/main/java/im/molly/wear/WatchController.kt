/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear

import im.molly.wear.protocol.Command
import im.molly.wear.protocol.CommandResult
import im.molly.wear.protocol.ErrorCode
import im.molly.wear.protocol.Snapshot
import im.molly.wear.protocol.WearMessage
import im.molly.wear.protocol.WearTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface HomeState {
  data object Connecting : HomeState
  data object NoPhone : HomeState
  data object Locked : HomeState
  data class Ready(val snapshot: Snapshot) : HomeState
}

data class ThreadState(
  val threadId: Long,
  val title: String,
  /** Oldest first. */
  val messages: List<WearMessage> = emptyList(),
  val hasMore: Boolean = false,
  val loading: Boolean = false,
  val isGroup: Boolean = false,
  /** Molly's bubble color for this chat (ARGB), or null for the default. */
  val chatColor: Int? = null
)

/** Outcome of the last user action, for a short status line in the UI. */
sealed interface ActionStatus {
  data object Idle : ActionStatus
  data object Sending : ActionStatus
  data class Failed(val reason: String) : ActionStatus
}

/**
 * All watch-side state and behaviour, independent of Android UI. Messages are only held in memory:
 * nothing is written to disk by this app, and everything is dropped as soon as the phone reports
 * Molly locked.
 */
class WatchController(
  private val link: PhoneLink,
  private val scope: CoroutineScope,
  private val newRequestId: () -> String = { UUID.randomUUID().toString() }
) {
  private val _home = MutableStateFlow<HomeState>(HomeState.Connecting)
  val home: StateFlow<HomeState> = _home.asStateFlow()

  private val _thread = MutableStateFlow<ThreadState?>(null)
  val thread: StateFlow<ThreadState?> = _thread.asStateFlow()

  /** The phone's Material 3 palette. Kept while locked: it's not message content. */
  private val _theme = MutableStateFlow<WearTheme?>(null)
  val theme: StateFlow<WearTheme?> = _theme.asStateFlow()

  private val _status = MutableStateFlow<ActionStatus>(ActionStatus.Idle)
  val status: StateFlow<ActionStatus> = _status.asStateFlow()

  /** The message being replied to (swipe to reply or the "Reply" action); the UI opens the input for it. */
  private val _replyTarget = MutableStateFlow<WearMessage?>(null)
  val replyTarget: StateFlow<WearMessage?> = _replyTarget.asStateFlow()

  fun startReply(message: WearMessage) {
    _replyTarget.value = message
  }

  /** Called with the text from the reply input (null if the user cancelled). */
  fun finishReply(text: String?) {
    val target = _replyTarget.value ?: return
    _replyTarget.value = null
    if (!text.isNullOrBlank()) sendText(text, quoteMessageId = target.messageId)
  }

  fun start() {
    scope.launch { link.snapshots.collect { onSnapshot(it) } }
    refresh()
  }

  /** Ask the phone for a fresh snapshot; shows "no phone" if it can't be reached and nothing is known yet. */
  fun refresh() {
    scope.launch {
      if (!link.isPhoneReachable()) {
        if (_home.value == HomeState.Connecting) _home.value = HomeState.NoPhone
        return@launch
      }
      if (_home.value == HomeState.NoPhone) _home.value = HomeState.Connecting
      execute(Command.RequestSnapshot(newRequestId()))
    }
  }

  private fun onSnapshot(snapshot: Snapshot?) {
    if (snapshot == null) return
    snapshot.theme?.let { _theme.value = it }
    if (snapshot.locked) {
      lockDown()
      return
    }
    _home.value = HomeState.Ready(snapshot)
    // Fold any newer messages for the open thread into it.
    val open = _thread.value ?: return
    val summary = snapshot.threads.firstOrNull { it.threadId == open.threadId } ?: return
    _thread.value = open.copy(messages = merge(open.messages, summary.recentMessages), chatColor = summary.chatColor, title = summary.title)
  }

  private fun lockDown() {
    _home.value = HomeState.Locked
    _thread.value = null
    _replyTarget.value = null
  }

  fun openThread(threadId: Long) {
    val summary = (home.value as? HomeState.Ready)?.snapshot?.threads?.firstOrNull { it.threadId == threadId }
    _thread.value = ThreadState(
      threadId = threadId,
      title = summary?.title ?: "",
      messages = summary?.recentMessages ?: emptyList(),
      loading = true,
      isGroup = summary?.isGroup ?: false,
      chatColor = summary?.chatColor
    )
    _status.value = ActionStatus.Idle
    loadPage(threadId, offset = 0)
  }

  fun closeThread() {
    _thread.value = null
    _replyTarget.value = null
  }

  fun loadOlder() {
    val open = _thread.value ?: return
    if (open.loading || !open.hasMore) return
    _thread.value = open.copy(loading = true)
    loadPage(open.threadId, offset = open.messages.size)
  }

  private fun loadPage(threadId: Long, offset: Int) {
    scope.launch {
      val result = execute(Command.FetchHistory(newRequestId(), threadId, offset, PAGE_SIZE))
      val open = _thread.value?.takeIf { it.threadId == threadId } ?: return@launch
      _thread.value = if (result is CommandResult.History) {
        open.copy(messages = merge(open.messages, result.messages), hasMore = result.hasMore, loading = false)
      } else {
        open.copy(loading = false)
      }
    }
  }

  /** [quoteMessageId]: reply to that message as a quote (swipe to reply / "Reply" action). */
  fun sendText(body: String, quoteMessageId: Long? = null) {
    val open = _thread.value ?: return
    if (body.isBlank()) return
    act(Command.SendText(newRequestId(), open.threadId, body.trim(), quoteMessageId))
  }

  fun react(messageId: Long, emoji: String) {
    val open = _thread.value ?: return
    act(Command.React(newRequestId(), open.threadId, messageId, emoji))
  }

  fun removeReaction(messageId: Long) {
    val open = _thread.value ?: return
    act(Command.RemoveReaction(newRequestId(), open.threadId, messageId))
  }

  fun markRead() {
    val open = _thread.value ?: return
    act(Command.MarkRead(newRequestId(), open.threadId))
  }

  /** State-changing command: show progress, then re-read the newest page so the thread reflects it. */
  private fun act(command: Command) {
    _status.value = ActionStatus.Sending
    scope.launch {
      val result = execute(command)
      if (result is CommandResult.Ok) {
        _status.value = ActionStatus.Idle
        _thread.value?.let { loadPage(it.threadId, offset = 0) }
      }
    }
  }

  /** Sends a command, translating every failure into UI state. Returns null on failure. */
  private suspend fun execute(command: Command): CommandResult? {
    val result = try {
      link.send(command)
    } catch (e: PhoneUnreachableException) {
      fail(command, "phone not reachable")
      if (_home.value == HomeState.Connecting) _home.value = HomeState.NoPhone
      return null
    } catch (e: TimeoutCancellationException) {
      fail(command, "phone didn't answer")
      return null
    }
    if (result is CommandResult.Error) {
      if (result.code == ErrorCode.LOCKED) lockDown()
      fail(command, describe(result.code))
      return null
    }
    return result
  }

  private fun fail(command: Command, reason: String) {
    // Background refreshes fail quietly; user actions report why.
    if (command !is Command.RequestSnapshot && command !is Command.FetchHistory) {
      _status.value = ActionStatus.Failed(reason)
    }
  }

  companion object {
    const val PAGE_SIZE = 20

    fun describe(code: ErrorCode): String = when (code) {
      ErrorCode.LOCKED -> "Molly is locked"
      ErrorCode.INVALID_COMMAND -> "not allowed"
      ErrorCode.UNKNOWN_THREAD -> "chat not found"
      ErrorCode.UNKNOWN_MESSAGE -> "message not found"
      ErrorCode.INTERNAL -> "error on phone"
    }

    /** Union by messageId (newer copy wins, e.g. updated reactions), ordered oldest first. */
    fun merge(existing: List<WearMessage>, incoming: List<WearMessage>): List<WearMessage> {
      val byId = LinkedHashMap<Long, WearMessage>()
      existing.forEach { byId[it.messageId] = it }
      incoming.forEach { byId[it.messageId] = it }
      return byId.values.sortedWith(compareBy({ it.sentAt }, { it.messageId }))
    }
  }
}
