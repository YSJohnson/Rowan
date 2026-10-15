package com.xiaoqi.companion.core.insight

import com.xiaoqi.companion.core.logging.AppLogger
import com.xiaoqi.companion.core.logging.LogTags
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive

/**
 * 解析洞察 prompt 的 JSON 输出 — 3 层容错，逐层降级：
 *
 * 1. 标准 JSON（含 markdown code fence 剥离）
 * 2. 归一化修复：单引号 / 中文标点 / 尾逗号 / 前后杂文字 / 裸对象包裹
 * 3. 正则提取：完全不是 JSON 时从自由文本里抠 headline/body/confidence
 *
 * 任何一层成功都返回 [InsightDraft] 列表；全部失败 → 空 list + warn 日志。
 * 云端模型基本一轮命中第 1 层，后两层是防供应商侧截断或模型不守格式。
 */
object InsightOutputParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parsePatternDetectOutput(raw: String): List<InsightDraft> {
        val cleaned = raw
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()
        if (cleaned.isBlank()) return emptyList()

        tryParseAsJsonArray(cleaned).takeIf { it.isNotEmpty() }?.let { return it }

        tryNormalizeJson(cleaned)?.let { normalized ->
            tryParseAsJsonArray(normalized).takeIf { it.isNotEmpty() }?.let { return it }
        }

        val extracted = runCatching { regexExtractInsights(cleaned) }.getOrElse { error ->
            AppLogger.warn(
                LogTags.Llm,
                "insight_regex_layer_crashed",
                "cause" to (error.message ?: error::class.simpleName.orEmpty()),
            )
            emptyList()
        }
        if (extracted.isNotEmpty()) return extracted

