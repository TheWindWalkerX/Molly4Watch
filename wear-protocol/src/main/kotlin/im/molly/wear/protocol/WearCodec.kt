/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Encodes protocol objects as UTF-8 JSON wrapped in a version envelope: `{"v":1,"body":{...}}`.
 *
 * Decoding ignores unknown keys, so peers can add optional fields without a version bump.
 * A different major version is rejected with [WearProtocolException] instead of being misread.
 */
object WearCodec {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    classDiscriminator = "type"
  }

  fun encodeSnapshot(value: Snapshot): ByteArray = encode(Snapshot.serializer(), value)
  fun decodeSnapshot(bytes: ByteArray): Snapshot = decode(Snapshot.serializer(), bytes)

  fun encodeCommand(value: Command): ByteArray = encode(Command.serializer(), value)
  fun decodeCommand(bytes: ByteArray): Command = decode(Command.serializer(), bytes)

  fun encodeResult(value: CommandResult): ByteArray = encode(CommandResult.serializer(), value)
  fun decodeResult(bytes: ByteArray): CommandResult = decode(CommandResult.serializer(), bytes)

  private fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray {
    val envelope = buildJsonObject {
      put("v", PROTOCOL_VERSION)
      put("body", json.encodeToJsonElement(serializer, value))
    }
    val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
    if (bytes.size > WearLimits.MAX_PAYLOAD_BYTES) {
      throw WearProtocolException("payload is ${bytes.size} bytes, limit ${WearLimits.MAX_PAYLOAD_BYTES}")
    }
    return bytes
  }

  private fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T {
    if (bytes.size > WearLimits.MAX_PAYLOAD_BYTES) {
      throw WearProtocolException("payload is ${bytes.size} bytes, limit ${WearLimits.MAX_PAYLOAD_BYTES}")
    }
    try {
      val envelope: JsonObject = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
      val version = envelope["v"]?.jsonPrimitive?.int ?: throw WearProtocolException("missing version")
      if (version != PROTOCOL_VERSION) {
        throw WearProtocolException("unsupported protocol version $version (this side speaks $PROTOCOL_VERSION)")
      }
      val body = envelope["body"] ?: throw WearProtocolException("missing body")
      return json.decodeFromJsonElement(serializer, body)
    } catch (e: SerializationException) {
      throw WearProtocolException("malformed payload: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
      // jsonObject / int accessors throw IllegalArgumentException on wrong JSON shapes.
      if (e is WearProtocolException) throw e
      throw WearProtocolException("malformed payload: ${e.message}", e)
    }
  }
}

class WearProtocolException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
