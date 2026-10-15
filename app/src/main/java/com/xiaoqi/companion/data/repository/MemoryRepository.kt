package com.xiaoqi.companion.data.repository

import com.xiaoqi.companion.data.db.converter.MemoryType
import com.xiaoqi.companion.data.db.dao.MemoryDao
import com.xiaoqi.companion.data.db.dao.MemorySummaryDao
import com.xiaoqi.companion.data.db.entity.MemoryEntity
import com.xiaoqi.companion.data.db.entity.MemorySummaryEntity
import com.xiaoqi.companion.core.logging.AppLogger
import com.xiaoqi.companion.core.logging.LogTags
import com.xiaoqi.companion.core.memory.MemorySensitivity
import com.xiaoqi.companion.core.memory.MemorySourceKinds
import com.xiaoqi.companion.core.memory.MemoryWriteDecision
import com.xiaoqi.companion.core.memory.MemoryWritePolicy
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object MemorySources {
    const val TOOL_SAVE_MEMORY = MemorySourceKinds.TOOL_SAVE_MEMORY
    const val TOOL_UPDATE_STATE = MemorySourceKinds.TOOL_UPDATE_STATE
    const val POST_TURN_FALLBACK = MemorySourceKinds.POST_TURN_FALLBACK
    const val REFLECTION_VISION = MemorySourceKinds.REFLECTION_VISION

    /** T5 对话模式偏好；来源是用户原话里的显式信号，不算推断。 */
    const val CHAT_MODE_PREFERENCE = MemorySourceKinds.CHAT_MODE_PREFERENCE

    /** 模型跨轮综合 / 人格推断 / 外部工具回写 —— 都不算"用户说的"，不进记忆库。 */
    const val REFLECTION_SYNTHESIS = MemorySourceKinds.REFLECTION_SYNTHESIS
    const val AI_INFERENCE = MemorySourceKinds.AI_INFERENCE
    const val EXTERNAL_TOOL_RESULT = MemorySourceKinds.EXTERNAL_TOOL_RESULT
}

data class SaveMemoryRequest(
    val content: String,
    val type: MemoryType,
    val importance: Float = 0.5f,
    val confidence: Float = 0.7f,
    val source: String = MemorySources.TOOL_SAVE_MEMORY,
    val sourceMessageIds: List<String> = emptyList(),
    val sensitivity: String = "normal",
    val expiresAt: Long? = null,
)

data class SaveMemoryResult(
    val memory: MemoryEntity,
    val merged: Boolean,
    /**
     * false = 被 D1 写路径守卫拒绝，**没有落库**（此时 [memory] 是承载内容的临时对象，
     * `id` 为空且不在库里）。调用方不要拿 `memory.id` 去查/删。
     */
    val stored: Boolean = true,
)

data class MemorySearchResult(
    val query: String,
    val memories: List<MemoryEntity>,
)

data class PromptMemoryContext(
    val memorySnippets: List<String>,
    val memoryIds: List<String>,
    val summarySnippets: List<String>,
    val summaryIds: List<String>,
)

