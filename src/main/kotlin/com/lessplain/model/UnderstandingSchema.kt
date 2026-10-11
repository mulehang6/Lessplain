package com.lessplain.model

/** 公开协议版本是标识符，不是十进制数值。 */
const val UNDERSTANDING_SCHEMA_VERSION = "1.4"

/** 在插件内部保留旧能力修订号和请求哈希表示。 */
internal const val UNDERSTANDING_SCHEMA_REVISION = 4

/** 旧协议标识保留原功能限制及幂等请求哈希。 */
internal fun understandingSchemaRevision(version: String): Int = when (version) {
    UNDERSTANDING_SCHEMA_VERSION, "4" -> UNDERSTANDING_SCHEMA_REVISION
    "3" -> 3
    else -> throw UnderstandingInputException("不支持的 schemaVersion：$version；请使用字符串 \"$UNDERSTANDING_SCHEMA_VERSION\"")
}
