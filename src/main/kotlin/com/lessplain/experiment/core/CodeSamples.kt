package com.lessplain.experiment.core

import com.lessplain.experiment.model.*
import kotlinx.serialization.json.*

/** 教学资源独立校验，不能通过样例接口放宽真实项目 sources 的边界。 */
internal fun validateCodeSamples(document: ViewDocument) {
    val samples = document.codeSamples.orEmpty()
    require(samples.size <= 20 && samples.values.sumOf { it.code.length } <= 500_000) { "教学代码资源过大" }
    samples.forEach { (id, sample) ->
        require(id.isNotBlank() && id.length <= 120 && sample.title.isNotBlank() && sample.title.length <= 120) { "教学代码名称无效" }
        require(sample.language.matches(Regex("[A-Za-z0-9_+.-]{1,32}"))) { "教学代码语言无效：$id" }
        require(sample.code.isNotBlank() && sample.code.length <= 100_000) { "教学代码不能为空或过大：$id" }
    }
}

internal fun requireCodeSample(document: ViewDocument, target: CodeSampleTarget): CodeSample {
    val sample = document.codeSamples.orEmpty()[target.id] ?: error("教学代码不存在：${target.id}")
    val end = target.endLine ?: target.startLine
    require(target.startLine >= 1 && end >= target.startLine && end <= sample.code.lines().size) { "教学代码行范围无效" }
    return sample
}

internal fun sampleLocation(
    view: ViewRecord,
    target: CodeSampleTarget,
    elementId: String?,
    nodeId: String?,
    state: JsonPayload,
): SampleLocation {
    val sample = requireCodeSample(view.document, target)
    val element = elementId?.let { view.document.spec.value["elements"]?.jsonObject?.get(it)?.jsonObject ?: error("教学组件已删除") }
    val props = element?.get("props")?.jsonObject
    val label = if (nodeId != null) {
        check(element?.get("type")?.jsonPrimitive?.contentOrNull == "Graph") { "教学图身份无效" }
        val node = props?.get("nodes")?.jsonArray?.singleOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == nodeId }?.jsonObject
            ?: error("教学节点已删除")
        check(node["sample"]?.let { Json.decodeFromJsonElement<CodeSampleTarget>(it) } == target) { "教学节点代码引用已变化" }
        node["label"]?.jsonPrimitive?.content
    } else {
        if (element != null) {
            when (element["type"]?.jsonPrimitive?.contentOrNull) {
                "CodeSample" -> check(props?.get("sample")?.jsonPrimitive?.content == target.id &&
                    (props["startLine"]?.jsonPrimitive?.intOrNull ?: 1) == target.startLine &&
                    props["endLine"]?.jsonPrimitive?.intOrNull == target.endLine) { "教学组件代码引用已变化" }
                "Demo" -> check(props?.get("demo")?.jsonPrimitive?.content in view.document.demos.orEmpty()) { "交互示例已删除" }
                else -> error("组件不支持教学代码定位")
            }
        }
        null
    }
    return SampleLocation(view.ref, view.revision, target, sample,
        ViewOrigin(viewRef = view.ref, elementId = elementId, nodeId = nodeId, nodeLabel = label, state = state))
}

/** 保存前校验当前样例；已有问题的冻结快照在后续课程修订后仍可独立阅读。 */
internal fun validateSampleSelection(view: ViewRecord, selection: SampleSelection) {
    val sample = view.document.codeSamples.orEmpty()[selection.id] ?: error("教学代码已删除，问题文字已保留")
    check(sample == selection.sample && digest(sample.code) == selection.version) { "教学代码已变化，请重新选择；问题文字已保留" }
    val range = selection.selectionRange
    require(range.startOffset >= 0 && range.endOffset > range.startOffset && range.endOffset <= sample.code.length) { "教学代码选区无效" }
    fun splitsSurrogate(offset: Int) = offset > 0 && offset < sample.code.length &&
        sample.code[offset - 1].isHighSurrogate() && sample.code[offset].isLowSurrogate()
    require(!splitsSurrogate(range.startOffset) && !splitsSurrogate(range.endOffset)) { "教学代码选区截断字符" }
}

internal fun validateSampleOrigin(view: ViewRecord, origin: ViewOrigin) {
    val selection = requireNotNull(origin.sample)
    validateSampleSelection(view, selection)
    if (origin.elementId == null) { require(origin.nodeId == null); return }
    val element = view.document.spec.value["elements"]?.jsonObject?.get(origin.elementId)?.jsonObject ?: error("教学来源组件已删除")
    val props = element.getValue("props").jsonObject
    val target = if (origin.nodeId != null) {
        val node = props["nodes"]?.jsonArray?.singleOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == origin.nodeId }?.jsonObject
            ?: error("教学来源节点已删除")
        check(node["label"]?.jsonPrimitive?.content == origin.nodeLabel) { "教学来源节点已变化" }
        node["sample"]?.let { Json.decodeFromJsonElement<CodeSampleTarget>(it) } ?: error("教学来源节点已变化")
    } else if (element["type"]?.jsonPrimitive?.content == "CodeSample") {
        CodeSampleTarget(props.getValue("sample").jsonPrimitive.content, props["startLine"]?.jsonPrimitive?.int ?: 1,
            props["endLine"]?.jsonPrimitive?.int)
    } else CodeSampleTarget(selection.id)
    sampleLocation(view, target, origin.elementId, origin.nodeId, origin.state)
    check(target.id == selection.id) { "教学来源代码已变化" }
    if (origin.nodeId != null) {
        val lines = selection.sample.code.lines()
        val start = lines.take(target.startLine - 1).sumOf { it.length + 1 }
        val end = lines.take(target.endLine ?: target.startLine).sumOf { it.length + 1 } - 1
        require(selection.selectionRange.startOffset >= start && selection.selectionRange.endOffset <= end) { "教学选区不属于当前节点" }
    }
}
