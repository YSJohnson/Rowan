package com.xiaoqi.companion.core.tools

import com.xiaoqi.companion.data.datastore.AppPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionToolRegistryTest {

    // 真实工具实例(各自依赖用 relaxed mock 填充),保证 10 个工具名唯一,
    // 避开 Koog ToolRegistry.builder() 的重名检查。
    private val searchMemoryTool = SearchMemoryTool(mockk(relaxed = true))
    private val searchRecordsTool = SearchRecordsTool(
        messageDao = mockk(relaxed = true),
        messageSearchDao = mockk(relaxed = true),
        appPreferences = mockk(relaxed = true),
    )
    private val searchSummariesTool = SearchSummariesTool(mockk(relaxed = true))
    private val getCurrentTimeTool = GetCurrentTimeTool()
    private val getRecentInteractionContextTool = GetRecentInteractionContextTool(
        messageDao = mockk(relaxed = true),
        appPreferences = mockk(relaxed = true),
    )
    private val getUserContextSettingsTool = GetUserContextSettingsTool(
        appPreferences = mockk(relaxed = true),
        permissionReader = mockk(relaxed = true),
    )
    private val getDeviceStatusTool = GetDeviceStatusTool(
        appPreferences = mockk(relaxed = true),
        deviceStatusProvider = mockk(relaxed = true),
    )
    private val getWeatherTool = GetWeatherTool(
        appPreferences = mockk(relaxed = true),
        weatherProvider = mockk(relaxed = true),
        locationProvider = mockk(relaxed = true),
    )
    private val createLocalReminderTool = CreateLocalReminderTool(
        appPreferences = mockk(relaxed = true),
        permissionReader = mockk(relaxed = true),
        reminderRepository = mockk(relaxed = true),
    )
    private val updateStateTool = UpdateStateTool(
        emotionMachine = mockk(relaxed = true),
        relationshipModel = mockk(relaxed = true),
        memoryRepository = mockk(relaxed = true),
    )

    private fun newRegistry(appPreferences: AppPreferences): CompanionToolRegistry = CompanionToolRegistry(
        searchMemoryTool = searchMemoryTool,
        searchRecordsTool = searchRecordsTool,
        searchSummariesTool = searchSummariesTool,
        getCurrentTimeTool = getCurrentTimeTool,
        getRecentInteractionContextTool = getRecentInteractionContextTool,
        getUserContextSettingsTool = getUserContextSettingsTool,
        getDeviceStatusTool = getDeviceStatusTool,
        getWeatherTool = getWeatherTool,
        createLocalReminderTool = createLocalReminderTool,
        updateStateTool = updateStateTool,
        appPreferences = appPreferences,
    )

    private fun appPrefs(systemTools: Boolean): AppPreferences = mockk {
        every { systemToolsEnabled } returns flowOf(systemTools)
    }

    @Test
    fun create_systemToolsEnabled_registersAllBuiltinTools() = runTest {
        val tools = newRegistry(appPrefs(systemTools = true)).create()

        assertEquals(10, tools.tools.size)
        assertTrue(tools.tools.any { it.name == "search_memory" })
        assertTrue(tools.tools.any { it.name == "update_state" })
    }

    @Test
    fun create_systemToolsDisabled_registersNoTools() = runTest {
        val tools = newRegistry(appPrefs(systemTools = false)).create()

        assertEquals(0, tools.tools.size)
    }

    @Test
    fun createForQuery_keepsBuiltinToolsAvailable() = runTest {
        val tools = newRegistry(appPrefs(systemTools = true))
            .createForQuery("帮我看看附近的天气", requiredToolNames = setOf("get_weather"))

        assertEquals(10, tools.tools.size)
        assertTrue(tools.tools.any { it.name == "get_weather" })
    }

    @Test
    fun create_readOnlyPolicy_filtersLocalWriteTools() = runTest {
        val tools = newRegistry(appPrefs(systemTools = true)).create(ToolPolicy.readOnly)

        assertTrue(tools.tools.any { it.name == "search_memory" })
        assertTrue(tools.tools.none { it.name == "update_state" })
        assertTrue(tools.tools.none { it.name == "create_local_reminder" })
    }

    @Test
    fun create_systemOnlyPolicy_keepsReadAndLocalWriteTools() = runTest {
        val tools = newRegistry(appPrefs(systemTools = true)).create(ToolPolicy.systemOnly)

        assertTrue(tools.tools.any { it.name == "search_memory" })
        assertTrue(tools.tools.any { it.name == "update_state" })
        assertTrue(tools.tools.any { it.name == "create_local_reminder" })
    }

    @Test
    fun create_nonePolicy_registersNoToolsEvenWhenEnabled() = runTest {
        val tools = newRegistry(appPrefs(systemTools = true)).create(ToolPolicy.none)

        assertEquals(0, tools.tools.size)
    }
}
