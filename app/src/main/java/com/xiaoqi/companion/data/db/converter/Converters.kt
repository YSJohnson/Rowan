package com.xiaoqi.companion.data.db.converter

import androidx.room.TypeConverter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Enums

enum class MessageRole { USER, ASSISTANT, SYSTEM }
enum class MemoryType { FACT, EPISODE, PROCEDURAL }
enum class SummaryType { DAILY, SESSION, TOPIC, PROJECT, RELATIONSHIP }
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * 对话提供商。枚举名会作为字符串持久化进 DataStore，**改名等于丢用户配置**。
 *
 * [usesOpenAiProtocol] 是协议判别位：true 走 OpenAI 兼容 Chat Completions（Bearer 鉴权），
 * false 走 Anthropic Messages（x-api-key 鉴权）。原先各处以 `== OPENAI_COMPATIBLE` 硬编码判断，
 * 新增服务商时会漏改；收敛到这个属性后只有一处需要维护。
 *
 * [isCustomEndpoint] 标记"Base URL 由用户自己填"的服务商，设置页据此决定 URL 输入框是否可编辑。
 */
enum class LlmProvider(
    val usesOpenAiProtocol: Boolean,
    val isCustomEndpoint: Boolean = false,
) {
    /** 智谱 GLM，走 Anthropic Messages 兼容端点。 */
    GLM(usesOpenAiProtocol = false),

    /** Kimi 编程版（api.kimi.com/coding），Anthropic Messages 协议。 */
    KIMI(usesOpenAiProtocol = false),

    /** 魔搭 ModelScope Inference，兼容 Anthropic Messages。 */
    MODELSCOPE(usesOpenAiProtocol = false),

    OPENAI(usesOpenAiProtocol = true),
    DEEPSEEK(usesOpenAiProtocol = true),
    DASHSCOPE(usesOpenAiProtocol = true),
    SILICONFLOW(usesOpenAiProtocol = true),
    VOLCENGINE(usesOpenAiProtocol = true),
    MINIMAX(usesOpenAiProtocol = true),
    OPENROUTER(usesOpenAiProtocol = true),

    /** 任意 OpenAI 兼容端点，Base URL 与模型名都由用户填。 */
    OPENAI_COMPATIBLE(usesOpenAiProtocol = true, isCustomEndpoint = true);

    companion object {
        /**
         * 反解 DataStore 里的 Provider 名。已删除的历史值（如 `LOCAL_QWEN`）落到 [fallback]，
         * 直接 `valueOf` 会在升级用户的设备上抛 IllegalArgumentException。
         */
        fun fromStoredName(stored: String?, fallback: LlmProvider): LlmProvider =
            entries.firstOrNull { it.name == stored } ?: fallback
    }
}

@Serializable
data class MessageMetadata(
    val model: String = "",
    val tokensUsed: Int = 0,
)

object Converters {

    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun messageRoleToString(role: MessageRole): String = role.name
    @TypeConverter
    fun stringToMessageRole(value: String): MessageRole = enumValueOf(value)

    @TypeConverter
    fun memoryTypeToString(type: MemoryType): String = type.name
    @TypeConverter
    fun stringToMemoryType(value: String): MemoryType = enumValueOf(value)

    @TypeConverter
    fun summaryTypeToString(type: SummaryType): String = type.name
    @TypeConverter
    fun stringToSummaryType(value: String): SummaryType = enumValueOf(value)

    @TypeConverter
    fun themeModeToString(mode: ThemeMode): String = mode.name
    @TypeConverter
    fun stringToThemeMode(value: String): ThemeMode = enumValueOf(value)

    @TypeConverter
    fun llmProviderToString(provider: LlmProvider): String = provider.name
    @TypeConverter
    fun stringToLlmProvider(value: String): LlmProvider = enumValueOf(value)

    @TypeConverter
    fun mapToJson(map: Map<String, Float>): String = json.encodeToString(map)
    @TypeConverter
    fun jsonToMap(value: String): Map<String, Float> =
        json.decodeFromString(value)

    @TypeConverter
    fun metadataToJson(meta: MessageMetadata): String = json.encodeToString(meta)
    @TypeConverter
    fun jsonToMetadata(value: String): MessageMetadata =
        json.decodeFromString(value)
}
