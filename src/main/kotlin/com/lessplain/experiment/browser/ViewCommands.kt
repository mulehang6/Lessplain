package com.lessplain.experiment.browser

import com.intellij.openapi.application.EDT
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.lessplain.experiment.core.ViewEngine
import com.lessplain.experiment.ide.ViewService
import com.lessplain.experiment.ide.snapshot
import com.lessplain.experiment.model.*
import com.lessplain.ide.captureEditorAnchor
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.awt.datatransfer.StringSelection

/** 浏览器只调用列出的操作；无法提交任意 IDE 命令、读任意路径或执行代码。 */
internal class ViewCommands(
    private val project: Project,
    private val service: ViewService,
    private val hostId: String,
    private val hostKind: BrowserHostKind,
) {
    private val json = service.json
    suspend fun execute(method: String, params: JsonObject): JsonElement {
        fun ref() = params.getValue("ref").jsonPrimitive.content
        fun text(key: String) = params.getValue(key).jsonPrimitive.content
        when (method) {
            "ready" -> {
                val initialTheme = withContext(Dispatchers.EDT) { theme() }
                val (snapshot, sequence) = service.published()
                return buildJsonObject {
                    put("snapshot", json.encodeToJsonElement(snapshot)); put("sequence", sequence)
                    put("hostId", hostId)
                    put("host", hostKind.wireValue)
                    service.browserViewRef(hostId)?.let { put("ref", it) }
                    put("theme", initialTheme)
                }
            }
            "refresh" -> Unit
            "select" -> {
                service.selectBrowserView(hostId, params["ref"]?.jsonPrimitive?.contentOrNull)
            }
            "origin" -> service.updateOrigin(hostId, json.decodeFromJsonElement(params))
            "navigationState" -> service.updateNavigationState(hostId, params)
            "cursor" -> service.updateBrowserCursor(hostId, BrowserCursor.parse(text("cursor")))
            "noteDetails" -> {
                val note = withContext(Dispatchers.IO) { service.engine.noteDetails(ref()) }
                service.openNoteDetails(hostId, note)
            }
            "note" -> {
                val origin = json.decodeFromJsonElement<ViewOrigin>(params.getValue("origin"))
                service.engine.createNote(text("text"), text("kind"), origin)
            }
            "editNote" -> service.engine.editNote(ref(), text("text"))
            "deleteNote" -> service.engine.deleteNote(ref())
            "browse" -> service.engine.saveBrowse(ref(), JsonPayload(params.getValue("state").jsonObject))
            "rename" -> service.engine.rename(ref(), text("title"))
            "move" -> service.engine.move(ref(), params["parentRef"]?.jsonPrimitive?.contentOrNull)
            "delete" -> service.engine.delete(ref())
            "openEditor" -> service.openEditor(ref())
            "copy" -> withContext(Dispatchers.EDT) { CopyPasteManager.getInstance().setContents(StringSelection(text("text"))) }
            "capture" -> {
                val source = withContext(Dispatchers.EDT) { captureEditorAnchor(project)?.snapshot() }
                    ?: error("请先在项目源码编辑器中选择可读代码")
                val fixed = service.engine.sourceRef(service.originForHost(hostId).copy(source = source))
                return buildJsonObject { put("ref", fixed) }
            }
            "source" -> {
                check(service.isBrowserShowing(hostId, ref())) { "当前浏览器已切换到其他页面，请重新定位源码" }
                val view = service.engine.snapshot().views.firstOrNull { it.ref == ref() } ?: error("页面不存在")
                val id = text("source")
                val source = view.sources[id] ?: error("源码引用不存在")
                val fallbackState = service.originForHost(hostId).takeIf { it.viewRef == ref() }?.state
                val binding = freezeSourceNavigation(
                    service.engine, view, id, params, fallbackState, hostId, hostKind,
                )
                return json.encodeToJsonElement(service.sources.open(source, binding.label, binding))
            }
            "sample" -> {
                check(service.isBrowserShowing(hostId, ref())) { "当前浏览器已切换到其他页面" }
                val target = CodeSampleTarget(text("sample"), params["startLine"]?.jsonPrimitive?.int ?: 1, params["endLine"]?.jsonPrimitive?.int)
                val location = service.engine.sampleLocation(ref(), params.getValue("revision").jsonPrimitive.int, target,
                    params["elementId"]?.jsonPrimitive?.contentOrNull, params["nodeId"]?.jsonPrimitive?.contentOrNull,
                    params["state"]?.let { JsonPayload(it.jsonObject) } ?: JsonPayload.empty())
                service.samples.open(location)
                return JsonNull
            }
            else -> error("未知操作")
        }
        if (method !in setOf("copy", "openEditor", "browse", "origin", "select", "navigationState", "cursor", "noteDetails")) service.changed()
        return JsonNull
    }

}

/** 把 iframe 导航字段与 host 一次性冻结到存储 revision。 */
internal fun freezeSourceNavigation(
    engine: ViewEngine,
    view: ViewRecord,
    sourceId: String,
    params: JsonObject,
    fallbackState: JsonPayload? = null,
    hostId: String? = null,
    hostKind: BrowserHostKind? = null,
): EditorSourceBinding {
    val source = view.sources[sourceId] ?: error("源码引用不存在")
    val explicit = listOf("elementId", "nodeId", "nodeLabel", "revision", "state").any(params::containsKey)
    if (!explicit) {
        return engine.freezeSourceBinding(
            view.ref, view.revision, null, null, sourceId, source.symbol ?: "当前源码依据",
            fallbackState ?: JsonPayload.empty(),
        ).copy(
            hostId = hostId,
            hostKind = hostKind,
            canPrevious = params["canPrevious"]?.jsonPrimitive?.booleanOrNull ?: false,
            canNext = params["canNext"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    val elementId = params["elementId"]?.jsonPrimitive?.contentOrNull
    val nodeId = params["nodeId"]?.jsonPrimitive?.contentOrNull
    val nodeLabel = params["nodeLabel"]?.jsonPrimitive?.contentOrNull
    check((nodeId == null) == (nodeLabel == null)) { "源码节点身份不完整，请重新定位" }
    check(nodeId == null || elementId != null) { "源码节点身份不完整，请重新定位" }
    val revision = params["revision"]?.jsonPrimitive?.intOrNull
        ?: error("源码导航缺少 revision")
    val state = params["state"]?.let { JsonPayload(it.jsonObject) } ?: JsonPayload.empty()
    val label = nodeLabel ?: elementId?.let { componentLabel(view, it, source) }
        ?: source.symbol ?: "当前源码依据"
    return engine.freezeSourceBinding(view.ref, revision, elementId, nodeId, sourceId, label, state).copy(
        hostId = hostId,
        hostKind = hostKind,
        canPrevious = params["canPrevious"]?.jsonPrimitive?.booleanOrNull ?: false,
        canNext = params["canNext"]?.jsonPrimitive?.booleanOrNull ?: false,
    )
}

private fun componentLabel(view: ViewRecord, elementId: String, source: ResolvedSource): String {
    val element = view.document.spec.value["elements"]?.jsonObject?.get(elementId)?.jsonObject
    val props = element?.get("props")?.jsonObject
    val explicit = listOf("label", "title", "name").firstNotNullOfOrNull { key ->
        (props?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }
    if (explicit != null) return explicit
    return source.symbol ?: when (element?.get("type")?.jsonPrimitive?.contentOrNull) {
        "CodeExcerpt" -> "代码片段"
        "Demo" -> "示例源码"
        else -> "当前源码依据"
    }
}