class MemoryRepository @Inject constructor(
    private val memoryDao: MemoryDao,
    private val summaryDao: MemorySummaryDao,
) {

    suspend fun saveMemory(request: SaveMemoryRequest): SaveMemoryResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val now = System.currentTimeMillis()
            // D1 写路径守卫：先过密级分层 + 只记原话，再落库。拒绝在这里就终止，不产生任何行。
            val decision = MemoryWritePolicy.evaluate(
                content = request.content,
                requestedSource = request.source,
                requestedSensitivity = MemorySensitivity.fromStorage(request.sensitivity),
            )
            if (!decision.shouldStore) {
                AppLogger.info(
                    LogTags.Repo,
                    "memory_save_rejected",
                    "source" to request.source,
                    "rejection" to decision.rejection,
                    "contentLength" to request.content.trim().length,
                )
                return@withContext SaveMemoryResult(
                    memory = rejectedMemory(request, decision, now),
                    merged = false,
                    stored = false,
                )
            }
            val normalizedContent = decision.content
            AppLogger.info(
                LogTags.Repo,
                "memory_save_started",
                "type" to request.type,
                "source" to request.source,
                "contentLength" to normalizedContent.length,
                "sourceMessageCount" to request.sourceMessageIds.size,
                "sensitivity" to decision.sensitivity.storageValue,
            )
            val sourceIdsJson = json.encodeToString(request.sourceMessageIds.map { it.trim() }.filter { it.isNotBlank() })
            val existing = findMergeTarget(normalizedContent, request.type)
            val entity = existing?.let {
                it.copy(
                    content = mergeContent(it.content, normalizedContent),
                    importance = maxOf(it.importance, request.importance.coerceIn(0f, 1f)),
                    confidence = maxOf(it.confidence, request.confidence.coerceIn(0f, 1f)),
                    source = mergeSource(it.source, request.source),
                    sourceMessageIds = mergeJsonStringLists(it.sourceMessageIds, sourceIdsJson),
                    updatedAt = now,
                    lastAccessed = now,
                    expiresAt = request.expiresAt ?: it.expiresAt,
                    sensitivity = strongestSensitivity(it.sensitivity, decision.sensitivity.storageValue),
                )
            } ?: MemoryEntity(
                id = UUID.randomUUID().toString(),
                type = request.type,
                content = normalizedContent,
                source = request.source,
                importance = request.importance.coerceIn(0f, 1f),
                confidence = request.confidence.coerceIn(0f, 1f),
                sourceMessageIds = sourceIdsJson,
                timestamp = now,
                updatedAt = now,
                expiresAt = request.expiresAt,
                sensitivity = decision.sensitivity.storageValue,
                lastAccessed = now,
            )
            memoryDao.insert(entity)
            SaveMemoryResult(memory = entity, merged = existing != null, stored = true).also {
                AppLogger.info(
                    LogTags.Repo,
                    "memory_save_completed",
                    "memoryId" to entity.id,
                    "type" to entity.type,
                    "source" to entity.source,
                    "merged" to (existing != null),
                    "importance" to entity.importance,
                    "confidence" to entity.confidence,
                    "sensitivity" to entity.sensitivity,
                    "durationMs" to (System.currentTimeMillis() - startedAt),
                )
            }
        }

    /** 被守卫拒绝的请求也要给调用方一个可读结果，但**不写库**。 */
    private fun rejectedMemory(
        request: SaveMemoryRequest,
        decision: MemoryWriteDecision,
        now: Long,
    ): MemoryEntity = MemoryEntity(
        id = "",
        type = request.type,
        content = decision.content,
        source = request.source,
        importance = 0f,
        confidence = 0f,
        sourceMessageIds = "[]",
        timestamp = now,
        updatedAt = now,
        sensitivity = decision.sensitivity.storageValue,
    )

    /**
     * D1「忘掉这件事」：删除该条**及其派生条目**。
     *
     * 派生判定 = 与目标条目共享任一 `sourceMessageIds`。同一批原话派出的推断/摘要条目
     * 会一起消失，而不是被改写成"曾经…"。返回实际删除条数（含目标本身）。
     */
    suspend fun forgetMemory(id: String): Int = withContext(Dispatchers.IO) {
        val target = memoryDao.getById(id)
        if (target == null) {
            AppLogger.warn(LogTags.Repo, "memory_forget_target_missing", "memoryId" to id)
            return@withContext 0
        }
        val targetSourceIds = decodeIdList(target.sourceMessageIds).toSet()
        val derived = if (targetSourceIds.isEmpty()) {
            emptyList()
        } else {
            memoryDao.getAll()
                .filter { it.id != id && decodeIdList(it.sourceMessageIds).any { sourceId -> sourceId in targetSourceIds } }
        }
        (derived.map { it.id } + id).distinct().forEach { memoryDao.deleteById(it) }
        AppLogger.info(
            LogTags.Repo,
            "memory_forget_completed",
            "memoryId" to id,
            "derivedDeleted" to derived.size,
            "totalDeleted" to (derived.size + 1),
        )
        derived.size + 1
    }

    suspend fun searchMemories(
        query: String,
        type: MemoryType? = null,
        limit: Int = 10,
    ): MemorySearchResult = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        val safeLimit = limit.coerceIn(1, MAX_MEMORY_RESULTS)
        val candidateLimit = (safeLimit * CANDIDATE_MULTIPLIER).coerceAtMost(MAX_MEMORY_CANDIDATES)
        val candidates = memoryDao.searchByContent(
            pattern = cleanQuery.toLikePattern(),
            type = type,
            limit = candidateLimit,
        )
        val memories = candidates
            .map { memory -> MemoryHit(memory, scoreMemory(memory.content, cleanQuery, memory.importance, memory.confidence)) }
            .sortedWith(compareByDescending<MemoryHit> { it.score }.thenByDescending { it.memory.lastAccessed })
            .take(safeLimit)
            .map { it.memory }
        markMemoriesAccessed(memories)
        MemorySearchResult(query = cleanQuery, memories = memories)
    }

    suspend fun selectPromptContext(inputText: String): PromptMemoryContext =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val query = inputText.trim()
            val relevant = searchPromptCandidates(query)
            val important = memoryDao.getPromptMemories(PROMPT_MEMORY_CANDIDATE_LIMIT)
                .filterUsableForPrompt(query, allowPrivateWhenRelevant = false)
            val recent = memoryDao.getRecentMemories(PROMPT_RECENT_LIMIT)
                .filterUsableForPrompt(query, allowPrivateWhenRelevant = false)
            val selectedMemories = selectMemoriesWithinTokenBudget(
                mergeMemoryBuckets(relevant, important, recent),
                PROMPT_MEMORY_TOKEN_BUDGET,
            )
            val selectedSummaries = selectSummariesWithinTokenBudget(
                searchPromptSummaries(query),
                PROMPT_SUMMARY_TOKEN_BUDGET,
            )

            markMemoriesAccessed(selectedMemories.map { it.memory })
            markSummariesAccessed(selectedSummaries.map { it.summary })

            PromptMemoryContext(
                memorySnippets = selectedMemories.map { it.snippet },
                memoryIds = selectedMemories.map { it.memory.id },
                summarySnippets = selectedSummaries.map { it.snippet },
                summaryIds = selectedSummaries.map { it.summary.id },
            ).also {
                AppLogger.debug(
                    LogTags.Repo,
                    "memory_prompt_context_selected",
                    "queryLength" to query.length,
                    "relevantCount" to relevant.size,
                    "importantCount" to important.size,
                    "recentCount" to recent.size,
                    "selectedMemoryCount" to it.memorySnippets.size,
                    "selectedSummaryCount" to it.summarySnippets.size,
                    "memoryTokenBudget" to PROMPT_MEMORY_TOKEN_BUDGET,
                    "summaryTokenBudget" to PROMPT_SUMMARY_TOKEN_BUDGET,
                    "durationMs" to (System.currentTimeMillis() - startedAt),
                )
            }
        }

    fun observeMemoriesPinnedFirst(): Flow<List<MemoryEntity>> = memoryDao.observeAllPinnedFirst()

    /**
     * 给定固定 id 插入一条 memory(供 debug seedDemoInsights 等需要固定 evidence id 的场景)。
     * 不走 merge / 重复检测 — **只用于 seed/migration**,不走用户主流程。
     */
    suspend fun insertMemoryWithId(entity: MemoryEntity) = withContext(Dispatchers.IO) {
        memoryDao.insert(entity)
        AppLogger.info(LogTags.Repo, "memory_insert_with_id", "memoryId" to entity.id)
    }

    suspend fun countAll(): Int = withContext(Dispatchers.IO) { memoryDao.countAll() }

    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        val before = memoryDao.countAll()
        memoryDao.clearAll()
        AppLogger.info(LogTags.Repo, "memory_clear_all", "beforeCount" to before)
        before
    }

    // region M4 视觉入 memory

    /**
     * 把"用户发图"事件作为一条 FACT memory 写进 memories 表,base64 一起存。
     *
     * 触发点:`ChatViewModel.sendMessage` 看到 `pendingImage != null` 时调,失败仅 log。
     * Validator 不走(这写路径已经明确可信 — base64 来自 system Photo Picker +
     * `AndroidChatImageProcessor` 压缩,不存在幻觉)。
     */
    suspend fun saveVisionMemory(
        summary: String,
        imageBase64: String,
        imageMediaType: String = "image/jpeg",
        importance: Float = 0.6f,
        confidence: Float = 0.85f,
        sourceMessageId: String? = null,
    ): MemoryEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val sourceIds = if (sourceMessageId != null) "[\"$sourceMessageId\"]" else "[]"
        val content = if (summary.isBlank()) "[图片]" else "[图片] $summary"
        val entity = MemoryEntity(
            id = java.util.UUID.randomUUID().toString(),
            type = com.xiaoqi.companion.data.db.converter.MemoryType.FACT,
            content = content,
            source = MemorySources.REFLECTION_VISION,
            importance = importance.coerceIn(0f, 1f),
            confidence = confidence.coerceIn(0f, 1f),
            sourceMessageIds = sourceIds,
            timestamp = now,
            updatedAt = now,
            expiresAt = null,
            sensitivity = "normal",
            lastAccessed = now,
            imageBase64 = imageBase64,
            imageMediaType = imageMediaType,
        )
        memoryDao.insert(entity)
        AppLogger.info(
            LogTags.Repo,
            "vision_memory_save_completed",
            "memoryId" to entity.id,
            "mediaType" to imageMediaType,
            "base64Bytes" to imageBase64.length,
        )
        entity
    }

    /** M4:有图 memory 列表(供 DreamDataCollector 跨模态 evidence 注入) */
    fun observeImages(limit: Int = 20): Flow<List<MemoryEntity>> = memoryDao.observeImages(limit)

    /** M4:一次性取近 N 张图(供 DreamDataCollector 90 天 collect 用) */
    suspend fun getRecentImages(limit: Int = 20): List<MemoryEntity> =
        withContext(Dispatchers.IO) { memoryDao.getRecentImages(limit) }

    // endregion

    suspend fun pinMemory(id: String): Unit = withContext(Dispatchers.IO) {
        memoryDao.setPinned(id, true)
        AppLogger.info(LogTags.Repo, "memory_pinned", "memoryId" to id)
    }

    suspend fun unpinMemory(id: String): Unit = withContext(Dispatchers.IO) {
        memoryDao.setPinned(id, false)
        AppLogger.info(LogTags.Repo, "memory_unpinned", "memoryId" to id)
    }

    suspend fun archiveMemory(id: String): Unit = withContext(Dispatchers.IO) {
        memoryDao.setArchived(id, true)
        AppLogger.info(LogTags.Repo, "memory_archived", "memoryId" to id)
    }

    suspend fun unarchiveMemory(id: String): Unit = withContext(Dispatchers.IO) {
        memoryDao.setArchived(id, false)
        AppLogger.info(LogTags.Repo, "memory_unarchived", "memoryId" to id)
    }

    suspend fun deleteMemory(id: String): Unit = withContext(Dispatchers.IO) {
        memoryDao.deleteById(id)
        AppLogger.info(LogTags.Repo, "memory_deleted_via_repo", "memoryId" to id)
    }

    private suspend fun findMergeTarget(content: String, type: MemoryType): MemoryEntity? {
        val keyTerms = content.keyTerms()
        if (keyTerms.isEmpty()) return null
        val pattern = keyTerms.first().toLikePattern()
        return memoryDao.findSimilar(pattern = pattern, type = type, limit = MERGE_CANDIDATE_LIMIT)
            .map { MemoryHit(it, similarityScore(it.content, content)) }
            .filter { it.score >= MERGE_THRESHOLD }
            .maxByOrNull { it.score }
            ?.memory
    }

    private suspend fun searchPromptCandidates(query: String): List<MemoryEntity> {
        if (query.isBlank()) return emptyList()
        val fullPhraseMatches = searchMemories(query = query, limit = PROMPT_RELEVANT_LIMIT).memories
        val termMatches = query.keyTerms()
            .flatMap { term -> searchMemories(query = term, limit = PROMPT_RELEVANT_LIMIT).memories }
        return (fullPhraseMatches + termMatches)
            .distinctBy { it.id }
            .filterUsableForPrompt(query, allowPrivateWhenRelevant = true)
            .take(PROMPT_RELEVANT_LIMIT)
    }

    private suspend fun searchPromptSummaries(query: String): List<MemorySummaryEntity> {
        val patterns = if (query.isBlank()) listOf("%") else listOf(query.toLikePattern()) + query.keyTerms().map { it.toLikePattern() }
        val candidates = patterns
            .flatMap { pattern ->
                summaryDao.searchByText(
                    pattern = pattern,
                    type = null,
                    limit = PROMPT_SUMMARY_CANDIDATES,
                )
            }
            .distinctBy { it.id }
        return candidates
            .map { summary -> SummaryHit(summary, scoreSummary(summary, query)) }
            .sortedWith(compareByDescending<SummaryHit> { it.score }.thenByDescending { it.summary.lastAccessed })
            .map { it.summary }
    }

    private suspend fun markMemoriesAccessed(memories: List<MemoryEntity>) {
        if (memories.isEmpty()) return
        val now = System.currentTimeMillis()
        memories.forEach { memoryDao.updateLastAccessed(it.id, now) }
    }

    private suspend fun markSummariesAccessed(summaries: List<MemorySummaryEntity>) {
        if (summaries.isEmpty()) return
        val now = System.currentTimeMillis()
        summaries.forEach { summaryDao.updateLastAccessed(it.id, now) }
    }

    private fun mergeMemoryBuckets(vararg buckets: List<MemoryEntity>): List<MemoryEntity> =
        buckets
            .flatMap { it }
            .distinctBy { it.id }

    private fun selectMemoriesWithinTokenBudget(
        memories: List<MemoryEntity>,
        tokenBudget: Int,
    ): List<PromptMemorySelection> {
        if (memories.isEmpty() || tokenBudget <= 0) return emptyList()

        val selected = mutableListOf<PromptMemorySelection>()
        var usedTokens = 0

        for (memory in memories) {
            val tokens = estimateTokens(memory.content)
            if (usedTokens + tokens <= tokenBudget) {
                selected += PromptMemorySelection(memory = memory, snippet = memory.content)
                usedTokens += tokens
                continue
            }

            val remainingTokens = tokenBudget - usedTokens
            if (remainingTokens > 0) {
                val truncated = memory.content.truncateToTokenBudget(remainingTokens)
                selected += PromptMemorySelection(memory = memory, snippet = truncated)
            }
            break
        }

        return selected
    }

    private fun selectSummariesWithinTokenBudget(
        summaries: List<MemorySummaryEntity>,
        tokenBudget: Int,
    ): List<PromptSummarySelection> {
        if (summaries.isEmpty() || tokenBudget <= 0) return emptyList()

        val selected = mutableListOf<PromptSummarySelection>()
        var usedTokens = 0

        for (summary in summaries) {
            val snippet = summary.toPromptSnippet()
            val tokens = estimateTokens(snippet)
            if (usedTokens + tokens <= tokenBudget) {
                selected += PromptSummarySelection(summary = summary, snippet = snippet)
                usedTokens += tokens
                continue
            }

            val remainingTokens = tokenBudget - usedTokens
            if (remainingTokens > 0) {
                selected += PromptSummarySelection(
                    summary = summary,
                    snippet = snippet.truncateToTokenBudget(remainingTokens),
                )
            }
            break
        }

        return selected
    }

    private data class MemoryHit(val memory: MemoryEntity, val score: Float)
    private data class SummaryHit(val summary: MemorySummaryEntity, val score: Float)
    private data class PromptMemorySelection(val memory: MemoryEntity, val snippet: String)
    private data class PromptSummarySelection(val summary: MemorySummaryEntity, val snippet: String)

    private companion object {
        const val MAX_MEMORY_RESULTS = 50
        const val MAX_MEMORY_CANDIDATES = 200
        const val CANDIDATE_MULTIPLIER = 4
        const val PROMPT_MEMORY_TOKEN_BUDGET = 10_000
        const val PROMPT_MEMORY_CANDIDATE_LIMIT = 200
        const val PROMPT_RELEVANT_LIMIT = 5
        const val PROMPT_RECENT_LIMIT = 3
        const val PROMPT_SUMMARY_CANDIDATES = 20
        const val PROMPT_SUMMARY_TOKEN_BUDGET = 5_000
        const val MERGE_CANDIDATE_LIMIT = 8
        const val MERGE_THRESHOLD = 0.72f
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}

