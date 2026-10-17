package com.lessplain.experiment.browser

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.*
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal const val APP_URL = "https://lessplain.local/index.html"

/** URL 到 classpath 的纯映射，不访问磁盘或响应任意网络请求。 */
internal fun resourcePath(url: String): String? = runCatching {
    val uri = URI(url)
    if (uri.scheme != "https" || uri.host != "lessplain.local" || uri.port != -1 || uri.userInfo != null) return null
    val path = uri.path
    if (!path.startsWith("/") || path.contains("..") || path.contains('\\') || path.contains('\u0000')) return null
    if (path == "/") "webview/index.html" else "webview" + path
}.getOrNull()

internal fun resourceMime(path: String) = when (path.substringAfterLast('.')) {
    "html" -> "text/html"
    "js" -> "text/javascript"
    "css" -> "text/css"
    "json" -> "application/json"
    "svg" -> "image/svg+xml"
    "woff2" -> "font/woff2"
    else -> "application/octet-stream"
}

/** 每个请求独立读取静态字节，取消和浏览器关闭会释放引用。 */
internal class StaticResources(private val preparePage: (ByteArray) -> ByteArray = { it }) : CefRequestHandlerAdapter(), AutoCloseable {
    private val requests = ConcurrentHashMap.newKeySet<BytesHandler>()
    @Volatile private var closed = false
    private val resourceRequests = object : CefResourceRequestHandlerAdapter() {
        override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest): CefResourceHandler = synchronized(requests) {
            val path = resourcePath(request.url)
            val bytes = if (!closed && path != null) javaClass.classLoader.getResourceAsStream(path)?.use {
                val raw = it.readBytes()
                if (path == "webview/index.html") preparePage(raw) else raw
            } else null
            BytesHandler(bytes, path?.let(::resourceMime) ?: "text/plain") { requests.remove(it) }.also { if (!closed) requests.add(it) }
        }
    }
    override fun getResourceRequestHandler(
        browser: CefBrowser?, frame: CefFrame?, request: CefRequest, isNavigation: Boolean, isDownload: Boolean,
        requestInitiator: String?, disableDefaultHandling: BoolRef,
    ): CefResourceRequestHandler {
        disableDefaultHandling.set(true)
        return resourceRequests
    }
    override fun onBeforeBrowse(browser: CefBrowser?, frame: CefFrame?, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean =
        frame?.isMain == true && resourcePath(request.url) == null

    override fun close() = synchronized(requests) {
        closed = true
        requests.toList().forEach { it.cancel() }
        requests.clear()
    }
}

private class BytesHandler(
    private var bytes: ByteArray?,
    private val mime: String,
    private val release: (BytesHandler) -> Unit,
) : CefResourceHandlerAdapter() {
    private var offset = 0
    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean { callback.Continue(); return true }
    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef) {
        response.status = if (bytes == null) 404 else 200
        response.statusText = if (bytes == null) "Not Found" else "OK"
        response.mimeType = mime
        responseLength.set(bytes?.size ?: 0)
        response.setHeaderByName("Content-Security-Policy", "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; worker-src 'self'; frame-src 'self' about:; connect-src 'none'; form-action 'none'; base-uri 'none';", true)
        response.setHeaderByName("X-Content-Type-Options", "nosniff", true)
        response.setHeaderByName("Cache-Control", "no-store", true)
    }
    override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback): Boolean {
        val source = bytes
        if (source == null || offset >= source.size) { cancel(); bytesRead.set(0); return false }
        val length = minOf(source.size - offset, bytesToRead, dataOut.size)
        source.copyInto(dataOut, 0, offset, offset + length); offset += length
        bytesRead.set(length)
        return true
    }
    override fun cancel() { bytes = null; release(this) }
}
