package com.lessplain.experiment.browser

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.cef.browser.CefBrowser
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

/** 不依赖页面 JS 的内部模式探针。回调不阻塞，不读取源码、问题或 bridge payload。 */
internal class BrowserDiagnostics(
    private val browser: JBCefBrowser,
    private val hostId: String,
    private val showing: () -> Boolean,
) : Disposable {
    private val log = Logger.getInstance(BrowserDiagnostics::class.java)
    private val events = BrowserDiagnosticEvents()
    private val capturing = AtomicBoolean()
    private val captures = AtomicInteger()
    @Volatile private var disposed = false
    private val heartbeat = HeartbeatWatchdog()
    private val saving = AtomicBoolean()
    private val saved = AtomicInteger()
    private val watchdog = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
        if (!disposed && heartbeat.poll(showing())) anomaly("heartbeat-missed")
    }, 2, 2, TimeUnit.SECONDS)
    private val life = object : CefLifeSpanHandlerAdapter() {
        override fun onAfterCreated(browser: CefBrowser) = mark("browser-created", "browserId" to browser.identifier)
        override fun onBeforeClose(browser: CefBrowser) = mark("browser-closing", "browserId" to browser.identifier)
    }
    private val requests = object : CefRequestHandlerAdapter() {
        override fun onRenderProcessTerminated(browser: CefBrowser, status: CefRequestHandler.TerminationStatus, errorCode: Int, errorString: String?) {
            mark("renderer-terminated", "status" to status.name, "errorCode" to errorCode, "browserId" to browser.identifier)
            anomaly("renderer-terminated")
        }
    }

    /** 只接收有界数值字段；正常批次只进入内存，不向 idea.log 写每条事件。 */
    fun receive(params: JsonObject) {
        if (disposed) return
        heartbeat.receive(params["visible"]?.jsonPrimitive?.booleanOrNull == true)
        val batch = params["events"] as? JsonArray ?: return
        for (item in batch.takeLast(128)) {
            val objectValue = item as? JsonObject ?: continue
            val kind = objectValue["kind"]?.jsonPrimitive?.contentOrNull ?: continue
            if (!kind.matches(Regex("[a-z-]{1,40}"))) continue
            val fields = objectValue.entries.take(20).mapNotNull { (key, value) ->
                val number = (value as? JsonPrimitive)?.doubleOrNull
                if (number?.isFinite() == true && key.matches(Regex("[A-Za-z]{1,30}"))) key to JsonPrimitive(number) else null
            }.toMap()
            events.record("js-$kind", fields)
        }
    }

    private fun anomaly(reason: String) {
        if (disposed || saved.get() >= 10 || !saving.compareAndSet(false, true)) return
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                if (!disposed) {
                    val file = DiagnosticSnapshots.save(hostId, reason, events.snapshot())
                    saved.incrementAndGet()
                    log.info("Lessplain diagnostic snapshot: $file")
                }
            } catch (failure: Exception) { log.warn("Lessplain diagnostic snapshot failed", failure) }
            finally { saving.set(false) }
        }
    }

    init {
        try {
            browser.jbCefClient.addLifeSpanHandler(life, browser.cefBrowser)
            browser.jbCefClient.addRequestHandler(requests, browser.cefBrowser)
            mark("diagnostics-installed")
        } catch (failure: Exception) {
            watchdog.cancel(false)
            runCatching { browser.jbCefClient.removeLifeSpanHandler(life, browser.cefBrowser) }
            runCatching { browser.jbCefClient.removeRequestHandler(requests, browser.cefBrowser) }
            throw failure
        }
    }

    fun mark(kind: String, vararg fields: Pair<String, Any>) {
        if (disposed) return
        val event = events.record(kind, fields.associate { (key, value) -> key to when (value) {
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString().take(120))
        } })
        log.info("Lessplain JCEF [$hostId] $event")
    }

    /** CDP 回包在后台异步等待；限制并发和次数，失联时也不会阻塞 EDT。 */
    suspend fun capture() {
        if (disposed || !capturing.compareAndSet(false, true)) return
        try {
            if (captures.incrementAndGet() > 10) { mark("capture-limit"); return }
            val cef = browser.cefBrowser
            mark("browser-state", "browserId" to cef.identifier, "closed" to cef.isClosed, "closing" to cef.isClosing, "loading" to cef.isLoading)
            val client = cef.devToolsClient ?: run { mark("devtools-client-unavailable"); return }
            for ((command, parameters) in listOf(
                "Browser.getVersion" to null,
                "Page.getFrameTree" to null,
                "Runtime.evaluate" to """{"expression":"JSON.stringify({ready:document.readyState,events:window.__lessplainDiagnostics?.events().slice(-30)??[]})","returnByValue":true}""",
            )) {
                if (disposed) return
                val start = System.nanoTime()
                try {
                    val result = withTimeout(3000) {
                        val future = client.executeDevToolsMethod(command, parameters)
                        suspendCancellableCoroutine<String> { continuation ->
                            future.whenComplete { value, error ->
                                if (error == null) continuation.resume(value) else continuation.resumeWithException(error)
                            }
                            continuation.invokeOnCancellation { future.cancel(false) }
                        }
                    }
                    mark("probe-completed", "command" to command, "durationMs" to (System.nanoTime() - start) / 1_000_000)
                    if (command == "Runtime.evaluate" && result.length <= 100_000) {
                        val value = Json.parseToJsonElement(result).jsonObject["result"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
                        // JS 端只输出有界时序；不执行来自页面或用户的表达式。
                        if (value != null && value.length <= 32_000) log.info("Lessplain JCEF [$hostId] js-diagnostics=$value")
                    }
                } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                    mark("probe-timeout", "command" to command)
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { mark("probe-failed", "command" to command, "type" to failure.javaClass.simpleName) }
            }
            log.info("Lessplain JCEF [$hostId] diagnostic-snapshot=${events.snapshot()}")
        } finally { capturing.set(false) }
    }

    override fun dispose() {
        if (disposed) return
        mark("diagnostics-detaching")
        disposed = true
        watchdog.cancel(false)
        browser.jbCefClient.removeLifeSpanHandler(life, browser.cefBrowser)
        browser.jbCefClient.removeRequestHandler(requests, browser.cefBrowser)
    }
}
