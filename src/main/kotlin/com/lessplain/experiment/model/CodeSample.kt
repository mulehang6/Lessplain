package com.lessplain.experiment.model

import com.lessplain.model.SourceSelectionRange
import kotlinx.serialization.Serializable

/** 随页面保存的教学代码，不代表项目文件或真实运行结果。 */
@Serializable
data class CodeSample(val title: String, val language: String, val code: String)

/** 图节点和组件引用同一份样例，行范围为一基闭区间。 */
@Serializable
data class CodeSampleTarget(val id: String, val startLine: Int = 1, val endLine: Int? = null)

/** 原生编辑器提问时冻结全文、版本及 UTF-16 选区，不依赖内存文件路径。 */
@Serializable
data class SampleSelection(
    val id: String,
    val sample: CodeSample,
    val version: String,
    val selectionRange: SourceSelectionRange,
)

/** 已验证的样例导航；打开后的编辑器及后续提问使用这一份冻结来源。 */
data class SampleLocation(
    val viewRef: String,
    val revision: Int,
    val target: CodeSampleTarget,
    val sample: CodeSample,
    val origin: ViewOrigin,
)
