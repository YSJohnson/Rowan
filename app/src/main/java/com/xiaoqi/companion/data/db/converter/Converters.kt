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
enum class LlmProvider {
    GLM,
    KIMI,
    MODELSCOPE,
    OPENAI_COMPATIBLE;

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
