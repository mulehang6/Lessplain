package com.lessplain.storage

import java.security.MessageDigest

/** 文档与选区使用相同编码，不依赖磁盘保存状态。 */
internal fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
