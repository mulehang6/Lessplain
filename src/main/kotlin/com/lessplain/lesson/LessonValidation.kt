package com.lessplain.lesson

import com.lessplain.storage.sha256

private val lessonIdPattern = Regex("[A-Za-z0-9_-]{1,100}")
private val dependencyPattern = Regex("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+")

internal fun requireLessonId(value: String) {
    require(lessonIdPattern.matches(value)) { "教材标识只能包含字母、数字、下划线和连字符" }
}

/** 加载时检查引用结构；内容失效是可显示状态，不因此删除笔记。 */
internal fun validateLessonState(state: LessonState) {
    require(state.lessons.map { it.id }.distinct().size == state.lessons.size) { "教材标识重复" }
    require(state.annotations.map { it.id }.distinct().size == state.annotations.size) { "批注标识重复" }
    require(state.contexts.map { it.token }.distinct().size == state.contexts.size) { "固定来源标识重复" }
    val records = state.lessons.associateBy { it.id }
    val annotations = state.annotations.associateBy { it.id }
    state.lessons.forEach { validateLesson(it, allowDraft = true) }
    state.annotations.forEach { annotation ->
        val record = requireNotNull(records[annotation.lessonId]) { "批注的教材不存在" }
        require(annotation.status in setOf("note", "pending", "answered")) { "批注状态无效" }
        validateLessonBlocks(annotation.answer, record.examples.map { it.id }.toSet())
        val visited = mutableSetOf(annotation.id)
        var owner = annotation.target.annotationId
        while (owner != null) {
            require(visited.add(owner)) { "批注的嵌套引用成环" }
            val parent = requireNotNull(annotations[owner]) { "批注所属的回答不存在" }
            require(parent.lessonId == record.id) { "批注不能跨教材关联" }
            owner = parent.target.annotationId
        }
    }
    state.contexts.forEach { context ->
        require(context.lessonId in records) { "固定来源的教材不存在" }
        context.annotationId?.let { id ->
            val annotation = requireNotNull(annotations[id]) { "固定来源的批注不存在" }
            require(annotation.lessonId == context.lessonId && annotation.contextToken == context.token &&
                annotation.target == context.target && annotation.question == context.question) { "批注与固定来源不一致" }
        }
    }
}

/** 先验证整份结果，再落盘，避免错误引用造成半份教材。 */
internal fun validateLesson(record: LessonRecord, allowDraft: Boolean = false) {
    requireLessonId(record.id)
    require(record.title.isNotBlank()) { "教材标题不能为空" }
    require(record.origin.anchor.text.isNotBlank()) { "教材需要源码来源" }
    validateLessonSource(record.origin.anchor)
    record.origin.focus?.let(::validateLessonFocus)
    require(record.revision >= 0) { "教材版本无效" }
    require(allowDraft || record.blocks.isNotEmpty()) { "教材至少需要一个内容块" }
    require(record.examples.map { it.id }.distinct().size == record.examples.size) { "示例标识重复" }
    record.examples.forEach { example ->
        requireLessonId(example.id)
        require(example.fileName.matches(Regex("[A-Za-z0-9_-]+\\.main\\.kts"))) {
            "示例文件名必须是独立的 .main.kts 文件名，不能包含目录"
        }
        require(example.code.isNotBlank()) { "可运行示例不能为空" }
        require(example.dependencies.all(dependencyPattern::matches)) { "示例依赖必须使用 group:artifact:version 坐标" }
    }
    validateLessonBlocks(record.blocks, record.examples.map { it.id }.toSet())
}