private fun String.toLikePattern(): String =
    if (isBlank()) "%" else "%${replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"

private fun scoreMemory(content: String, query: String, importance: Float, confidence: Float): Float {
    if (query.isBlank()) return importance * 3f + confidence
    val normalizedContent = content.lowercase()
    val normalizedQuery = query.lowercase()
    val terms = normalizedQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
    var score = importance * 3f + confidence * 2f
    if (normalizedContent.contains(normalizedQuery)) score += 20f
    terms.forEach { term ->
        if (normalizedContent.contains(term)) score += 5f
    }
    if (normalizedContent.startsWith(normalizedQuery)) score += 4f
    return score
}

private fun scoreSummary(summary: MemorySummaryEntity, query: String): Float {
    if (query.isBlank()) return summary.importance
    val haystack = "${summary.title}\n${summary.summary}\n${summary.keywords}".lowercase()
    val normalizedQuery = query.lowercase()
    val terms = normalizedQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
    var score = summary.importance * 3f
    if (haystack.contains(normalizedQuery)) score += 20f
    terms.forEach { term ->
        if (haystack.contains(term)) score += 5f
    }
    if (summary.title.lowercase().contains(normalizedQuery)) score += 6f
    return score
}

private fun MemorySummaryEntity.toPromptSnippet(): String =
    "$title: $summary"

