package com.lessplain.experiment.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.projectOrNull
import com.lessplain.experiment.ide.ViewService
import com.lessplain.experiment.model.*
import kotlinx.coroutines.*

/** 三个公开工具；创建和更新共用同一份固定上下文提交协议。 */
class ViewToolset : McpToolset {
    @McpTool(name = "lessplain_context")
    @McpDescription("准备独立页面，或读取插件提供的页面、问题、源码 ref。返回固定 context、原问题、源码快照、待处理项和组件 catalog。读取不完成反馈。")
    suspend fun context(
        @McpDescription("可选，插件提供的 view:、note: 或 source: 引用。省略准备新页面。") ref: String? = null,
    ): ContextResult = guarded { service().context(ref) }

    @McpTool(name = "lessplain_present")
    @McpDescription("用固定 context 创建或完整替换页面。view 为 question、spec 及可选 title、sources、demos、codeSamples；按 context 的 catalog 构建。项目源码验证、教学资源校验成功后原子保存并完成对应问题。同一 context 和内容重试返回原结果。")
    suspend fun present(
        @McpDescription("lessplain_context 返回的固定凭据。") context: String,
        @McpDescription("完整页面，不是补丁。sources 只传项目 path、实际 text 和可选 symbol；独立教学代码放入 codeSamples {title,language,code}，不伪造项目路径。") view: ViewDocument,
    ): PresentReceipt = guarded {
        val service = service()
        val receipt = withContext(Dispatchers.IO) { service.engine.present(context, view) }
        service.saved(receipt.ref)
        receipt
    }
    @McpTool(name = "lessplain_finish_feedback")
    @McpDescription("完成已处理的缺陷反馈。只接受当前 note: 引用；已编辑的旧引用失败，重复完成幂等。源码问题须通过 lessplain_present 回答。")
    suspend fun finishFeedback(
        @McpDescription("lessplain_context 返回的反馈 ref 数组。") refs: List<String>,
    ): FeedbackReceipt = guarded {
        val service = service()
        val finished = withContext(Dispatchers.IO) { service.engine.finishFeedback(refs) }
        service.changed()
        FeedbackReceipt(finished)
    }
    private suspend fun service(): ViewService {
        val project = currentCoroutineContext().projectOrNull ?: mcpFail("没有活动项目")
        return project.getService(ViewService::class.java)
    }
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        mcpFail(failure.message ?: "操作失败")
    }
}
