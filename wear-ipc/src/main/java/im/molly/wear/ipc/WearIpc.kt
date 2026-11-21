/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.ipc

import im.molly.wear.protocol.MollyGateway.HistoryPage
import im.molly.wear.protocol.MollyGateway.Outcome
import im.molly.wear.protocol.Snapshot
import kotlinx.serialization.json.Json

/** Names and encoding shared by Molly and the relay app. */
object WearIpc {
  /** Bump when [IMollyWearGateway] changes incompatibly. */
  const val IPC_VERSION = 2 // 2: sendText gained quoteMessageId

  const val PERMISSION = "im.molly.wear.permission.BRIDGE"

  /** Intent action of Molly's gateway service (bound by the relay with an explicit package). */
  const val ACTION_BIND_GATEWAY = "im.molly.wear.ipc.BIND_GATEWAY"

  /** Explicit broadcast from Molly to the relay: notifications, lock or thread state changed. */
  const val ACTION_STATE_CHANGED = "im.molly.wear.ipc.STATE_CHANGED"

  /** The relay's package is Molly's plus this suffix; the watch app uses the same package. */
  const val RELAY_PACKAGE_SUFFIX = ".wear"

  // Local IPC only, so no payload limit here: the bridge enforces the Data Layer limit itself.
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  fun encodeSnapshot(value: Snapshot): ByteArray = json.encodeToString(Snapshot.serializer(), value).encodeToByteArray()
  fun decodeSnapshot(bytes: ByteArray): Snapshot = json.decodeFromString(Snapshot.serializer(), bytes.decodeToString())

  fun encodeHistory(value: HistoryPage): ByteArray = json.encodeToString(HistoryPage.serializer(), value).encodeToByteArray()
  fun decodeHistory(bytes: ByteArray): HistoryPage = json.decodeFromString(HistoryPage.serializer(), bytes.decodeToString())

  fun outcomeToInt(outcome: Outcome): Int = outcome.ordinal

  /** Unknown values (a newer Molly) map to UNKNOWN_THREAD rather than crashing the relay. */
  fun outcomeFromInt(value: Int): Outcome = Outcome.entries.getOrElse(value) { Outcome.UNKNOWN_THREAD }
}
