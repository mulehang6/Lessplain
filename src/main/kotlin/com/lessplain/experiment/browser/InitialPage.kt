package com.lessplain.experiment.browser

import kotlinx.serialization.json.JsonObject

/** 首个 HTML 解析时就有 IDE 主题；不等待 bridge ready，CEF 线程也不读取 Swing 颜色。 */
internal fun initialPage(bytes: ByteArray, theme: JsonObject, boot: JsonObject): ByteArray {
    fun safeJson(value: JsonObject) = value.toString().replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    val script = "<script>window.__lessplainBoot=" + safeJson(boot) +
        ";Object.entries(" + safeJson(theme) + ").forEach(([name,value])=>document.documentElement.style.setProperty(name,value));</script>"
    val html = bytes.toString(Charsets.UTF_8)
    require(html.contains("<head>")) { "页面缺少初始化入口" }
    val charset = Regex("<meta\\s+charset=[^>]*>", RegexOption.IGNORE_CASE).find(html)
    val prepared = if (charset == null) html.replaceFirst("<head>", "<head><meta charset=\"UTF-8\">" + script)
    else html.substring(0, charset.range.last + 1) + script + html.substring(charset.range.last + 1)
    return prepared.toByteArray(Charsets.UTF_8)
}
