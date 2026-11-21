/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear

import im.molly.wear.protocol.Command
import im.molly.wear.protocol.CommandResult
import im.molly.wear.protocol.Snapshot
import kotlinx.coroutines.flow.StateFlow

/** The watch side of the transport, behind an interface so [WatchController] is testable. */
interface PhoneLink {
  /** Latest snapshot published by the phone; null until one is known. */
  val snapshots: StateFlow<Snapshot?>

  suspend fun isPhoneReachable(): Boolean

  /**
   * Sends one command and suspends until the phone answers.
   * @throws PhoneUnreachableException if no phone with the bridge is reachable
   * @throws kotlinx.coroutines.TimeoutCancellationException if the phone doesn't answer in time
   */
  suspend fun send(command: Command): CommandResult
}

class PhoneUnreachableException : Exception("Phone not reachable")
