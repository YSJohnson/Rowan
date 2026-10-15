package com.xiaoqi.companion.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 从 `/v1/models` 风格的响应体里抠出模型 ID 列表。
 *
 * 不按严格 schema 解析是有意为之：OpenAI 返回 `{"data":[{"id":...}]}`、Anthropic 条目里多一层
 * `display_name`、Google 用 `{"models":[{"name":"models/gemini-pro"}]}`，还有服务商直接返回字符串
 * 数组。逐个适配不如按"容器 key + ID 字段"宽松提取 —— 漏识别的代价只是用户手填一次模型名，
 * 而误报的代价是把不存在的模型塞进下拉框。
 */
internal object LlmModelListParser {

    /** 各家把模型数组放在哪个 key 下 —— OpenAI/Anthropic 用 data，Google 用 models。 */
    private val CONTAINER_KEYS = listOf("data", "models", "result", "items")

    /** 单个模型条目里承载 ID 的字段名。 */
    private val ID_KEYS = listOf("id", "name", "model")

    /** 超过这个长度的"模型 ID"一定是别的东西（描述文案、URL）。 */
    private const val MAX_ID_LENGTH = 128

    fun parse(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return emptyList()
        val ids = LinkedHashSet<String>()

        fun collect(element: JsonElement) {
            when (element) {
                is JsonArray -> element.forEach(::collect)
                is JsonObject -> {
                    CONTAINER_KEYS.forEach { key -> element[key]?.let(::collect) }
                    ID_KEYS.forEach { key ->
                        val value = element[key]
                        if (value is JsonPrimitive && value.isString) ids += value.content
                    }
                }
                is JsonPrimitive -> if (element.isString) ids += element.content
            }
        }

        CONTAINER_KEYS.forEach { key -> root[key]?.let(::collect) }
        return ids
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_ID_LENGTH && !it.any(Char::isWhitespace) }
    }
}
