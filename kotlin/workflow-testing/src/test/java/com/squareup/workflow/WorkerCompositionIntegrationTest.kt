/*
 * Copyright 2019 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.squareup.workflow

import com.squareup.workflow.WorkflowAction.Companion.noAction
import com.squareup.workflow.testing.WorkerSink
import com.squareup.workflow.testing.testFromStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class WorkerCompositionIntegrationTest {

  private class ExpectedException : RuntimeException()

  @Test fun `worker started`() {
    var started = false
    val worker = Worker.create<Unit> { started = true }
    val workflow = Workflow.stateless<Boolean, Nothing, Unit> { props ->
      if (props) runningWorker(worker) { noAction() }
    }

    workflow.testFromStart(false) {
      assertFalse(started)
      sendProps(true)
      assertTrue(started)
    }
  }

  @Test fun `worker cancelled when dropped`() {
    var cancelled = false
    val worker = object : LifecycleWorker() {
      override fun onStopped() {
        cancelled = true
      }
    }
    val workflow = Workflow.stateless<Boolean, Nothing, Unit> { props ->
      if (props) runningWorker(worker) { noAction() }
    }

    workflow.testFromStart(true) {
      assertFalse(cancelled)
      sendProps(false)
      assertTrue(cancelled)
    }
  }

  @Test fun `worker only starts once over multiple renders`() {
    var starts = 0
    var stops = 0
    val worker = object : LifecycleWorker() {
      override fun onStarted() {
        starts++
      }

      override fun onStopped() {
        stops++
      }
    }
    val workflow = Workflow.stateless<Unit, Nothing, Unit> { runningWorker(worker) { noAction() } }

    workflow.testFromStart {
      assertEquals(1, starts)
      assertEquals(0, stops)

      sendProps(Unit)
      assertEquals(1, starts)
      assertEquals(0, stops)

      sendProps(Unit)
      assertEquals(1, starts)
      assertEquals(0, stops)
    }
  }

  @Test fun `worker restarts`() {
    var starts = 0
    var stops = 0
    val worker = object : LifecycleWorker() {
      override fun onStarted() {
        starts++
      }

      override fun onStopped() {
        stops++
      }
    }
    val workflow = Workflow.stateless<Boolean, Nothing, Unit> { props ->
      if (props) runningWorker(worker) { noAction() }
    }

    workflow.testFromStart(false) {
      assertEquals(0, starts)
      assertEquals(0, stops)

      sendProps(true)
      assertEquals(1, starts)
      assertEquals(0, stops)

      sendProps(false)
      assertEquals(1, starts)
      assertEquals(1, stops)

      sendProps(true)
      assertEquals(2, starts)
      assertEquals(1, stops)
    }
  }

  @Test fun `runningWorker gets output`() {
    val worker = WorkerSink<String>("")
    val workflow = Workflow.stateless<Unit, String, Unit> {
      runningWorker(worker) { WorkflowAction { it } }
    }

    workflow.testFromStart {
      assertFalse(this.hasOutput)

      worker.send("foo")

      assertEquals("foo", awaitNextOutput())
    }
  }

  @Test fun `runningWorker gets error`() {
    val channel = Channel<String>()
    val workflow = Workflow.stateless<Unit, String, Unit> {
      runningWorker(channel.asWorker()) { WorkflowAction { it } }
    }

    workflow.testFromStart {
      assertFalse(this.hasOutput)

      channel.cancel(CancellationException(null, ExpectedException()))

      assertFailsWith<ExpectedException> {
        awaitNextOutput()
      }
    }
  }

  @Test fun `onWorkerOutput does nothing when worker finished`() {
    val channel = Channel<Unit>()
    val workflow = Workflow.stateless<Unit, Unit, Unit> {
      runningWorker(channel.asWorker()) { fail("Expected handler to not be invoked.") }
    }

    workflow.testFromStart {
      channel.close()

      assertFailsWith<TimeoutCancellationException> {
        // There should never be any outputs, so this should timeout.
        awaitNextOutput(timeoutMs = 100)
      }
    }
  }

  // See https://github.com/square/workflow/issues/261.
  @Test fun `onWorkerOutput closes over latest state`() {
    val triggerOutput = WorkerSink<Unit>("")

    val incrementState = WorkflowAction<Int, Int> {
      state += 1
      null
    }

    val workflow = Workflow.stateful<Int, Int, () -> Unit>(
        initialState = 0,
        render = { state ->
          runningWorker(triggerOutput) { WorkflowAction { state } }

          val sink = makeActionSink<WorkflowAction<Int, Int>>()
          return@stateful { sink.send(incrementState) }
        }
    )

    workflow.testFromStart {
      triggerOutput.send(Unit)
      assertEquals(0, awaitNextOutput())

      awaitNextRendering()
          .invoke()
      triggerOutput.send(Unit)

      assertEquals(1, awaitNextOutput())

      awaitNextRendering()
          .invoke()
      triggerOutput.send(Unit)

      assertEquals(2, awaitNextOutput())
    }
  }

  @Test fun `runningWorker throws when output emitted`() {
    @Suppress("UNCHECKED_CAST")
    val worker = Worker.from { Unit } as Worker<Nothing>
    val workflow = Workflow.stateless<Unit, Unit, Unit> {
      runningWorker(worker)
    }

    workflow.testFromStart {
      assertTrue(awaitFailure() is AssertionError)
    }
  }

  @Test fun `runningWorker doesn't throw when worker finishes`() {
    // No-op worker, completes immediately.
    val worker = Worker.createSideEffect {}
    val workflow = Workflow.stateless<Unit, Unit, Unit> {
      runningWorker(worker)
    }

    workflow.testFromStart {
      assertFailsWith<TimeoutCancellationException> {
        awaitNextOutput(timeoutMs = 100)
      }
    }
  }
}
