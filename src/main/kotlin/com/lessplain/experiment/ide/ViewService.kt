package com.lessplain.experiment.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.application.EDT
import com.lessplain.experiment.core.ViewEngine
import com.lessplain.experiment.core.ViewValidation
import com.lessplain.experiment.browser.BrowserCursor
import com.lessplain.experiment.model.*
import com.lessplain.experiment.storage.ViewStore
import kotlinx.serialization.json.*
import com.lessplain.ide.captureEditorAnchor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 项目权威状态。浏览器有各自视口；固定来源和提交校验只由此服务管理。 */
class ViewService(internal val project: Project, private val scope: CoroutineScope) : Disposable {
    val json = kotlinx.serialization.json.Json { encodeDefaults = true; explicitNulls = false }
    private val disposed = AtomicBoolean()
    private val store = ViewStore(Path.of(requireNotNull(project.basePath), ".idea", "lessplain-experiment", "views.db"))
    private val validation by lazy {
        ViewValidation(requireNotNull(javaClass.getResourceAsStream("/webview/catalog.json")) { "前端 catalog 未打包" }.bufferedReader().use { it.readText() })
    }
    private val engineDelegate = lazy {
        ViewEngine(store, { validation.catalog }, validation::validate, { validateViewSelection(project, it) }) {
            resolveViewSources(project, it)
        }
    }
    val engine by engineDelegate
    private val listeners = CopyOnWriteArrayList<(AppSnapshot, Long, String?, Boolean) -> Unit>()
    private val publication = Mutex()
    private var sequence = 0L
    @Volatile var currentOrigin = ViewOrigin()
        private set
    @Volatile var toolWindowRef: String? = null
        internal set
    private val browserHosts = ConcurrentHashMap<String, BrowserHostSession>()
    internal val sources = ViewSourceNavigation(project, this).also { Disposer.register(this, it) }
    internal val samples = SampleNavigation(project) { engine.releaseComponentSnapshot(it) }.also { Disposer.register(this, it) }
    internal val sourceQuestionDrafts = SourceQuestionDrafts()

    private class BrowserHostSession(
        val id: String,
        val kind: BrowserHostKind,
        val fixedTreeRootRef: String?,
        @Volatile var viewRef: String?,
        @Volatile var origin: ViewOrigin?,
        val sendNavigationRequest: (JsonObject) -> Unit,
        val setCursor: (BrowserCursor) -> Unit,
        val openNoteDetails: (ViewNote) -> Unit,
    )

    /** 每个 JCEF 浏览器注册独立 host；导航事件由绑定 host 单播。 */
    internal fun registerBrowserHost(
        hostId: String,
        kind: BrowserHostKind,
        fixedRef: String?,
        sendNavigationRequest: (JsonObject) -> Unit,
        setCursor: (BrowserCursor) -> Unit,
        openNoteDetails: (ViewNote) -> Unit,
    ): AutoCloseable {
        if (kind == BrowserHostKind.EDITOR) requireNotNull(fixedRef) { "编辑器宿主需要固定结果入口" }
        val initialRef = if (kind == BrowserHostKind.TOOL_WINDOW) fixedRef ?: toolWindowRef else fixedRef
        val fixedTreeRootRef = if (kind == BrowserHostKind.EDITOR) {
            requireNotNull(viewTreeRootRef(fixedRef, engine.snapshot().views)) { "编辑器结果已不存在" }
        } else null
        val initialOrigin = if (kind == BrowserHostKind.TOOL_WINDOW) {
            currentOrigin.takeIf { it.viewRef == initialRef } ?: ViewOrigin(viewRef = initialRef)
        } else {
            ViewOrigin(viewRef = initialRef)
        }
        val host = BrowserHostSession(
            hostId,
            kind,
            fixedTreeRootRef,
            initialRef,
            initialOrigin,
            sendNavigationRequest,
            setCursor,
            openNoteDetails,
        )
        check(browserHosts.putIfAbsent(hostId, host) == null) { "浏览器 hostId 重复" }
        return AutoCloseable {
            if (browserHosts.remove(hostId, host)) invalidateHost(hostId)
        }
    }

