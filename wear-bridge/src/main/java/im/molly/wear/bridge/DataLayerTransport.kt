/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.bridge

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import im.molly.wear.protocol.WearPaths
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * [WearTransport] over the Wear OS Data Layer. Every call tolerates Play services being missing
 * (e.g. GrapheneOS without sandboxed Play): it logs and does nothing, and never throws into Molly.
 */
class DataLayerTransport(context: Context) : WearTransport {
  private val appContext = context.applicationContext

  @Volatile private var cachedHasWatch: Boolean = false
  @Volatile private var cachedAt: Long = 0

  override fun hasWatch(): Boolean {
    val now = System.currentTimeMillis()
    if (now - cachedAt < CAPABILITY_CACHE_MS) return cachedHasWatch

    cachedHasWatch = try {
      val info = Tasks.await(
        Wearable.getCapabilityClient(appContext).getCapability(WearPaths.CAPABILITY_WATCH, CapabilityClient.FILTER_ALL),
        TIMEOUT_SECONDS,
        TimeUnit.SECONDS
      )
      info.nodes.isNotEmpty()
    } catch (e: Exception) {
      Log.i(TAG, "Watch capability lookup failed (Play services missing or no watch): ${e.javaClass.simpleName}")
      false
    }
    cachedAt = now
    return cachedHasWatch
  }

  override fun putSnapshot(bytes: ByteArray) {
    try {
      val request = PutDataRequest.create(WearPaths.SNAPSHOT).setData(bytes).setUrgent()
      Wearable.getDataClient(appContext).putDataItem(request)
        .addOnFailureListener { Log.w(TAG, "putDataItem failed: ${it.javaClass.simpleName}") }
    } catch (e: Exception) {
      Log.w(TAG, "putDataItem threw: ${e.javaClass.simpleName}")
    }
  }

  override fun sendResult(nodeId: String, bytes: ByteArray) {
    try {
      Wearable.getMessageClient(appContext).sendMessage(nodeId, WearPaths.RESULT, bytes)
        .addOnFailureListener { Log.w(TAG, "sendMessage failed: ${it.javaClass.simpleName}") }
    } catch (e: Exception) {
      Log.w(TAG, "sendMessage threw: ${e.javaClass.simpleName}")
    }
  }

  companion object {
    private const val TAG = "MollyWear/DataLayerTransport"
    private const val CAPABILITY_CACHE_MS = 60_000L
    private const val TIMEOUT_SECONDS = 5L
  }
}
