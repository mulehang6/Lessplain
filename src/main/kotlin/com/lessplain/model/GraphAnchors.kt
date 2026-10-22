package com.lessplain.model

/** 遍历图中的所有固定源码引用；引用名称用于批量错误和保存响应。 */
internal fun UnderstandingGraph.mapAnchors(transform: (String, SourceAnchor) -> SourceAnchor): UnderstandingGraph = copy(
    nodes = nodes.map { node ->
        node.copy(anchor = node.anchor?.let { transform("nodes[" + node.id + "].anchor", it) })
    },
    evidence = evidence.map { item ->
        val prefix = "evidence[" + item.id + "]"
        item.copy(
            anchor = transform(prefix + ".anchor", item.anchor),
            dependencies = item.dependencies.mapIndexed { index, anchor ->
                transform(prefix + ".dependencies[" + index + "]", anchor)
            },
        )
    },
)
