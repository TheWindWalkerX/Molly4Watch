/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.bridge

/** The phone side of the transport, kept behind an interface so the bridge logic can be tested without Play services. */
interface WearTransport {
  /** Whether a watch running the companion app is known. Blocking; never call it on the main thread. */
  fun hasWatch(): Boolean

  /** Replace the published snapshot. */
  fun putSnapshot(bytes: ByteArray)

  /** Send one command result to the watch node that sent the command. */
  fun sendResult(nodeId: String, bytes: ByteArray)
}
