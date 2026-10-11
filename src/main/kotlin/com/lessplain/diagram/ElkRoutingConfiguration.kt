package com.lessplain.diagram

import org.eclipse.elk.alg.layered.options.FixedAlignment
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.graph.ElkNode

/** 各级图预留相同的独立连线通道，并保留横向主路径。 */
internal fun ElkNode.configureUnderstandingRouting(direction: LayoutDirection) {
    setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered")
    setProperty(CoreOptions.DIRECTION, if (direction == LayoutDirection.VERTICAL) Direction.DOWN else Direction.RIGHT)
    setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
    if (direction == LayoutDirection.HORIZONTAL) {
        setProperty(LayeredOptions.NODE_PLACEMENT_BK_FIXED_ALIGNMENT, FixedAlignment.LEFTUP)
    }
    setProperty(CoreOptions.SPACING_EDGE_NODE, 26.0)
    setProperty(CoreOptions.SPACING_EDGE_EDGE, 18.0)
    setProperty(CoreOptions.SPACING_EDGE_LABEL, 8.0)
    setProperty(LayeredOptions.SPACING_EDGE_EDGE_BETWEEN_LAYERS, 18.0)
    setProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, 26.0)
    setProperty(CoreOptions.RANDOM_SEED, 1)
}
