package com.lessplain.experiment.model

import kotlinx.serialization.Serializable
import com.lessplain.model.SourceSelectionRange

/** Agent 提交的完整页面；身份、来源归属和版本由固定上下文管理。 */
@Serializable
data class ViewDocument(
    val question: String,
    val spec: JsonPayload,
    val title: String? = null,
    val sources: Map<String, SourceReference>? = null,
    val demos: Map<String, DemoResource>? = null,
    val codeSamples: Map<String, CodeSample>? = null,
)

/** 新引用只包含定位所需的信息，实际版本与位置在 IDE 中解析。 */
@Serializable
data class SourceReference(val path: String, val text: String, val symbol: String? = null)

@Serializable
data class ResolvedSource(
    val path: String,
    val text: String,
    val symbol: String? = null,
    val version: String,
    val line: Int,
    val selectionRange: SourceSelectionRange? = null,
)

@Serializable
data class DemoResource(val html: String)

@Serializable
data class ViewRecord(
    val ref: String,
    val revision: Int,
    val document: ViewDocument,
    val sources: Map<String, ResolvedSource>,
    val createdAt: Long,
    val updatedAt: Long,
    val parentRef: String? = null,
    val origin: ViewOrigin = ViewOrigin(),
    /** 答案保存当时的版本化问题；待答行删除后仍可重建历史引用和冻结来源。 */
    val answeredNote: ViewNote? = null,
)

/** 捕获当时的来源，区别于结果在问题树中的归属。 */
@Serializable
data class ViewOrigin(
    val viewRef: String? = null,
    val elementId: String? = null,
    val nodeId: String? = null,
    val nodeLabel: String? = null,
    val source: ResolvedSource? = null,
    val state: JsonPayload = JsonPayload.empty(),
    /** 产生注释的入口；源码选择即使绑定图节点也始终是 sourceSelection。 */
    val entry: String? = null,
    val sample: SampleSelection? = null,
)

/** 页面交互入口，避免各个原生提问调用自行拼接协议字符串。 */
object ViewOriginEntry {
    const val NODE = "node"
    const val COMPONENT = "component"
    const val SOURCE_SELECTION = "sourceSelection"
    const val SAMPLE_SELECTION = "sampleSelection"
}

@Serializable
data class ViewNote(
    val ref: String,
    val kind: String,
    val text: String,
    val origin: ViewOrigin,
    val revision: Int = 1,
    val createdAt: Long,
    val ownerRef: String? = null,
    val answerRef: String? = null,
    val status: String = "pending",
)

/** 写入凭据只在服务内解释，调用方不参与归属、版本或幂等参数组合。 */
@Serializable
data class ContextTicket(
    val context: String,
    val origin: ViewOrigin,
    val targetRef: String? = null,
    val expectedRevision: Int? = null,
    val parentRef: String? = null,
    val noteRef: String? = null,
    val noteRevision: Int? = null,
    val question: String? = null,
)

@Serializable
data class PresentReceipt(val ref: String, val revision: Int)

@Serializable
data class FeedbackReceipt(val refs: List<String>)

@Serializable
data class ContextTarget(val operation: String, val ref: String? = null, val parentRef: String? = null)

@Serializable
data class ContextResult(
    val context: String,
    val target: ContextTarget,
    val origin: ViewOrigin,
    val catalog: JsonPayload,
    val view: ViewRecord? = null,
    val originView: ViewRecord? = null,
    val pending: List<ViewNote> = emptyList(),
    val question: String? = null,
)

@Serializable
data class AppSnapshot(
    val views: List<ViewRecord>,
    val notes: List<ViewNote>,
    val browse: Map<String, JsonPayload>,
)
