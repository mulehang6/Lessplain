package com.master.lessplain.model

/** The public protocol version is an identifier, never a decimal number. */
const val UNDERSTANDING_SCHEMA_VERSION = "1.4"

/** Retains the legacy capability revision and request hash representation inside the plugin. */
internal const val UNDERSTANDING_SCHEMA_REVISION = 4

/** Old protocol identifiers keep their original feature gates and idempotent request hashes. */
internal fun understandingSchemaRevision(version: String): Int = when (version) {
    UNDERSTANDING_SCHEMA_VERSION, "4" -> UNDERSTANDING_SCHEMA_REVISION
    "3" -> 3
    else -> throw UnderstandingInputException("不支持的 schemaVersion：$version；请使用字符串 \"$UNDERSTANDING_SCHEMA_VERSION\"")
}