    /** 画布回报选择状态时，拒绝别的 view、旧 revision 或不存在的图节点。 */
    internal suspend fun updateNavigationState(hostId: String, params: JsonObject) {
        val host = browserHosts[hostId] ?: return
        val viewRef = params["ref"]?.jsonPrimitive?.contentOrNull ?: return
        val revision = params["revision"]?.jsonPrimitive?.intOrNull ?: return
        val elementId = params["elementId"]?.jsonPrimitive?.contentOrNull ?: return
        val nodeId = params["nodeId"]?.jsonPrimitive?.contentOrNull ?: return
        val canPrevious = params["canPrevious"]?.jsonPrimitive?.booleanOrNull ?: return
        val canNext = params["canNext"]?.jsonPrimitive?.booleanOrNull ?: return
        if (host.viewRef != viewRef || !engine.hasGraphNode(viewRef, revision, elementId, nodeId)) return
        val binding = withContext(Dispatchers.EDT) {
            sources.bindingForNavigation(hostId, viewRef, revision, elementId, nodeId)
        } ?: return
        if (!engine.isCurrentSourceBinding(binding)) return
        withContext(Dispatchers.EDT) {
            if (!disposed.get() && browserHosts[hostId] === host && host.viewRef == viewRef) {
                sources.updateNavigationState(hostId, viewRef, revision, elementId, nodeId, canPrevious, canNext)
            }
        }
    }

    /** Inlay 步进仅使用冻结的 host/view/node；不读取 currentOrigin 或当前活动编辑器。 */
    internal fun requestSourceStep(binding: EditorSourceBinding, direction: SourceStepDirection) {
        val hostId = binding.hostId ?: return
        launch {
            val host = browserHosts[hostId]
            val valid = !disposed.get() && host != null && host.kind == binding.hostKind &&
                host.viewRef == binding.viewRef && engine.isCurrentSourceBinding(binding)
            if (!valid) {
                withContext(Dispatchers.EDT) { sources.invalidateBinding(binding) }
                return@launch
            }
            host.sendNavigationRequest(sourceNavigationRequest(binding, direction))
        }
    }

    internal fun selectBrowserView(hostId: String, requestedRef: String?) {
        val host = browserHosts[hostId] ?: return
        val views = engine.snapshot().views
        val selectedRef = resolveBrowserViewSelection(host.kind, host.fixedTreeRootRef, requestedRef, views)
        if (selectedRef != null) {
            check(views.any { it.ref == selectedRef }) { "页面不存在：$selectedRef" }
        }
        host.viewRef = selectedRef
        host.origin = ViewOrigin(viewRef = selectedRef)
        if (host.kind == BrowserHostKind.TOOL_WINDOW) toolWindowRef = selectedRef
        if (host.kind == BrowserHostKind.TOOL_WINDOW) currentOrigin = ViewOrigin(viewRef = selectedRef)
        invalidateHost(hostId)
    }

    internal fun browserViewRef(hostId: String): String? = browserHosts[hostId]?.viewRef

    internal fun isBrowserShowing(hostId: String, viewRef: String): Boolean =
        browserHosts[hostId]?.viewRef == viewRef

    internal fun originForHost(hostId: String): ViewOrigin = browserHosts[hostId]?.origin ?: currentOrigin

    internal fun updateOrigin(hostId: String, origin: ViewOrigin) {
        val host = browserHosts[hostId] ?: return
        host.origin = origin
        if (host.kind == BrowserHostKind.TOOL_WINDOW) currentOrigin = origin
    }

    internal fun updateBrowserCursor(hostId: String, cursor: BrowserCursor) {
        val host = browserHosts[hostId] ?: return
        val update = { if (!disposed.get() && browserHosts[hostId] === host) host.setCursor(cursor) }
        if (ApplicationManager.getApplication().isDispatchThread) update()
        else ApplicationManager.getApplication().invokeLater(update)
    }

    internal suspend fun openNoteDetails(hostId: String, note: ViewNote) = withContext(Dispatchers.EDT) {
        val host = browserHosts[hostId] ?: return@withContext
        if (!disposed.get() && browserHosts[hostId] === host) host.openNoteDetails(note)
    }

