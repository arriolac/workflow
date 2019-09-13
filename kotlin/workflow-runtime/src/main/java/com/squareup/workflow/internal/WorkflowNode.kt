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
package com.squareup.workflow.internal

import com.squareup.workflow.Snapshot
import com.squareup.workflow.StatefulWorkflow
import com.squareup.workflow.Workflow
import com.squareup.workflow.WorkflowAction
import com.squareup.workflow.applyTo
import com.squareup.workflow.debugging.LazyString
import com.squareup.workflow.debugging.WorkflowHierarchyDebugSnapshot
import com.squareup.workflow.debugging.WorkflowUpdateDebugInfo
import com.squareup.workflow.debugging.WorkflowUpdateDebugInfo.Kind
import com.squareup.workflow.debugging.WorkflowUpdateDebugInfo.Source
import com.squareup.workflow.internal.Behavior.WorkerCase
import com.squareup.workflow.parse
import com.squareup.workflow.readByteStringWithLength
import com.squareup.workflow.writeByteStringWithLength
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.SelectBuilder
import kotlin.coroutines.CoroutineContext

/**
 * A node in a state machine tree. Manages the actual state for a given [Workflow].
 *
 * @param initialState Allows unit tests to start the node from a given state, instead of calling
 * [StatefulWorkflow.initialState].
 */
