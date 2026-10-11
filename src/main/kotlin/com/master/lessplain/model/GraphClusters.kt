package com.master.lessplain.model

/** 根图的单层语义分组，不依赖坐标和布线。 */
data class GraphCluster(val id: String, val title: String, val nodeIds: List<String>)

/** 省略或 null 保留原版本分组，显式空替换则清除分组。 */
sealed interface ClusterUpdate {
    data object Keep : ClusterUpdate
    data class Replace(val clusters: List<GraphCluster>) : ClusterUpdate
}

internal fun UnderstandingGraph.validateClusters() {
    if (clusters.isEmpty()) return
    requireInput(clusters.map { it.id }.all(String::isNotBlank) &&
        clusters.map { it.id }.distinct().size == clusters.size, "分组 ID 为空或重复")
    requireInput(clusters.all { it.title.isNotBlank() && it.nodeIds.isNotEmpty() }, "分组必须有标题和节点")
    val members = clusters.flatMap { it.nodeIds }
    requireInput(members.distinct().size == members.size, "节点不能重复或属于多个分组")
    requireInput(members.toSet() == nodes.map { it.id }.toSet(), "分组必须完整覆盖图节点，不能引用未知节点")
}
