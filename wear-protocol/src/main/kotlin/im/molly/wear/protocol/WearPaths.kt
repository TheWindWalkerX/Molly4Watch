/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.protocol

/**
 * Data Layer paths and capability names. The major protocol version is part of every path, so a
 * phone and a watch on incompatible versions simply never see each other's traffic.
 */
object WearPaths {
  private const val ROOT = "/molly/v$PROTOCOL_VERSION"

  /** DataItem, phone -> watch. Latest [Snapshot]. Persistent, so the watch has it after reconnecting. */
  const val SNAPSHOT = "$ROOT/snapshot"

  /** Message, watch -> phone. One [Command]. */
  const val COMMAND = "$ROOT/command"

  /** Message, phone -> watch. One [CommandResult], matched to its command by requestId. */
  const val RESULT = "$ROOT/result"

  /** Capability advertised by the phone app (declared in :wear-bridge's res/values/wear.xml). */
  const val CAPABILITY_PHONE = "molly_wear_bridge_v$PROTOCOL_VERSION"

  /** Capability advertised by the watch app (declared in :wear's res/values/wear.xml). */
  const val CAPABILITY_WATCH = "molly_wear_app_v$PROTOCOL_VERSION"
}
