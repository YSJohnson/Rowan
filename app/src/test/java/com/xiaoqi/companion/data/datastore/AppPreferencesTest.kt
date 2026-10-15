package com.xiaoqi.companion.data.datastore

import androidx.datastore.preferences.core.edit
import com.xiaoqi.companion.data.db.converter.LlmProvider
import com.xiaoqi.companion.testing.InMemoryPreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 升级路径回归：老版本把 `LOCAL_QWEN` 写进 DataStore，新版本读到时必须退化成默认 provider
 * 而不是崩溃。
 */
class AppPreferencesTest {

    private val dataStore = InMemoryPreferencesDataStore()
    private val prefs = AppPreferences(dataStore)

    @Test
    fun llmProvider_retiredLocalQwenValue_fallsBackToDefault() = runTest {
        dataStore.edit { it[AppPreferences.Keys.llmProvider] = "LOCAL_QWEN" }

        assertEquals(LlmProvider.GLM, prefs.llmProvider.first())
    }

    @Test
    fun llmProvider_absentKey_fallsBackToDefault() = runTest {
        assertEquals(LlmProvider.GLM, prefs.llmProvider.first())
    }

    @Test
    fun llmProvider_knownValue_isPreserved() = runTest {
        prefs.setLlmProvider(LlmProvider.KIMI)

        assertEquals(LlmProvider.KIMI, prefs.llmProvider.first())
    }
}
