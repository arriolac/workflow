package com.squareup.workflow

import kotlinx.coroutines.flow.Flow

/**
 * TODO write documentation
 */
class WorkflowSession<out OutputT : Any, out RenderingT>(
  val renderingsAndSnapshots: Flow<RenderingAndSnapshot<RenderingT>>,
  val outputs: Flow<OutputT>
)