internal class WorkflowNode<PropsT, StateT, OutputT : Any, RenderingT>(
  val id: WorkflowId<PropsT, OutputT, RenderingT>,
  workflow: StatefulWorkflow<PropsT, StateT, OutputT, RenderingT>,
  initialProps: PropsT,
  snapshot: Snapshot?,
  baseContext: CoroutineContext,
  initialState: StateT? = null
) : CoroutineScope {

  /**
   * Holds the channel representing the outputs of a worker, as well as a tombstone flag that is
   * true after the worker has finished and we've reported that fact to the workflow. This is to
   * prevent the workflow from entering an infinite loop of getting `Finished` events if it
   * continues to listen to the worker after it finishes.
   */
  private class WorkerSession(
    val channel: ReceiveChannel<*>,
    var tombstone: Boolean = false
  )

  /**
   * Context that has a job that will live as long as this node.
   * Also adds a debug name to this coroutine based on its ID.
   */
  override val coroutineContext = baseContext + Job(baseContext[Job]) + CoroutineName(id.toString())

  private val subtreeManager = SubtreeManager<StateT, OutputT>(coroutineContext)
  private val workerTracker =
    LifetimeTracker<WorkerCase<*, StateT, OutputT>, Any, WorkerSession>(
        getKey = { case -> case },
        start = { case -> WorkerSession(launchWorker(case.worker)) },
        dispose = { _, session -> session.channel.cancel() }
    )

  private var state: StateT = initialState
      ?: snapshot?.restoreState(initialProps, workflow)
      ?: workflow.initialState(initialProps, snapshot = null)

  private var lastProps: PropsT = initialProps

  private var behavior: Behavior<StateT, OutputT>? = null

  /**
   * Walk the tree of workflows, rendering each one and using
   * [RenderContext][com.squareup.workflow.RenderContext] to give its children a chance to
   * render themselves and aggregate those child renderings.
   */
  @Suppress("UNCHECKED_CAST")
  fun render(
    workflow: StatefulWorkflow<PropsT, *, OutputT, RenderingT>,
    input: PropsT
  ): RenderingEnvelope<RenderingT> =
    renderWithStateType(workflow as StatefulWorkflow<PropsT, StateT, OutputT, RenderingT>, input)

  /**
   * Walk the tree of state machines again, this time gathering snapshots and aggregating them
   * automatically.
   */
  fun snapshot(workflow: StatefulWorkflow<*, *, *, *>): Snapshot {
    val childrenSnapshot = subtreeManager.createChildrenSnapshot()
    @Suppress("UNCHECKED_CAST")
    return childrenSnapshot.withState(
        workflow as StatefulWorkflow<PropsT, StateT, OutputT, RenderingT>
    )
  }

  /**
   * Gets the next [output][OutputT] from the state machine.
   *
   * Walk the tree of state machines, asking each one to wait for its next event. If something happen
   * that results in an output, that output is returned. Null means something happened that requires
   * a re-render, e.g. my state changed or a child state changed.
   *
   * It is an error to call this method after calling [cancel].
   */
  @UseExperimental(InternalCoroutinesApi::class)
  fun <T : Any> tick(
    selector: SelectBuilder<OutputEnvelope<T>>,
    handler: (OutputEnvelope<OutputT>) -> OutputEnvelope<T>
  ) {
    fun acceptUpdate(
      action: WorkflowAction<StateT, OutputT>,
      kind: Kind
    ): OutputEnvelope<T> {
      val (newState, output) = action.applyTo(state)
      state = newState
      val info = createDebugInfo(kind)
      val envelope = OutputEnvelope(output, info)
      return handler(envelope)
    }

    // Listen for any child workflow updates.
    subtreeManager.tickChildren(selector, ::acceptUpdate)

    // Listen for any subscription updates.
    workerTracker.lifetimes
        .filter { (_, session) -> !session.tombstone }
        .forEach { (case, session) ->
          with(selector) {
            session.channel.onReceiveOrClosed { valueOrClosed ->
              if (valueOrClosed.isClosed) {
                // Set the tombstone flag so we don't continue to listen to the subscription.
                session.tombstone = true
                // Nothing to do on close other than update the session, so don't emit any output.
                val debugInfo = createDebugInfo(Kind.DidUpdate(Source.Worker))
                return@onReceiveOrClosed OutputEnvelope(null, debugInfo)
              } else {
                val update = case.acceptUpdate(valueOrClosed.value)
                acceptUpdate(update, Kind.DidUpdate(Source.Worker))
              }
            }
          }
        }

    // Listen for any events.
    with(selector) {
      behavior!!.nextActionFromEvent.onAwait { update ->
        acceptUpdate(update, Kind.DidUpdate(Source.External))
      }
    }
  }

  private fun createDebugInfo(kind: Kind): WorkflowUpdateDebugInfo =
    WorkflowUpdateDebugInfo(id.type.toString(), kind)

  /**
   * Cancels this state machine host, and any coroutines started as children of it.
   *
   * This must be called when the caller will no longer call [tick]. It is an error to call [tick]
   * after calling this method.
   */
  fun cancel() {
    // No other cleanup work should be done in this function, since it will only be invoked when
    // this workflow is *directly* discarded by its parent (or the host).
    // If you need to do something whenever this workflow is torn down, add it to the
    // invokeOnCompletion handler for the Job above.
    coroutineContext.cancel()
  }

  /**
   * Contains the actual logic for [render], after we've casted the passed-in [Workflow]'s
   * state type to our `StateT`.
   */
  private fun renderWithStateType(
    workflow: StatefulWorkflow<PropsT, StateT, OutputT, RenderingT>,
    input: PropsT
  ): RenderingEnvelope<RenderingT> {
    updatePropsAndState(workflow, input)

    val context = RealRenderContext(subtreeManager)
    val rendering = workflow.render(input, state, context)

    behavior = context.buildBehavior()
        .apply {
          // Start new children/workers, and drop old ones.
          subtreeManager.track(childCases)
          workerTracker.track(workerCases)
        }

    val debugSnapshot = WorkflowHierarchyDebugSnapshot(
        workflowType = id.type.toString(),
        stateDescription = LazyString(state::toString),
        children = subtreeManager.createChildDebugSnapshots()
    )

    return RenderingEnvelope(rendering, debugSnapshot)
  }

  private fun updatePropsAndState(
    workflow: StatefulWorkflow<PropsT, StateT, OutputT, RenderingT>,
    newProps: PropsT
  ) {
    state = workflow.onPropsChanged(lastProps, newProps, state)
    lastProps = newProps
  }

  /** @see Snapshot.parsePartial */
  private fun Snapshot.withState(
    workflow: StatefulWorkflow<PropsT, StateT, OutputT, RenderingT>
  ): Snapshot {
    val stateSnapshot = workflow.snapshotState(state)
    return Snapshot.write { sink ->
      sink.writeByteStringWithLength(stateSnapshot.bytes)
      sink.write(bytes)
    }
  }

  private fun Snapshot.restoreState(
    input: PropsT,
    workflow: StatefulWorkflow<PropsT, StateT, OutputT, RenderingT>
  ): StateT {
    val (state, childrenSnapshot) = parsePartial(input, workflow)
    subtreeManager.restoreChildrenFromSnapshot(childrenSnapshot)
    return state
  }

  /** @see Snapshot.withState */
  private fun Snapshot.parsePartial(
    input: PropsT,
    workflow: StatefulWorkflow<PropsT, StateT, OutputT, RenderingT>
  ): Pair<StateT, Snapshot> =
    bytes.parse { source ->
      val stateSnapshot = source.readByteStringWithLength()
      val childrenSnapshot = source.readByteString()
      val state = workflow.initialState(input, Snapshot.of(stateSnapshot))
      return Pair(state, Snapshot.of(childrenSnapshot))
    }
}