        AppLogger.warn(
            LogTags.Llm,
            "insight_all_parse_layers_failed",
            "rawLength" to raw.length,
            "rawPreview" to raw.take(300),
        )
        return emptyList()
    }

    /** Layer 1/2 共用：把字符串当 JSON 数组解析，逐条转 [InsightDraft]。 */
    private fun tryParseAsJsonArray(text: String): List<InsightDraft> {
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val array = parsed as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val headline = obj["headline"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
            if (headline.isEmpty()) return@mapNotNull null
            val body = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val evidenceIds = (obj["evidence_ids"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()
            val confidence = obj["confidence"]?.jsonPrimitive?.floatOrZero() ?: 0f
            val relevanceWindow = obj["relevanceWindow"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: "近 7 天"
            InsightDraft(
                triggerType = "PATTERN_DETECT",
                category = obj["category"]?.jsonPrimitive?.contentOrNull?.trim() ?: "情绪",
                headline = headline,
                bodyMarkdown = body,
                relevanceWindow = relevanceWindow,
                confidence = confidence.coerceIn(0f, 1f),
                evidenceMessageIds = evidenceIds,
                evidenceMemoryIds = emptyList(),
                evidenceMoodSnapshotIds = emptyList(),
            )
        }
    }

    /**
     * Layer 2：修复常见 JSON 格式问题——单引号、中文标点、尾逗号、前后杂文字、缺方括号的裸对象。
     * 返回 null 表示原样太离谱，无法归一化。
     */
    private fun tryNormalizeJson(raw: String): String? {
        var s = raw.trim()

        val firstBracket = s.indexOf('[')
        val lastBracket = s.lastIndexOf(']')
        if (firstBracket >= 0 && lastBracket > firstBracket) {
            s = s.substring(firstBracket, lastBracket + 1)
        } else if (firstBracket < 0 && lastBracket < 0) {
            if (!s.contains("{")) return null
            val firstBrace = s.indexOf('{')
            val lastBrace = s.lastIndexOf('}')
            if (firstBrace < 0 || lastBrace <= firstBrace) return null
            s = "[${s.substring(firstBrace, lastBrace + 1)}]"
        }

        s = s.replace("'", "\"")
            .replace("，", ",")
            .replace("：", ":")
            .replace("；", ";")
            .replace(Regex(",\\s*([}\\]])"), "$1")

        val endBracket = s.lastIndexOf(']')
        if (endBracket >= 0 && endBracket < s.length - 1) {
            s = s.substring(0, endBracket + 1)
        }

        return s.ifBlank { null }
    }

    /**
     * Layer 3：纯字符串提取。Android ICU 正则兼容性极差，全部用 indexOf/substring 实现，
     * 扫描文本中所有 `"headline"` / `"body"` / `"confidence"` 键值对凑成 draft。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun regexExtractInsights(text: String): List<InsightDraft> {
        val results = mutableListOf<InsightDraft>()

        var currentHeadline: String? = null
        var currentBody = ""
        var currentConfidence = 0.5f

        fun flushDraft() {
            val headline = currentHeadline?.trim()?.takeIf { it.isNotBlank() } ?: return
            results.add(
                InsightDraft(
                    triggerType = "PATTERN_DETECT",
                    category = "情绪",
                    headline = headline,
                    bodyMarkdown = currentBody.trim(),
                    relevanceWindow = "近 7 天",
                    confidence = currentConfidence.coerceIn(0f, 1f),
                )
            )
            currentHeadline = null
            currentBody = ""
            currentConfidence = 0.5f
        }

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val headlineValue = extractQuotedValue(line, "headline")
                ?: extractAfterColon(line, listOf("headline"))
            if (headlineValue != null) {
                if (currentHeadline != null) flushDraft()
                currentHeadline = headlineValue
                continue
            }
            val bodyValue = extractQuotedValue(line, "body")
                ?: extractAfterColon(line, listOf("body"))
            if (bodyValue != null) {
                currentBody = bodyValue
                continue
            }
            extractFloatValue(line, "confidence")?.let {
                currentConfidence = it
                continue
            }

            val titleValue = extractAfterColon(line, listOf("标题", "title"))
            if (titleValue != null) {
                if (currentHeadline != null) flushDraft()
                currentHeadline = titleValue
                continue
            }
            extractAfterColon(line, listOf("内容", "描述", "content"))?.let { currentBody = it }
        }

        flushDraft()
        return results
    }

    private fun extractQuotedValue(line: String, key: String): String? {
        val patterns = listOf(
            "\"$key\"\\s*:\\s*\"",
            "'$key'\\s*:\\s*'",
            "$key\\s*:\\s*\"",
        )
        for (pattern in patterns) {
            val idx = line.indexOf(pattern)
            if (idx < 0) continue
            val start = idx + pattern.length
            val endQuote = line.indexOf('"', start)
            if (endQuote > start) return line.substring(start, endQuote)
            val endSingle = line.indexOf('\'', start)
            if (endSingle > start) return line.substring(start, endSingle)
            val endComma = line.indexOf(',', start)
            if (endComma > start) return line.substring(start, endComma).trim()
            return line.substring(start).trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun extractFloatValue(line: String, key: String): Float? {
        val patterns = listOf("\"$key'\\s*:", "'$key'\\s*:", "$key\\s*:")
        for (pattern in patterns) {
            val idx = line.indexOf(pattern)
            if (idx < 0) continue
            val after = line.substring(idx + pattern.length).trim()
            val number = after.split(',', '}', ' ', '\t').firstOrNull()?.trim() ?: continue
            return number.toFloatOrNull()
        }
        return null
    }

    private fun extractAfterColon(line: String, keys: List<String>): String? {
        for (key in keys) {
            val keyIdx = line.indexOf(key)
            if (keyIdx < 0) continue
            val colon = line.indexOfAny(charArrayOf(':', '：'), keyIdx + key.length)
            if (colon < 0) continue
            val trimmed = line.substring(colon + 1).trim()
            val unquoted = trimmed.removePrefix("\"").removePrefix("'").removePrefix("：").removePrefix(":")
            val end = unquoted.indexOfAny(charArrayOf(',', '\n', '"', '\''))
            val result = if (end > 0) unquoted.substring(0, end) else unquoted
            return result.trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun JsonPrimitive.floatOrZero(): Float = runCatching { float }.getOrDefault(0f)
}
