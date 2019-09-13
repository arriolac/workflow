package com.squareup.workflow

import com.squareup.workflow.debugging.WorkflowDebugInfo
import kotlinx.coroutines.flow.Flow

/**
 * TODO write documentation
 */
class WorkflowSession<out OutputT : Any, out RenderingT>(
  val renderingsAndSnapshots: Flow<RenderingAndSnapshot<RenderingT>>,
  val outputs: Flow<OutputT>,
  val debugSnapshots: Flow<WorkflowDebugInfo>
)