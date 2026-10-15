package com.xiaoqi.companion.core.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPolicyTest {

    @Test
    fun chatDefault_allowsFourRoundsPerTurn() {
        assertEquals(4, ToolPolicy.chatDefault.maxToolRoundsPerTurn)
    }

    @Test
    fun chatDefault_allowsBuiltinReadAndLocalWrite() {
        assertTrue(ToolPolicy.chatDefault.allows(ToolMetadataRegistry.searchMemory))
        assertTrue(ToolPolicy.chatDefault.allows(ToolMetadataRegistry.updateState))
        assertTrue(ToolPolicy.chatDefault.allows(ToolMetadataRegistry.createLocalReminder))
    }

    @Test
    fun readOnly_allowsReadContextButBlocksLocalWrite() {
        assertTrue(ToolPolicy.readOnly.allows(ToolMetadataRegistry.searchMemory))
        assertFalse(ToolPolicy.readOnly.allows(ToolMetadataRegistry.updateState))
        assertFalse(ToolPolicy.readOnly.allows(ToolMetadataRegistry.createLocalReminder))
    }

    @Test
    fun systemOnly_allowsLocalWriteAndReadContext() {
        assertTrue(ToolPolicy.systemOnly.allows(ToolMetadataRegistry.updateState))
        assertTrue(ToolPolicy.systemOnly.allows(ToolMetadataRegistry.createLocalReminder))
        assertTrue(ToolPolicy.systemOnly.allows(ToolMetadataRegistry.getWeather))
    }

    @Test
    fun none_disablesEverything() {
        assertFalse(ToolPolicy.none.allowTools)
        assertFalse(ToolPolicy.none.allows(ToolMetadataRegistry.searchMemory))
    }
}
