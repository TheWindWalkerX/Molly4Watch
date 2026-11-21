/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.bridge

import im.molly.wear.protocol.MollyGateway
import im.molly.wear.protocol.MollyGateway.Outcome
import im.molly.wear.protocol.Command
import im.molly.wear.protocol.CommandResult
import im.molly.wear.protocol.ErrorCode
import im.molly.wear.protocol.WearCodec
import im.molly.wear.protocol.WearProtocolException
import im.molly.wear.protocol.validationError
import android.util.Log

/**
 * Turns one encoded watch command into exactly one [CommandResult].
 *
 * Order matters: decode, then the lock gate, then validation, then dispatch. While Molly is locked,
 * nothing reaches the gateway except [MollyGateway.isLocked].
 */
class CommandHandler(
  private val gateway: MollyGateway,
  /** Called after a command that changed state or asked for a snapshot, so the snapshot gets republished. */
  private val requestSnapshotPublish: () -> Unit
) {

  /** [handle], encoded. History pages that don't fit the payload limit lose their oldest messages and report hasMore. */
  fun handleToBytes(bytes: ByteArray): ByteArray {
    var result = handle(bytes)
    while (true) {
      try {
        return WearCodec.encodeResult(result)
      } catch (e: WearProtocolException) {
        val history = result as? CommandResult.History
        if (history == null || history.messages.isEmpty()) {
          Log.w(TAG, "Result too large to send", e)
          return WearCodec.encodeResult(CommandResult.Error(result.requestId, ErrorCode.INTERNAL, "result too large"))
        }
        result = history.copy(messages = history.messages.drop(1), hasMore = true)
      }
    }
  }

  fun handle(bytes: ByteArray): CommandResult {
    val command = try {
      WearCodec.decodeCommand(bytes)
    } catch (e: WearProtocolException) {
      Log.w(TAG, "Rejecting undecodable command: ${e.message}")
      return CommandResult.Error(UNKNOWN_REQUEST_ID, ErrorCode.INVALID_COMMAND, e.message)
    }

    if (gateway.isLocked()) {
      return CommandResult.Error(command.requestId, ErrorCode.LOCKED)
    }

    command.validationError()?.let { reason ->
      return CommandResult.Error(command.requestId, ErrorCode.INVALID_COMMAND, reason)
    }

    return try {
      dispatch(command)
    } catch (e: Exception) {
      // Never let a gateway failure crash Molly's process; report it to the watch instead.
      Log.w(TAG, "Command ${command::class.simpleName} failed", e)
      CommandResult.Error(command.requestId, ErrorCode.INTERNAL)
    }
  }

  private fun dispatch(command: Command): CommandResult = when (command) {
    is Command.SendText -> changed(command, gateway.sendText(command.threadId, command.body, command.quoteMessageId))
    is Command.React -> changed(command, gateway.react(command.threadId, command.messageId, command.emoji))
    is Command.RemoveReaction -> changed(command, gateway.removeReaction(command.threadId, command.messageId))
    is Command.MarkRead -> changed(command, gateway.markRead(command.threadId))
    is Command.FetchHistory -> {
      val page = gateway.loadHistory(command.threadId, command.offset, command.limit)
      if (page == null) {
        CommandResult.Error(command.requestId, ErrorCode.UNKNOWN_THREAD)
      } else {
        CommandResult.History(command.requestId, command.threadId, page.messages, page.hasMore)
      }
    }
    is Command.RequestSnapshot -> {
      requestSnapshotPublish()
      CommandResult.Ok(command.requestId)
    }
  }

  private fun changed(command: Command, outcome: Outcome): CommandResult = when (outcome) {
    Outcome.OK -> {
      requestSnapshotPublish()
      CommandResult.Ok(command.requestId)
    }
    Outcome.UNKNOWN_THREAD -> CommandResult.Error(command.requestId, ErrorCode.UNKNOWN_THREAD)
    Outcome.UNKNOWN_MESSAGE -> CommandResult.Error(command.requestId, ErrorCode.UNKNOWN_MESSAGE)
  }

  companion object {
    private const val TAG = "MollyWear/CommandHandler"

    /** requestId used when the command was too broken to read one. */
    const val UNKNOWN_REQUEST_ID = ""
  }
}