private fun estimateTokens(text: String): Int =
    ((text.length + 2) / 3).coerceAtLeast(1)

private fun String.truncateToTokenBudget(tokenBudget: Int): String {
    val maxChars = (tokenBudget * 3).coerceAtLeast(1)
    if (length <= maxChars) return this
    if (maxChars <= 3) return take(maxChars)
    return take(maxChars - 3).trimEnd().let { "$it..." }
}

private fun similarityScore(a: String, b: String): Float {
    val left = a.keyTerms().toSet()
    val right = b.keyTerms().toSet()
    if (left.isEmpty() || right.isEmpty()) return 0f
    val intersection = left.intersect(right).size.toFloat()
    val union = left.union(right).size.toFloat()
    val jaccard = intersection / union
    val directContainment = if (a.contains(b, ignoreCase = true) || b.contains(a, ignoreCase = true)) 0.35f else 0f
    return (jaccard + directContainment).coerceAtMost(1f)
}

private fun String.keyTerms(): List<String> =
    lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.length >= 2 }

private fun mergeContent(existing: String, incoming: String): String =
    when {
        incoming.isBlank() -> existing
        existing.contains(incoming, ignoreCase = true) -> existing
        incoming.contains(existing, ignoreCase = true) -> incoming
        else -> "$existing\n$incoming"
    }