    private fun invalidateHost(hostId: String) {
        val clear = { if (!disposed.get()) sources.invalidateHost(hostId) }
        if (ApplicationManager.getApplication().isDispatchThread) clear() else ApplicationManager.getApplication().invokeLater(clear)
    }

    private fun invalidateStaleBindings(snapshot: AppSnapshot) {
        val revisions = snapshot.views.associate { it.ref to it.revision }
        val clear = {
            if (!disposed.get()) {
                sourceQuestionDrafts.retainPending(snapshot)
                sources.invalidateRevisions(revisions)
                samples.invalidate(snapshot)
            }
        }
        if (ApplicationManager.getApplication().isDispatchThread) clear() else ApplicationManager.getApplication().invokeLater(clear)
    }

    suspend fun context(ref: String?): ContextResult {
        val origin = if (ref.isNullOrBlank() && currentOrigin.viewRef == null) {
            withContext(Dispatchers.EDT) { currentOrigin.copy(source = captureEditorAnchor(project)?.snapshot()) }
        } else currentOrigin
        return withContext(Dispatchers.IO) { engine.context(ref, origin) }
    }

    fun updateOrigin(origin: ViewOrigin) { currentOrigin = origin }
    fun subscribe(listener: (AppSnapshot, Long, String?, Boolean) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
    suspend fun published(): Pair<AppSnapshot, Long> = publication.withLock {
        withContext(Dispatchers.IO) { engine.snapshot() } to ++sequence
    }
    suspend fun changed(select: String? = null, showHistory: Boolean = false) = publication.withLock {
        val snapshot = withContext(Dispatchers.IO) { engine.snapshot() }
        invalidateStaleBindings(snapshot)
        val revision = ++sequence
        if (!disposed.get()) listeners.forEach { it(snapshot, revision, select, showHistory) }
    }
    fun launch(task: suspend CoroutineScope.() -> Unit): Job = scope.launch(Dispatchers.IO, block = task)
    suspend fun saved(ref: String) {
        toolWindowRef = ref
        browserHosts.values.filter { it.kind == BrowserHostKind.TOOL_WINDOW }.forEach {
            it.viewRef = ref
            it.origin = ViewOrigin(viewRef = ref)
        }
        changed(ref)
        withContext(Dispatchers.EDT) { ToolWindowManager.getInstance(project).getToolWindow(VIEW_TOOL_WINDOW_ID)?.show() }
    }
    fun showHistory() {
        toolWindowRef = null
        browserHosts.values.filter { it.kind == BrowserHostKind.TOOL_WINDOW }.forEach {
            it.viewRef = null
            it.origin = ViewOrigin()
        }
        sources.clear()
        launch { changed(showHistory = true) }
    }
    fun openEditor(ref: String) {
        launch {
            val view = engine.snapshot().views.firstOrNull { it.ref == ref } ?: return@launch
            withContext(Dispatchers.EDT) {
                val manager = FileEditorManager.getInstance(project)
                val existing = manager.openFiles.filterIsInstance<ViewVirtualFile>().firstOrNull { it.ref == ref }
                manager.openFile(existing ?: ViewVirtualFile(view), true)
            }
        }
    }
    override fun dispose() {
        disposed.set(true)
        listeners.clear()
        browserHosts.clear()
        sourceQuestionDrafts.clear()
        if (engineDelegate.isInitialized()) engine.clearComponentSnapshots()
        store.close()
    }
}

/** 只构造协议体；发送方由冻结 binding 对应的 BrowserHostSession 提供。 */
internal fun sourceNavigationRequest(
    binding: EditorSourceBinding,
    direction: SourceStepDirection,
    id: String = java.util.UUID.randomUUID().toString(),
): JsonObject = buildJsonObject {
    put("navigationRequest", buildJsonObject {
        put("id", id)
        put("direction", direction.wireValue)
        put("viewRef", binding.viewRef)
        put("revision", binding.revision)
        put("elementId", binding.elementId)
        put("nodeId", binding.nodeId)
    })
}
