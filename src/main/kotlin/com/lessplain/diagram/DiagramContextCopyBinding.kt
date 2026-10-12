package com.lessplain.diagram

import com.lessplain.ide.UnderstandingService
import com.lessplain.ui.SourceNode

/** 节点复制冻结点击来源，任务由具体画布拥有，关闭画布不能再覆盖剪贴板。 */
internal fun UnderstandingDiagram.bindNodeContextCopy(
    service: UnderstandingService,
    sourceOf: (String) -> SourceNode?,
): AutoCloseable {
    var closed = false
    var task: AutoCloseable? = null
    val subscription = onCopyNodeContext { id ->
        if (!closed) sourceOf(id)?.let { source ->
            task?.close()
            task = service.copyNodeContext(source.record, source.nodeId) {
                if (!closed && !disposed) showCopied(id)
            }
        }
    }
    return AutoCloseable {
        closed = true
        subscription.close()
        task?.close()
    }
}
