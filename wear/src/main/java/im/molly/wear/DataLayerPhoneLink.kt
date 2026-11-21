/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear

import android.content.Context
import android.os.HandlerThread
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import im.molly.wear.protocol.Command
import im.molly.wear.protocol.CommandResult
import im.molly.wear.protocol.Snapshot
import im.molly.wear.protocol.WearCodec
import im.molly.wear.protocol.WearPaths
import im.molly.wear.protocol.WearProtocolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * [PhoneLink] over the Wear OS Data Layer. The snapshot arrives as a DataItem at
 * [WearPaths.SNAPSHOT]; commands go out as messages and their results come back as messages
 * matched by requestId. Play services only connects this app to the same-signed app on the phone.
 */
class DataLayerPhoneLink(context: Context) : PhoneLink, DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {
  // Listener callbacks (and the JSON decode inside them) run on this thread, not the UI thread.
  private val callbackThread = HandlerThread("MollyWearLink").apply { start() }
  private val options = Wearable.WearableOptions.Builder().setLooper(callbackThread.looper).build()
  private val dataClient = Wearable.getDataClient(context, options)
  private val messageClient = Wearable.getMessageClient(context, options)
  private val capabilityClient = Wearable.getCapabilityClient(context, options)

  private val latest = MutableStateFlow<Snapshot?>(null)
  override val snapshots: StateFlow<Snapshot?> = latest.asStateFlow()

  private val pending = ConcurrentHashMap<String, CompletableDeferred<CommandResult>>()

  fun attach() {
    dataClient.addListener(this)
    messageClient.addListener(this)
  }

  fun detach() {
    dataClient.removeListener(this)
    messageClient.removeListener(this)
    callbackThread.quitSafely()
    pending.values.forEach { it.cancel() }
    pending.clear()
  }

  /** Reads the snapshot already stored on the watch, so the app has content before the phone answers. */
  suspend fun loadStoredSnapshot() {
    try {
      val buffer = dataClient.dataItems.await()
      try {
        buffer.firstOrNull { it.uri.path == WearPaths.SNAPSHOT }?.data?.let(::accept)
      } finally {
        buffer.release()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't read stored snapshot: ${e.javaClass.simpleName}")
    }
  }

  override fun onDataChanged(events: DataEventBuffer) {
    // The buffer is released after this callback returns, so decode inside it.
    for (event in events) {
      if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WearPaths.SNAPSHOT) {
        event.dataItem.data?.let(::accept)
      }
    }
  }

  private fun accept(bytes: ByteArray) {
    try {
      latest.value = WearCodec.decodeSnapshot(bytes)
    } catch (e: WearProtocolException) {
      Log.w(TAG, "Ignoring undecodable snapshot: ${e.message}")
    }
  }

  override fun onMessageReceived(event: MessageEvent) {
    if (event.path != WearPaths.RESULT) return
    try {
      val result = WearCodec.decodeResult(event.data)
      pending.remove(result.requestId)?.complete(result)
    } catch (e: WearProtocolException) {
      Log.w(TAG, "Ignoring undecodable result: ${e.message}")
    }
  }

  override suspend fun isPhoneReachable(): Boolean = phoneNodeId() != null

  private suspend fun phoneNodeId(): String? = try {
    val nodes = capabilityClient.getCapability(WearPaths.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes
    (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
  } catch (e: Exception) {
    Log.w(TAG, "Capability lookup failed: ${e.javaClass.simpleName}")
    null
  }

  override suspend fun send(command: Command): CommandResult {
    val nodeId = phoneNodeId() ?: throw PhoneUnreachableException()
    val answer = CompletableDeferred<CommandResult>()
    pending[command.requestId] = answer
    try {
      messageClient.sendMessage(nodeId, WearPaths.COMMAND, WearCodec.encodeCommand(command)).await()
      return withTimeout(RESULT_TIMEOUT_MS) { answer.await() }
    } catch (e: com.google.android.gms.common.api.ApiException) {
      throw PhoneUnreachableException()
    } finally {
      pending.remove(command.requestId)
    }
  }

  companion object {
    private const val TAG = "MollyWear/PhoneLink"
    private const val RESULT_TIMEOUT_MS = 15_000L
  }
}
