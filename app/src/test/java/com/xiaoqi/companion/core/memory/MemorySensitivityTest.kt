package com.xiaoqi.companion.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySensitivityTest {

    @Test
    fun `storage values match persisted column values`() {
        assertEquals("normal", MemorySensitivity.NORMAL.storageValue)
        assertEquals("private", MemorySensitivity.PRIVATE.storageValue)
        assertEquals("sensitive", MemorySensitivity.SENSITIVE.storageValue)
    }

    @Test
    fun `fromStorage resolves known values case insensitively`() {
        assertEquals(MemorySensitivity.NORMAL, MemorySensitivity.fromStorage("normal"))
        assertEquals(MemorySensitivity.PRIVATE, MemorySensitivity.fromStorage("PRIVATE"))
        assertEquals(MemorySensitivity.SENSITIVE, MemorySensitivity.fromStorage(" Sensitive "))
    }

    @Test
    fun `unknown or missing stored values fall back to normal`() {
        assertEquals(MemorySensitivity.NORMAL, MemorySensitivity.fromStorage(null))
        assertEquals(MemorySensitivity.NORMAL, MemorySensitivity.fromStorage(""))
        assertEquals(MemorySensitivity.NORMAL, MemorySensitivity.fromStorage("top-secret"))
    }

    @Test
    fun `stronger keeps the higher tier regardless of argument order`() {
        assertEquals(MemorySensitivity.SENSITIVE, MemorySensitivity.NORMAL.stronger(MemorySensitivity.SENSITIVE))
        assertEquals(MemorySensitivity.SENSITIVE, MemorySensitivity.SENSITIVE.stronger(MemorySensitivity.NORMAL))
        assertEquals(MemorySensitivity.PRIVATE, MemorySensitivity.PRIVATE.stronger(MemorySensitivity.NORMAL))
        assertEquals(MemorySensitivity.PRIVATE, MemorySensitivity.NORMAL.stronger(MemorySensitivity.PRIVATE))
        assertEquals(MemorySensitivity.PRIVATE, MemorySensitivity.PRIVATE.stronger(MemorySensitivity.PRIVATE))
    }

    @Test
    fun `only normal is considered non sensitive`() {
        assertFalse(MemorySensitivity.NORMAL.isSensitive)
        assertTrue(MemorySensitivity.PRIVATE.isSensitive)
        assertTrue(MemorySensitivity.SENSITIVE.isSensitive)
    }

    @Test
    fun `lexicon escalates health finance relationship and trauma terms`() {
        assertEquals(MemorySensitivity.SENSITIVE, SensitivityLexicon.classify("我确诊了糖尿病"))
        assertEquals(MemorySensitivity.SENSITIVE, SensitivityLexicon.classify("房贷压力好大"))
        assertEquals(MemorySensitivity.SENSITIVE, SensitivityLexicon.classify("他对我冷暴力"))
        assertEquals(MemorySensitivity.SENSITIVE, SensitivityLexicon.classify("奶奶去年去世了"))
    }

    @Test
    fun `lexicon treats relative and role mentions as private`() {
        assertEquals(MemorySensitivity.PRIVATE, SensitivityLexicon.classify("我跟室友相处得不错"))
        assertEquals(MemorySensitivity.PRIVATE, SensitivityLexicon.classify("老板今天夸我了"))
    }

    @Test
    fun `lexicon leaves ordinary content alone`() {
        assertEquals(MemorySensitivity.NORMAL, SensitivityLexicon.classify("今天天气很好，我去公园散步了"))
    }

    @Test
    fun `secrecy and forget detection are independent`() {
        assertTrue(SensitivityLexicon.requestsSecrecy("只有你知道这件事"))
        assertFalse(SensitivityLexicon.requestsSecrecy("今天天气不错"))

        assertTrue(SensitivityLexicon.requestsForget("把这个忘了"))
        assertFalse(SensitivityLexicon.requestsForget("帮我记住这个"))
    }

    @Test
    fun `inferred sources are exactly the three non stated kinds`() {
        assertEquals(3, MemorySourceKinds.INFERRED.size)
        assertTrue(MemorySourceKinds.INFERRED.contains(MemorySourceKinds.REFLECTION_SYNTHESIS))
        assertTrue(MemorySourceKinds.INFERRED.contains(MemorySourceKinds.AI_INFERENCE))
        assertTrue(MemorySourceKinds.INFERRED.contains(MemorySourceKinds.EXTERNAL_TOOL_RESULT))
        assertFalse(MemorySourceKinds.INFERRED.contains(MemorySourceKinds.TOOL_SAVE_MEMORY))
    }
}