private fun mergeSource(existing: String, incoming: String): String =
    listOf(existing, incoming)
        .flatMap { it.split(",") }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(",")

private fun mergeJsonStringLists(left: String, right: String): String {    val json = Json { ignoreUnknownKeys = true }
    val leftItems = runCatching { json.decodeFromString<List<String>>(left) }
        .onFailure {
            AppLogger.warn(
                LogTags.Repo,
                "merge_json_list_left_failed",
                "leftLength" to left.length,
                "error" to (it.message ?: it::class.simpleName.orEmpty()),
            )
        }
        .getOrDefault(emptyList())
    val rightItems = runCatching { json.decodeFromString<List<String>>(right) }
        .onFailure {
            AppLogger.warn(
                LogTags.Repo,
                "merge_json_list_right_failed",
                "rightLength" to right.length,
                "error" to (it.message ?: it::class.simpleName.orEmpty()),
            )
        }
        .getOrDefault(emptyList())
    return json.encodeToString((leftItems + rightItems).map { it.trim() }.filter { it.isNotBlank() }.distinct())
}

private fun strongestSensitivity(left: String, right: String): String {
    val order = listOf("normal", "private", "sensitive")
    val normalizedLeft = normalizeSensitivity(left)
    val normalizedRight = normalizeSensitivity(right)
    return if (order.indexOf(normalizedLeft) >= order.indexOf(normalizedRight)) normalizedLeft else normalizedRight
}