internal fun validateLessonBlocks(blocks: List<LessonBlock>, exampleIds: Set<String>) {
    require(blocks.map { it.id }.distinct().size == blocks.size) { "教材块标识重复" }
    blocks.forEach { block ->
        requireLessonId(block.id)
        require(block.level in 0..3) { "标题层级必须在 0 到 3 之间" }
        block.richText?.let { runs ->
            require(block.kind == "text" && block.text.isEmpty() && runs.isNotEmpty()) { "富文本只用于正文，不能同时重复 text" }
            runs.forEach { run ->
                require(run.style in setOf("plain", "strong", "emphasis", "code", "link")) { "不支持的正文样式" }
                require(run.style != "link" || run.href?.let(::safeLessonLink) == true) { "链接只支持 http/https" }
            }
        }
        val bindings = block.sourceBindings.orEmpty()
        require(bindings.map { it.id }.distinct().size == bindings.size) { "概念关联标识重复" }
        bindings.forEach { binding ->
            requireLessonId(binding.id)
            require(binding.label.isNotBlank()) { "概念关联需要标签" }
            validateLessonFocus(binding.focus)
            require((binding.start == null) == (binding.end == null)) { "教材关联范围需要同时提供 start/end" }
            if (binding.start != null && binding.end != null) {
                require(block.kind == "text" && binding.start >= 0 && binding.start < binding.end &&
                    binding.end <= lessonBlockText(block).length) { "教材关联范围越界；局部范围只用于正文" }
            }
        }
        block.source?.let {
            require(block.kind == "code" && block.exampleId == null) { "源码链接用于独立的源码引用块" }
            validateLessonSource(it)
        }
        when (block.kind) {
            "text" -> require(lessonBlockText(block).isNotBlank()) { "文字块不能为空" }
            "code" -> {
                require(block.text.isNotBlank() || block.exampleId != null) { "代码块缺少代码或示例引用" }
                require(block.exampleId == null || block.exampleId in exampleIds) { "代码块引用了不存在的示例" }
                require(block.exampleId == null || block.text.isEmpty()) { "引用示例时不要复制 text；显示与运行使用同一份文件" }
            }
            "table" -> {
                require(block.columns.isNotEmpty()) { "表格需要列标题" }
                require(block.rows.all { it.size == block.columns.size }) { "表格行的单元格数量不匹配" }
            }
            "diagram" -> {
                val graph = requireNotNull(block.graph) { "图块需要 graph" }
                require(graph.nodes.isNotEmpty()) { "图块至少需要一个节点" }
                require(graph.nodes.map { it.id }.distinct().size == graph.nodes.size) { "图节点标识重复" }
                val nodes = graph.nodes.map { it.id }.toSet()
                require(graph.nodes.all { it.id.isNotBlank() && it.label.isNotBlank() }) { "图节点需要标识和标签" }
                require(graph.edges.map { it.id }.distinct().size == graph.edges.size) { "图关系标识重复" }
                require(graph.edges.all { it.from in nodes && it.to in nodes }) { "图关系引用了不存在的节点" }
                if (graph.clusters.isNotEmpty()) {
                    val members = graph.clusters.flatMap { it.nodeIds }
                    require(members.toSet() == nodes && members.size == nodes.size) { "图分组必须互斥且完整覆盖节点" }
                }
            }
            else -> error("不支持的教材块类型：${block.kind}")
        }
    }
}

internal fun validateLessonSource(anchor: com.lessplain.model.SourceAnchor) {
    val path = anchor.path.replace('\\', '/')
    require(path.isNotBlank() && !path.startsWith('/') && ':' !in path && path.split('/').none { it == ".." }) {
        "教材来源必须是项目内的相对路径"
    }
    require(anchor.text.isNotBlank()) { "源码引用需要唯一片段" }
}

internal fun lessonBlockText(block: LessonBlock): String = when (block.kind) {
    "table" -> (listOf(block.columns) + block.rows).joinToString("\n") { it.joinToString("\t") }
    "diagram" -> block.graph?.nodes?.joinToString("\n") { "${it.id}: ${it.label}" }.orEmpty()
    "text" -> block.richText?.joinToString("") { it.text } ?: block.text
    else -> block.text
}

internal fun safeLessonLink(value: String): Boolean = runCatching {
    java.net.URI(value).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() }
}.getOrDefault(false)

internal fun validateLessonFocus(focus: LessonSourceFocus) {
    validateLessonSource(focus.anchor)
    require(focus.start >= 0 && focus.start < focus.end && focus.end <= focus.anchor.text.length) { "源码关注范围越界" }
    require(focus.anchor.text.substring(focus.start, focus.end).isNotBlank()) { "源码关注范围不能为空白" }
}

/** 查找唯一的源码片段；行号不能把已经失效的笔记重新挂到其他代码上。 */
internal fun uniqueLessonSourceOffset(source: String, selected: String): Int? {
    if (selected.isBlank()) return null
    val first = source.indexOf(selected)
    return first.takeIf { it >= 0 && source.indexOf(selected, it + 1) < 0 }
}

internal fun lessonTargetIsCurrent(
    record: LessonRecord,
    annotations: List<LessonAnnotation>,
    target: LessonTarget,
    exampleText: (LessonExample) -> String = ::lessonScript,
): Boolean {
    if (target.blockId == null) return target.selectedText == record.origin.anchor.text &&
        target.contentVersion == sha256(record.origin.anchor.text)
    val blocks = if (target.annotationId != null) annotations.firstOrNull {
        it.id == target.annotationId && it.lessonId == record.id
    }?.answer ?: return false else record.blocks
    val block = blocks.firstOrNull { it.id == target.blockId } ?: return false
    val text = when {
        target.objectId != null -> block.graph?.nodes?.firstOrNull { it.id == target.objectId }?.label ?: return false
        target.row != null && target.column != null ->
            block.rows.getOrNull(target.row)?.getOrNull(target.column) ?: return false
        block.exampleId != null -> record.examples.firstOrNull { it.id == block.exampleId }?.let(exampleText) ?: return false
        else -> lessonBlockText(block)
    }
    return sha256(text) == target.contentVersion && target.start >= 0 && target.end <= text.length &&
        target.start <= target.end && text.substring(target.start, target.end) == target.selectedText
}
