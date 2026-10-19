package com.lessplain.experiment.core

import com.lessplain.experiment.model.*
import kotlinx.serialization.json.*
import java.util.UUID

private val displayJson = Json { prettyPrint = true }

/** 身份摘要包含原组件和冻结源码；交互 state 不会使已打开的快照变成另一份内容。 */
internal fun componentVersion(view: ViewRecord, elementId: String): String {
    val element = view.document.spec.value["elements"]?.jsonObject?.get(elementId)?.jsonObject
        ?: error("来源组件已删除")
    val sourceId = element["props"]?.jsonObject?.get("source")?.jsonPrimitive?.contentOrNull
    return digest(element.toString() + sourceId?.let { view.sources[it].toString() }.orEmpty())
}

internal fun componentSnapshotCurrent(view: ViewRecord?, snapshot: ComponentSnapshot): Boolean =
    view != null && runCatching { componentVersion(view, snapshot.elementId) == snapshot.componentVersion }.getOrDefault(false)

/** 只打开本页声明的组件；源码正文始终取自服务端保存的 sources，不接收路径或客户端代码。 */
internal fun componentLocation(view: ViewRecord, elementId: String, value: JsonElement, state: JsonPayload): SampleLocation {
    val element = view.document.spec.value.getValue("elements").jsonObject[elementId]?.jsonObject ?: error("组件不存在")
    val props = element.getValue("props").jsonObject
    val type = element.getValue("type").jsonPrimitive.content
    val label = props["label"]?.jsonPrimitive?.contentOrNull?.take(120) ?: if (type == "CodeExcerpt") "源码快照" else "页面数据"
    val content = when (type) {
        "CodeExcerpt" -> {
            val source = view.sources[props.getValue("source").jsonPrimitive.content] ?: error("源码引用已失效")
            CodeSample("源码快照 · $label", source.path.substringAfterLast('.', "txt"), source.text)
        }
        "JsonView" -> {
            val raw = props["format"]?.jsonPrimitive?.contentOrNull == "text"
            val text = if (raw) (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("原始文本必须是字符串")
                else displayJson.encodeToString(JsonElement.serializer(), value)
            CodeSample((if (raw) "原始文本 · " else "页面数据 · ") + label, if (raw) "txt" else "json", text)
        }
        else -> error("该组件不支持代码预览")
    }
    require(content.code.length <= 100_000 && state.toString().length <= 500_000) { "组件快照过大" }
    val normalized = content.copy(code = content.code.replace("\r\n", "\n").replace('\r', '\n'))
    val snapshot = ComponentSnapshot(UUID.randomUUID().toString(), elementId, componentVersion(view, elementId), normalized, digest(normalized.code),
        originalText = content.code.takeIf { props["format"]?.jsonPrimitive?.contentOrNull == "text" })
    return SampleLocation(view.ref, view.revision, CodeSampleTarget(snapshot.id), normalized,
        ViewOrigin(viewRef = view.ref, elementId = elementId, state = state), snapshot)
}

/** 创建问题时只接受本服务实际签发的快照，随后以完整冻结正文保存。 */
internal fun validateComponentOrigin(view: ViewRecord, origin: ViewOrigin, issued: SampleLocation?) {
    val selection = requireNotNull(origin.componentSnapshot)
    val snapshot = selection.snapshot
    check(issued?.componentSnapshot == snapshot && issued.origin.state == origin.state &&
        issued.viewRef == origin.viewRef && origin.elementId == snapshot.elementId) { "组件快照已失效，请重新打开；问题文字已保留" }
    check(componentSnapshotCurrent(view, snapshot)) { "来源组件已变化，请重新打开；问题文字已保留" }
    val range = selection.selectionRange
    val code = snapshot.content.code
    require(range.startOffset >= 0 && range.endOffset > range.startOffset && range.endOffset <= code.length) { "组件代码选区无效" }
    fun splits(offset: Int) = offset > 0 && offset < code.length && code[offset - 1].isHighSurrogate() && code[offset].isLowSurrogate()
    require(!splits(range.startOffset) && !splits(range.endOffset)) { "组件代码选区截断字符" }
}
