package com.lessplain.experiment.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 任意 JSON 对象的公开序列化适配，避免 MCP schema 展开 JsonElement 递归类型。 */
@Serializable(with = JsonPayloadSerializer::class)
data class JsonPayload(val value: JsonObject) {
    companion object {
        fun empty(): JsonPayload = JsonPayload(JsonObject(emptyMap()))
    }
}

/** 外层参数 schema 描述开放对象；完整 spec 由构建时导出的 catalog schema 校验。 */
object JsonPayloadSerializer : KSerializer<JsonPayload> {
    override val descriptor = buildClassSerialDescriptor("com.lessplain.experiment.JsonPayload")

    override fun serialize(encoder: Encoder, value: JsonPayload) {
        require(encoder is JsonEncoder) { "页面只支持 JSON 编码" }
        encoder.encodeJsonElement(value.value)
    }

    override fun deserialize(decoder: Decoder): JsonPayload {
        require(decoder is JsonDecoder) { "页面只支持 JSON 解码" }
        return JsonPayload(decoder.decodeJsonElement().jsonObject)
    }
}
