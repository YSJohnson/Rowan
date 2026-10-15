package com.xiaoqi.companion.data.repository

import com.xiaoqi.companion.data.db.BaseDaoTest
import com.xiaoqi.companion.data.db.converter.MemoryType
import com.xiaoqi.companion.data.db.converter.SummaryType
import com.xiaoqi.companion.data.db.dao.MemoryDao
import com.xiaoqi.companion.data.db.dao.MemorySummaryDao
import com.xiaoqi.companion.data.db.entity.MemoryEntity
import com.xiaoqi.companion.data.db.entity.MemorySummaryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryTest : BaseDaoTest() {

    private lateinit var memoryDao: MemoryDao
    private lateinit var summaryDao: MemorySummaryDao
    private lateinit var repository: MemoryRepository

    override fun initDaos() {
        memoryDao = db.memoryDao()
        summaryDao = db.memorySummaryDao()
        repository = MemoryRepository(memoryDao, summaryDao)
    }

    @Test
    fun saveMemory_mergesSimilarMemory() = runTest {
        val first = repository.saveMemory(
            SaveMemoryRequest(
                content = "User likes jasmine tea",
                type = MemoryType.FACT,
                importance = 0.5f,
            )
        )
        val second = repository.saveMemory(
            SaveMemoryRequest(
                content = "User likes jasmine tea in the evening",
                type = MemoryType.FACT,
                importance = 0.9f,
                confidence = 0.8f,
            )
        )

        val all = memoryDao.getPromptMemories(10)

        assertEquals(first.memory.id, second.memory.id)
        assertTrue(second.merged)
        assertEquals(1, all.size)
        assertEquals(0.9f, all.single().importance)
        assertTrue(all.single().content.contains("evening"))
    }

    @Test
    fun searchMemories_marksResultsAccessed() = runTest {
        memoryDao.insert(memory(id = "m1", content = "User likes jasmine tea", lastAccessed = 1_000L))

        val result = repository.searchMemories("jasmine")

        assertEquals(listOf("m1"), result.memories.map { it.id })
        assertTrue(memoryDao.getById("m1")!!.lastAccessed > 1_000L)
    }

    @Test
    fun selectPromptContext_combinesRelevantImportantRecentAndSummaries() = runTest {
        memoryDao.insert(memory(id = "relevant", content = "User likes jasmine tea", importance = 0.4f, timestamp = 1_000L))
        memoryDao.insert(memory(id = "important", content = "User is allergic to peanuts", importance = 0.95f, timestamp = 2_000L))
        memoryDao.insert(memory(id = "recent", content = "User started learning pottery", importance = 0.3f, timestamp = 9_000L))
        summaryDao.insert(
            summary(
                id = "summary",
                title = "Tea preferences",
                summary = "The user often returns to jasmine tea as a comfort ritual.",
                keywords = """["jasmine","tea"]""",
            )
        )

        val context = repository.selectPromptContext("Do I like jasmine?")

        assertTrue(context.memoryIds.contains("relevant"))
        assertTrue(context.memoryIds.contains("important"))
        assertTrue(context.memoryIds.contains("recent"))
        assertTrue(context.summaryIds.contains("summary"))
        assertFalse(context.summarySnippets.single().isBlank())
    }

    @Test
    fun selectPromptContext_whenQueryIsUnrelated_stillIncludesImportantMemories() = runTest {
        memoryDao.insert(memory(id = "important", content = "User likes jasmine tea", importance = 0.95f, timestamp = 1_000L))
        memoryDao.insert(memory(id = "recent", content = "User started learning pottery", importance = 0.3f, timestamp = 9_000L))

        val context = repository.selectPromptContext("Tell me a joke")

        assertTrue(context.memoryIds.contains("important"))
        assertTrue(context.memoryIds.contains("recent"))
    }

    @Test
    fun selectPromptContext_includesMoreThanEightMemoriesWhenWithinTokenBudget() = runTest {
        repeat(12) { index ->
            memoryDao.insert(
                memory(
                    id = "memory-$index",
                    content = "Stable preference ${index + 1}",
                    importance = 0.9f - index * 0.01f,
                    timestamp = 1_000L + index,
                )
            )
        }

        val context = repository.selectPromptContext("unmatched")

        assertEquals(12, context.memoryIds.size)
        assertTrue(context.memoryIds.contains("memory-11"))
    }

    @Test
    fun selectPromptContext_truncatesSingleMemoryToTokenBudget() = runTest {
        val longContent = "a".repeat(31_000)
        memoryDao.insert(memory(id = "long", content = longContent, importance = 0.99f))
        memoryDao.insert(memory(id = "small", content = "small memory", importance = 0.1f))

        val context = repository.selectPromptContext("unmatched")

        assertEquals(listOf("long"), context.memoryIds)
        assertTrue(context.memorySnippets.single().length <= 30_000)
        assertTrue(context.memorySnippets.single().endsWith("..."))
    }

    @Test
    fun selectPromptContext_includesMoreThanTwoSummariesWhenWithinTokenBudget() = runTest {
        repeat(4) { index ->
            summaryDao.insert(
                summary(
                    id = "summary-$index",
                    title = "Topic ${index + 1}",
                    summary = "The user mentioned summary detail ${index + 1}.",
                    keywords = """["topic"]""",
                )
            )
        }

        val context = repository.selectPromptContext("topic")

        assertEquals(4, context.summaryIds.size)
        assertTrue(context.summarySnippets.all { it.contains(":") })
    }

    @Test
    fun selectPromptContext_truncatesSummaryToTokenBudget() = runTest {
        summaryDao.insert(
            summary(
                id = "long-summary",
                title = "Long Summary",
                summary = "s".repeat(16_000),
                keywords = """["long"]""",
            )
        )
        summaryDao.insert(
            summary(
                id = "small-summary",
                title = "Small Summary",
                summary = "short",
                keywords = """["small"]""",
            )
        )

        val context = repository.selectPromptContext("long")

        assertEquals(listOf("long-summary"), context.summaryIds)
        assertTrue(context.summarySnippets.single().length <= 15_000)
        assertTrue(context.summarySnippets.single().endsWith("..."))
    }

    @Test
    fun selectPromptContext_excludesExpiredMemories() = runTest {
        memoryDao.insert(
            memory(
                id = "expired",
                content = "User used to live in Tokyo",
                importance = 0.95f,
                expiresAt = System.currentTimeMillis() - 1_000L,
            )
        )

        val context = repository.selectPromptContext("Where did I live?")

        assertFalse(context.memoryIds.contains("expired"))
    }

    @Test
    fun selectPromptContext_onlyIncludesPrivateMemoryWhenRelevant() = runTest {
        memoryDao.insert(
            memory(
                id = "private",
                content = "User private journal mentions jasmine tea",
                importance = 0.95f,
                sensitivity = "private",
            )
        )

        val unrelated = repository.selectPromptContext("What should I cook tonight?")
        val related = repository.selectPromptContext("Do I mention jasmine tea?")

        assertFalse(unrelated.memoryIds.contains("private"))
        assertTrue(related.memoryIds.contains("private"))
    }

    @Test
    fun pinMemory_setsPinnedTrue() = runTest {
        memoryDao.insert(memory(id = "p1", content = "Fact A", importance = 0.5f))

        repository.pinMemory("p1")

        assertTrue(memoryDao.getById("p1")!!.pinned)
    }

    // --- Onboarding 种子写入 ---

    @Test
    fun saveOnboardingMemories_writesAllAnswersAsSeparateMemories() = runTest {
        val written = repository.saveOnboardingMemories(
            concerns = "换工作面试 + 下周体检",
            upcomingDates = "6/22 妈妈生日, 6/30 论文截止",
            addressStyle = "叫我小蓝,语气放松",
            friends = listOf("旺财", "小米"),
            scheduleChoice = "晚睡晚起",
        )

        // 3 个独立字段(挂心事/日期/称呼/作息) + 2 个朋友 = 6 条
        assertEquals(6, written)
        val all = memoryDao.getPromptMemories(10)
        assertEquals(6, all.size)

        val byType = all.groupBy { it.type }
        // 4 个 FACT(称呼/朋友×2/作息) + 2 个 EPISODE(挂心事/日期)
        assertEquals(4, byType[MemoryType.FACT]?.size)
        assertEquals(2, byType[MemoryType.EPISODE]?.size)

        val contents = all.map { it.content }
        assertTrue(contents.any { it.contains("小蓝") })
        assertTrue(contents.any { it.contains("旺财") })
        assertTrue(contents.any { it.contains("小米") })
        assertTrue(contents.any { it.contains("换工作") })
        assertTrue(contents.any { it.contains("妈妈生日") })
        assertTrue(all.all { it.source == "onboarding" })
        assertTrue(all.all { it.importance == 0.8f })
    }

    @Test
    fun saveOnboardingMemories_skipsBlankAnswers() = runTest {
        val written = repository.saveOnboardingMemories(
            concerns = "",                       // 空,跳过
            upcomingDates = "  ",                // 空白,跳过
            addressStyle = "叫我小蓝",
            friends = listOf("", "旺财", "  "),  // 只保留非空
            scheduleChoice = "",                 // 空,跳过
        )

        assertEquals(2, written)
        val all = memoryDao.getPromptMemories(10)
        assertEquals(2, all.size)
        val contents = all.map { it.content }
        assertTrue(contents.any { it.contains("小蓝") })
        assertTrue(contents.any { it.contains("旺财") })
    }

    @Test
    fun saveOnboardingMemories_mergesDuplicateFriends() = runTest {
        // 第一次:旺财
        repository.saveOnboardingMemories(
            concerns = "",
            upcomingDates = "",
            addressStyle = "",
            friends = listOf("旺财"),
            scheduleChoice = "",
        )
        // 第二次:旺财(用户多设备或重做 onboarding)
        repository.saveOnboardingMemories(
            concerns = "",
            upcomingDates = "",
            addressStyle = "",
            friends = listOf("旺财"),
            scheduleChoice = "",
        )

        val all = memoryDao.getPromptMemories(10)
        // merge 路径应当把"高频联系人: 旺财"合并成 1 条
        assertEquals(1, all.size)
        assertTrue(all.single().content.contains("旺财"))
    }

    // --- Vision memory ---

    @Test
    fun saveVisionMemory_storesBase64AndMediaType() = runTest {
        val entity = repository.saveVisionMemory(
            summary = "看这夕阳",
            imageBase64 = "aGVsbG8=",
            imageMediaType = "image/jpeg",
        )

        val fetched = memoryDao.getById(entity.id)
        assertNotNull(fetched)
        assertEquals("aGVsbG8=", fetched!!.imageBase64)
        assertEquals("image/jpeg", fetched.imageMediaType)
        assertTrue(fetched.content.contains("看这夕阳"))
        assertEquals("reflection:vision", fetched.source)
    }

    @Test
    fun saveVisionMemory_blankSummary_usesDefaultLabel() = runTest {
        val entity = repository.saveVisionMemory(
            summary = "",
            imageBase64 = "aGVsbG8=",
        )

        val fetched = memoryDao.getById(entity.id)
        assertNotNull(fetched)
        assertEquals("[图片]", fetched!!.content)
    }

    @Test
    fun getRecentImages_returnsOnlyMemoriesWithImage() = runTest {
        // 1 张图 + 1 张无图
        repository.saveVisionMemory(summary = "图", imageBase64 = "img1")
        memoryDao.insert(memory(id = "no-image-1", content = "无图文字记忆"))

        val images = repository.getRecentImages(limit = 10)

        assertEquals(1, images.size)
        assertTrue(images[0].content.contains("图"))
        assertEquals("img1", images[0].imageBase64)
    }

    @Test
    fun unpinMemory_setsPinnedFalse() = runTest {
        memoryDao.insert(memory(id = "p1", content = "Fact A", importance = 0.5f))
        repository.pinMemory("p1")

        repository.unpinMemory("p1")

        assertEquals(false, memoryDao.getById("p1")!!.pinned)
    }

    @Test
    fun archiveMemory_setsArchivedTrue() = runTest {
        memoryDao.insert(memory(id = "a1", content = "Old fact", importance = 0.4f))

        repository.archiveMemory("a1")

        assertTrue(memoryDao.getById("a1")!!.archived)
    }

    @Test
    fun unarchiveMemory_setsArchivedFalse() = runTest {
        memoryDao.insert(memory(id = "a1", content = "Old fact", importance = 0.4f))
        repository.archiveMemory("a1")

        repository.unarchiveMemory("a1")

        assertEquals(false, memoryDao.getById("a1")!!.archived)
    }

    @Test
    fun observeMemoriesPinnedFirst_returnsPinnedMemoriesFirst() = runTest {
        memoryDao.insert(memory(id = "m-normal", content = "Normal", importance = 0.9f, lastAccessed = 9_000L))
        memoryDao.insert(memory(id = "m-pinned", content = "Pinned", importance = 0.3f, lastAccessed = 1_000L))

        repository.pinMemory("m-pinned")

        val first = memoryDao.observeAllPinnedFirst().first()
        assertEquals("m-pinned", first[0].id)
    }

    private fun memory(
        id: String,
        content: String,
        importance: Float = 0.5f,
        timestamp: Long = System.currentTimeMillis(),
        lastAccessed: Long = timestamp,
        expiresAt: Long? = null,
        sensitivity: String = "normal",
    ) = MemoryEntity(
        id = id,
        type = MemoryType.FACT,
        content = content,
        importance = importance,
        timestamp = timestamp,
        lastAccessed = lastAccessed,
        expiresAt = expiresAt,
        sensitivity = sensitivity,
    )

    private fun summary(
        id: String,
        title: String,
        summary: String,
        keywords: String,
    ) = MemorySummaryEntity(
        id = id,
        type = SummaryType.TOPIC,
        title = title,
        summary = summary,
        keywords = keywords,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        lastAccessed = 1_000L,
    )

    // region T7 敏感内容记忆策略

    @Test
    fun saveMemory_storedResultIsMarkedStoredAndUsable() = runTest {
        val result = repository.saveMemory(
            SaveMemoryRequest(content = "我最近在学做菜", type = MemoryType.FACT)
        )

        assertTrue(result.stored)
        assertTrue(result.memory.id.isNotBlank())
        assertNotNull(memoryDao.getById(result.memory.id))
    }

    @Test
    fun saveMemory_rejectedWriteDoesNotPersist() = runTest {
        // D1：模型对人格的推断不算"用户说的"
        val result = repository.saveMemory(
            SaveMemoryRequest(
                content = "用户是一个回避型依恋的人",
                type = MemoryType.FACT,
                source = com.xiaoqi.companion.core.memory.MemorySourceKinds.AI_INFERENCE,
            )
        )

        assertFalse(result.stored)
        assertEquals("", result.memory.id)
        assertEquals(0, memoryDao.countAll())
    }

    @Test
    fun saveMemory_symptomKeepsUserAttribution() = runTest {
        val result = repository.saveMemory(
            SaveMemoryRequest(content = "用户有抑郁症", type = MemoryType.FACT)
        )

        assertTrue(result.stored)
        assertEquals("用户说自己有抑郁症", result.memory.content)
        // 症状相关一律高敏
        assertEquals("sensitive", result.memory.sensitivity)
    }

    @Test
    fun saveMemory_relativeMentionIsPrivate() = runTest {
        val result = repository.saveMemory(
            SaveMemoryRequest(content = "我妈今天又催我结婚", type = MemoryType.FACT)
        )

        assertEquals("private", result.memory.sensitivity)
    }

    @Test
    fun forgetMemory_deletesDerivedEntriesSharingSourceMessages() = runTest {
        val target = repository.saveMemory(
            SaveMemoryRequest(
                content = "上周项目汇报搞砸了",
                type = MemoryType.EPISODE,
                sourceMessageIds = listOf("msg-1", "msg-2"),
            )
        )
        val derived = repository.saveMemory(
            SaveMemoryRequest(
                content = "项目汇报前我总睡不好",
                type = MemoryType.FACT,
                sourceMessageIds = listOf("msg-2"),
            )
        )
        val unrelated = repository.saveMemory(
            SaveMemoryRequest(
                content = "我最近在学做菜",
                type = MemoryType.FACT,
                sourceMessageIds = listOf("msg-9"),
            )
        )

        val deleted = repository.forgetMemory(target.memory.id)

        assertEquals(2, deleted)
        assertEquals(null, memoryDao.getById(target.memory.id))
        assertEquals(null, memoryDao.getById(derived.memory.id))
        assertNotNull(memoryDao.getById(unrelated.memory.id))
    }

    @Test
    fun forgetMemory_withoutSourceIdsDeletesOnlyItself() = runTest {
        val target = repository.saveMemory(
            SaveMemoryRequest(content = "我最近在学做菜", type = MemoryType.FACT)
        )
        repository.saveMemory(SaveMemoryRequest(content = "我养了一只猫", type = MemoryType.FACT))

        val deleted = repository.forgetMemory(target.memory.id)

        assertEquals(1, deleted)
        assertEquals(1, memoryDao.countAll())
    }

    @Test
    fun forgetMemory_unknownIdIsNoOp() = runTest {
        repository.saveMemory(SaveMemoryRequest(content = "我最近在学做菜", type = MemoryType.FACT))

        assertEquals(0, repository.forgetMemory("does-not-exist"))
        assertEquals(1, memoryDao.countAll())
    }

    // endregion
}
