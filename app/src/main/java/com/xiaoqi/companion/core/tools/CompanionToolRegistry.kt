package com.xiaoqi.companion.core.tools

import ai.koog.agents.core.tools.ToolRegistry
import com.xiaoqi.companion.core.logging.AppLogger
import com.xiaoqi.companion.core.logging.LogTags
import com.xiaoqi.companion.data.datastore.AppPreferences
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

interface AgentToolRegistry {
    fun create(policy: ToolPolicy = ToolPolicy.chatDefault): ToolRegistry

    /** 复合任务路径 (KoogAgentFactoryImpl) 会带入本轮 query 与必需工具名;内置工具不依赖它们。 */
    fun createForQuery(
        query: String,
        policy: ToolPolicy = ToolPolicy.chatDefault,
        requiredToolNames: Set<String> = emptySet(),
    ): ToolRegistry = create(policy)
}

class CompanionToolRegistry @Inject constructor(
    private val searchMemoryTool: SearchMemoryTool,
    private val searchRecordsTool: SearchRecordsTool,
    private val searchSummariesTool: SearchSummariesTool,
    private val getCurrentTimeTool: GetCurrentTimeTool,
    private val getRecentInteractionContextTool: GetRecentInteractionContextTool,
    private val getUserContextSettingsTool: GetUserContextSettingsTool,
    private val getDeviceStatusTool: GetDeviceStatusTool,
    private val getWeatherTool: GetWeatherTool,
    private val createLocalReminderTool: CreateLocalReminderTool,
    private val updateStateTool: UpdateStateTool,
    private val appPreferences: AppPreferences,
) : AgentToolRegistry {

    override fun create(policy: ToolPolicy): ToolRegistry {
        val builder = ToolRegistry.builder()
        if (readSystemToolsPref()) {
            if (policy.allows(ToolMetadataRegistry.searchMemory)) builder.tool(searchMemoryTool)
            if (policy.allows(ToolMetadataRegistry.searchRecords)) builder.tool(searchRecordsTool)
            if (policy.allows(ToolMetadataRegistry.searchSummaries)) builder.tool(searchSummariesTool)
            if (policy.allows(ToolMetadataRegistry.getCurrentTime)) builder.tool(getCurrentTimeTool)
            if (policy.allows(ToolMetadataRegistry.getRecentInteractionContext)) builder.tool(getRecentInteractionContextTool)
            if (policy.allows(ToolMetadataRegistry.getUserContextSettings)) builder.tool(getUserContextSettingsTool)
            if (policy.allows(ToolMetadataRegistry.getDeviceStatus)) builder.tool(getDeviceStatusTool)
            if (policy.allows(ToolMetadataRegistry.getWeather)) builder.tool(getWeatherTool)
            if (policy.allows(ToolMetadataRegistry.createLocalReminder)) builder.tool(createLocalReminderTool)
            if (policy.allows(ToolMetadataRegistry.updateState)) builder.tool(updateStateTool)
        }
        return builder.build()
    }

    private fun readSystemToolsPref(): Boolean = runCatching {
        runBlocking { appPreferences.systemToolsEnabled.first() }
    }.getOrElse {
        AppLogger.warn(LogTags.Llm, "system_tools_pref_read_failed", "message" to (it.message ?: ""))
        true
    }
}
