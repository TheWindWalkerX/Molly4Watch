/*
 * Copyright 2026 Molly Wear companion contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package im.molly.wear.bridge

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import im.molly.wear.protocol.WearCodec
import org.junit.Test
import java.util.concurrent.Executor

class SnapshotPublisherTest {
  private val gateway = FakeGateway()
  private val transport = FakeTransport()

  /** Runs tasks only when asked, so coalescing is observable. */
  private class ManualExecutor : Executor {
    val queue = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) {
      queue += command
    }
    fun runAll() {
      while (queue.isNotEmpty()) queue.removeFirst().run()
    }
  }

  private val executor = ManualExecutor()
  private val publisher = SnapshotPublisher(gateway, transport, executor, clock = { 1000L })

  @Test
  fun `unlocked with a watch publishes thread content`() {
    publisher.publishNow()
    val snapshot = WearCodec.decodeSnapshot(transport.snapshots.single())
    assertThat(snapshot.locked).isFalse()
    assertThat(snapshot.threads.map { it.threadId }).containsExactly(7L)
  }

  @Test
  fun `locked publishes a content-free locked snapshot and never reads Molly`() {
    gateway.locked = true
    publisher.publishNow()
    val snapshot = WearCodec.decodeSnapshot(transport.snapshots.single())
    assertThat(snapshot.locked).isTrue()
    assertThat(snapshot.threads).isEmpty()
    assertThat(gateway.calls).isEmpty()
  }

  @Test
  fun `locked snapshot is published even with no watch reachable`() {
    gateway.locked = true
    transport.watchPresent = false
    publisher.publishNow()
    assertThat(transport.snapshots.size).isEqualTo(1)
  }

  @Test
  fun `no watch - message content is never put into the Data Layer`() {
    transport.watchPresent = false
    publisher.publishNow()
    assertThat(transport.snapshots).isEmpty()
    assertThat(gateway.calls).isEmpty()
  }

  @Test
  fun `burst of requests is coalesced into one publish`() {
    repeat(50) { publisher.request() }
    assertThat(executor.queue.size).isEqualTo(1)
    executor.runAll()
    assertThat(transport.snapshots.size).isEqualTo(1)
  }

  @Test
  fun `request after a publish starts schedules another`() {
    publisher.request()
    executor.runAll()
    publisher.request()
    executor.runAll()
    assertThat(transport.snapshots.size).isEqualTo(2)
  }

  @Test
  fun `publish failures don't escape the executor`() {
    val throwing = object : WearTransport by transport {
      override fun putSnapshot(bytes: ByteArray) = throw IllegalStateException("boom")
    }
    val p = SnapshotPublisher(gateway, throwing, executor, clock = { 1L })
    p.request()
    executor.runAll() // must not throw
  }
}
