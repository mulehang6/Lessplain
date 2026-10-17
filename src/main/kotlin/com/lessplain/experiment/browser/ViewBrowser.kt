package com.lessplain.experiment.browser

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.lessplain.experiment.ide.ViewService
import com.lessplain.experiment.ide.showNoteDetails
import com.lessplain.experiment.model.BrowserHostKind
import com.lessplain.experiment.model.ViewNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import java.awt.Cursor
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import com.intellij.util.ui.UIUtil

/** 一个宿主一个浏览器；切换结果不重载页面，关闭宿主释放查询、请求和异步任务。 */
internal class ViewBrowser(project: Project, private val ref: String? = null) : Disposable.Parent {
    val component: JComponent = object : JPanel(BorderLayout()), UiDataProvider {
        override fun uiDataSnapshot(sink: DataSink) {
            if (ApplicationManager.getApplication().isInternal && !disposed && browser != null) {
                val captured = browser
                sink.set(LESSPLAIN_DEVTOOLS, Runnable {
                    if (!disposed && browser === captured) {
                        val start = System.nanoTime()
                        diagnostics?.mark("devtools-requested", "thread" to Thread.currentThread().name)
                        try { captured?.openDevtools() }
                        finally { diagnostics?.mark("devtools-returned", "durationMs" to (System.nanoTime() - start) / 1_000_000) }
                    }
                })
                sink.set(LESSPLAIN_DIAGNOSTICS, Runnable { if (!disposed) service.launch { diagnostics?.capture() } })
            }
        }
    }
    private val service = project.getService(ViewService::class.java)
    private val json = service.json
    private val hostId = UUID.randomUUID().toString()
    private val hostKind = if (ref == null) BrowserHostKind.TOOL_WINDOW else BrowserHostKind.EDITOR
    private val initialRef = if (hostKind == BrowserHostKind.TOOL_WINDOW) service.toolWindowRef else ref
    private val boot = buildJsonObject {
        put("hostId", hostId)
        put("host", hostKind.wireValue)
        initialRef?.let { put("ref", it) }
        put("title", "Lessplain")
        put("diagnostics", ApplicationManager.getApplication().isInternal)
    }
    @Volatile private var pageTheme = theme()
    private val resources = StaticResources { initialPage(it, pageTheme, boot) }
    private var browser: JBCefBrowser? = null
    private var query: JBCefJSQuery? = null
    private var subscription: AutoCloseable? = null
    private var hostRegistration: AutoCloseable? = null
    private var contextMenuHandler: CefContextMenuHandlerAdapter? = null
    private var cursorBoundaryListener: MouseAdapter? = null
    private var diagnostics: BrowserDiagnostics? = null
    private val jobs = ConcurrentHashMap.newKeySet<Job>()
    @Volatile private var disposed = false
    @Volatile private var generation = 0L
    private val commands = ViewCommands(project, service, hostId, hostKind)
    private val outbox = BridgeOutbox { raw ->
        val page = generation
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && page == generation) {
                if (raw.length <= 64000) browser?.cefBrowser?.executeJavaScript("window.__lessplainReceive&&window.__lessplainReceive(" + raw + ");", APP_URL, 0)
                else {
                    val chunks = raw.chunked(64000)
                    val packet = java.util.UUID.randomUUID().toString()
                    chunks.forEachIndexed { index, chunk ->
                        val arguments = json.encodeToJsonElement(listOf(JsonPrimitive(packet), JsonPrimitive(index), JsonPrimitive(chunks.size), JsonPrimitive(chunk))).toString().removeSurrounding("[", "]")
                        browser?.cefBrowser?.executeJavaScript("window.__lessplainReceiveChunk&&window.__lessplainReceiveChunk(" + arguments + ");", APP_URL, 0)
                    }
                }
            }
        }
    }
    init {
        try {
            component.background = UIUtil.getPanelBackground()
            if (!JBCefApp.isSupported()) {
                component.add(JLabel("Lessplain 需要包含 JCEF 的 JetBrains Runtime。"), BorderLayout.CENTER)
            } else {
                val created = JBCefBrowser.createBuilder().setOffScreenRendering(false).build()
                browser = created
                Disposer.register(this, created)
                if (ApplicationManager.getApplication().isInternal) diagnostics = BrowserDiagnostics(created, hostId)
                created.setPageBackgroundColor(pageTheme["--background"]?.jsonPrimitive?.content ?: "#ffffff")
                val noNativeContextMenu = object : CefContextMenuHandlerAdapter() {
                    override fun onBeforeContextMenu(
                        browser: CefBrowser,
                        frame: CefFrame,
                        params: CefContextMenuParams,
                        model: CefMenuModel,
                    ) {
                        model.clear()
                    }
                }
                contextMenuHandler = noNativeContextMenu
                created.jbCefClient.addContextMenuHandler(noNativeContextMenu, created.cefBrowser)
                val cefComponent = created.cefBrowser.uiComponent
                val resetCursorOnBoundary = object : MouseAdapter() {
                    override fun mouseEntered(event: MouseEvent) = setBrowserCursor(created, BrowserCursor.DEFAULT)
                    override fun mouseExited(event: MouseEvent) = setBrowserCursor(created, BrowserCursor.DEFAULT)
                }
                cursorBoundaryListener = resetCursorOnBoundary
                created.component.addMouseListener(resetCursorOnBoundary)
                cefComponent.addMouseListener(resetCursorOnBoundary)
                hostRegistration = service.registerBrowserHost(
                    hostId = hostId,
                    kind = hostKind,
                    fixedRef = ref,
                    sendNavigationRequest = { request -> outbox.event(generation, request.toString()) },
                    setCursor = { cursor -> setBrowserCursor(created, cursor) },
                    openNoteDetails = { note -> service.showNoteDetails(created.component, note) },
                )
                val base: JBCefBrowserBase = created
                val jsQuery = JBCefJSQuery.create(base)
                query = jsQuery
                Disposer.register(this, jsQuery)
                jsQuery.addHandler { raw ->
                    if (!disposed && raw.length <= 1_500_000) {
                        val request = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                        val page = request?.get("generation")?.jsonPrimitive?.longOrNull
                        if (request != null && page == generation && jobs.size < 64) {
                            val job = service.launch {
                                val id = request["id"]?.jsonPrimitive?.content ?: return@launch
                                val method = request.getValue("method").jsonPrimitive.content
                                val reply = try {
                                    val result = commands.execute(method, request["params"]?.jsonObject ?: JsonObject(emptyMap()))
                                    buildJsonObject { put("id", id); put("result", result) }
                                } catch (failure: CancellationException) { throw failure }
                                catch (failure: Exception) { buildJsonObject { put("id", id); put("error", failure.message ?: "操作失败") } }
                                outbox.reply(page, reply.toString())
                                if (method == "ready") outbox.ready(page)
                            }
                            jobs += job
                            job.invokeOnCompletion { jobs -= job }
                        }
                    }
                    null
                }
                created.jbCefClient.addRequestHandler(resources, created.cefBrowser)
                created.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                    override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transitionType: org.cef.network.CefRequest.TransitionType) {
                        if (frame.isMain) {
                            diagnostics?.mark("load-start")
                            generation = outbox.newPage()
                            jobs.toList().forEach(Job::cancel)
                            setBrowserCursor(created, BrowserCursor.DEFAULT)
                        }
                    }
                    override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                        if (!frame.isMain || disposed) return
                        diagnostics?.mark("load-end", "httpStatus" to httpStatusCode)
                        setBrowserCursor(created, BrowserCursor.DEFAULT)
                        val expression = jsQuery.inject("JSON.stringify(request)")
                        browser.executeJavaScript(
                            "window.__lessplainBoot=" + boot + ";window.__lessplainQuery=function(raw){const request=JSON.parse(raw);request.generation=" + generation + ";" + expression + "};window.dispatchEvent(new Event('lessplain-connected'));",
                            APP_URL, 0,
                        )
                    }
                }, created.cefBrowser)
                subscription = service.subscribe { snapshot, sequence, selection, showHistory ->
                    outbox.snapshot(buildJsonObject {
                        put("snapshot", json.encodeToJsonElement(snapshot))
                        put("sequence", sequence)
                        // 编辑器标签页保持自己的结果选择。
                        if (ref == null) selection?.let { put("select", it) }
                        if (ref == null && showHistory) put("showHistory", true)
                    }.toString())
                }
                project.messageBus.connect(this).subscribe(LafManagerListener.TOPIC, LafManagerListener {
                    pageTheme = theme()
                    component.background = UIUtil.getPanelBackground()
                    outbox.snapshot(buildJsonObject { put("theme", pageTheme) }.toString())
                })
                component.add(created.component, BorderLayout.CENTER)
                created.loadURL(APP_URL)
            }
        } catch (failure: Exception) {
            // 构造中断时尚未交给宿主所有者，仍须释放已经登记的 JCEF 子资源。
            Disposer.dispose(this)
            throw failure
        }
    }
    override fun beforeTreeDispose() {
        if (disposed) return
        disposed = true
        jobs.toList().forEach(Job::cancel)
        jobs.clear()
        diagnostics?.dispose()
        subscription?.close()
        hostRegistration?.close()
        browser?.let { created ->
            cursorBoundaryListener?.let {
                created.component.removeMouseListener(it)
                created.cefBrowser.uiComponent.removeMouseListener(it)
            }
            contextMenuHandler?.let { created.jbCefClient.removeContextMenuHandler(it, created.cefBrowser) }
        }
        val targets = listOfNotNull(browser?.component, browser?.cefBrowser?.uiComponent, component)
        val clearCursor = { targets.forEach { it.cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR) } }
        if (ApplicationManager.getApplication().isDispatchThread) clearCursor()
        else ApplicationManager.getApplication().invokeLater(clearCursor)
    }

    override fun dispose() {
        resources.close()
        outbox.close()
    }

    private fun setBrowserCursor(created: JBCefBrowser, cursor: BrowserCursor) {
        val update = {
            if (!disposed && browser === created) {
                val awtCursor = cursor.awtCursor()
                created.cefBrowser.uiComponent.cursor = awtCursor
                created.component.cursor = awtCursor
                component.cursor = awtCursor
            }
        }
        if (ApplicationManager.getApplication().isDispatchThread) update()
        else ApplicationManager.getApplication().invokeLater(update)
    }
}