private fun normalizeSensitivity(value: String): String =
    when (value.lowercase()) {
        "private" -> "private"
        "sensitive" -> "sensitive"
        else -> "normal"
    }

/** 解 `sourceMessageIds` 里的 JSON 数组；坏数据按空处理，不要让一条脏行阻断遗忘链路。 */
private fun decodeIdList(raw: String): List<String> =
    runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<List<String>>(raw) }
        .getOrDefault(emptyList())
        .map { it.trim() }
        .filter { it.isNotBlank() }

private fun List<MemoryEntity>.filterUsableForPrompt(
    query: String,
    allowPrivateWhenRelevant: Boolean,
): List<MemoryEntity> {
    val now = System.currentTimeMillis()
    val terms = query.keyTerms()
    return filter { memory ->
        val isExpired = memory.expiresAt?.let { it <= now } == true
        if (isExpired) return@filter false

        when (normalizeSensitivity(memory.sensitivity)) {
            "normal" -> true
            "private" -> allowPrivateWhenRelevant && memory.matchesAnyTerm(terms)
            "sensitive" -> allowPrivateWhenRelevant && memory.matchesAnyTerm(terms) && terms.size >= 2
            else -> true
        }
    }
}

private fun MemoryEntity.matchesAnyTerm(terms: List<String>): Boolean {
    if (terms.isEmpty()) return false
    val haystack = content.lowercase()
    return terms.any { haystack.contains(it) }
}
