package com.xiaoqi.companion.core.memory

/**
 * D1「只记用户原话，不记推断」的写路径守卫 + 「症状 ≠ 诊断」的文本净化。
 *
 * 纯对象、无副作用、不读系统时间、不依赖 Android —— 全部决策由入参决定，便于直接单测。
 * 调用点：[com.xiaoqi.companion.data.repository.MemoryRepository.saveMemory] 在落库前调用
 * [evaluate]，`shouldStore == false` 时直接丢弃。**敏感条目不做特殊存储**：
 * 密级只是 `MemoryEntity.sensitivity` 上的一层标记，写路径与普通条目相同（D2 隐私立场不变），
 * 差异只体现在「是否进 prompt / 是否进摘要 / 列表是否默认折叠」。
 */
object MemoryWritePolicy {

    /**
     * 症状/病情必须保持"用户说"的归属，不能变成系统断言。
     *
     * 例：`用户有抑郁症` → `用户说自己有抑郁症`。
     * 已经是归属句式（"用户说…"）的原话不动，避免叠成"用户说自己说自己"。
     */
    fun sanitizeSymptomAttribution(content: String): String {
        if (ATTRIBUTED_PREFIX.containsMatchIn(content)) return content
        return DIAGNOSTIC_PATTERN.replace(content) { match ->
            val (subject, verb, condition) = match.destructured
            "$subject" + "说自己" + verb + condition
        }
    }

    /**
     * 决定一条待写记忆是否入库，以及以什么密级入库。
     *
     * @param content 待写入内容（用户原话或模型提炼句）
     * @param requestedSource 请求方声明的来源，见 [MemorySourceKinds]
     * @param requestedSensitivity 请求方显式指定的密级
     */
    fun evaluate(
        content: String,
        requestedSource: String = MemorySourceKinds.TOOL_SAVE_MEMORY,
        requestedSensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
    ): MemoryWriteDecision {
        val trimmed = content.trim()
        val sanitized = sanitizeSymptomAttribution(trimmed)

        if (sanitized.isBlank()) {
            return MemoryWriteDecision(
                shouldStore = false,
                sensitivity = MemorySensitivity.NORMAL,
                content = sanitized,
                source = requestedSource,
                rejection = MemoryWriteDecision.Rejection.EMPTY_CONTENT,
            )
        }

        if (SensitivityLexicon.requestsForget(sanitized)) {
            return MemoryWriteDecision(
                shouldStore = false,
                sensitivity = MemorySensitivity.SENSITIVE,
                content = sanitized,
                source = requestedSource,
                rejection = MemoryWriteDecision.Rejection.FORGET_REQUEST,
            )
        }

        if (requestedSource in MemorySourceKinds.INFERRED) {
            return MemoryWriteDecision(
                shouldStore = false,
                sensitivity = MemorySensitivity.NORMAL,
                content = sanitized,
                source = requestedSource,
                rejection = MemoryWriteDecision.Rejection.INFERRED_NOT_STATED,
            )
        }

        if (INFERENCE_PHRASES.any { sanitized.contains(it) }) {
            return MemoryWriteDecision(
                shouldStore = false,
                sensitivity = MemorySensitivity.NORMAL,
                content = sanitized,
                source = requestedSource,
                rejection = MemoryWriteDecision.Rejection.INFERENCE_PHRASE,
            )
        }

        var sensitivity = requestedSensitivity.stronger(SensitivityLexicon.classify(sanitized))
        if (SensitivityLexicon.requestsSecrecy(sanitized)) sensitivity = MemorySensitivity.SENSITIVE
        // D1：从图里读出来的内容比用户原话更不可靠，至少按 private 处理，不默认进摘要。
        if (requestedSource == MemorySourceKinds.REFLECTION_VISION) {
            sensitivity = sensitivity.stronger(MemorySensitivity.PRIVATE)
        }

        return MemoryWriteDecision(
            shouldStore = true,
            sensitivity = sensitivity,
            content = sanitized,
            source = requestedSource,
        )
    }

    /** 敏感/私密内容一律不参与摘要生成，避免在列表里外露。 */
    fun isEligibleForSummary(sensitivity: MemorySensitivity): Boolean = !sensitivity.isSensitive

    /** 记忆库列表默认是否折叠该条目。 */
    fun isCollapsedByDefault(sensitivity: MemorySensitivity): Boolean = sensitivity.isSensitive

    private val ATTRIBUTED_PREFIX = Regex("""(用户|对方|他|她|ta)(说|讲|提到|表示|自称|觉得|认为)""")

    /**
     * 断言式诊断句式。中文没有 `\b`，所以显式拆成「主体 + 判断词 + 病症词」三段捕获，
     * 命中后重排成归属句。判断词可缺省（`用户抑郁症` 少见但合法输入，不崩即可）。
     */
    private val DIAGNOSTIC_PATTERN = Regex(
        "(用户|对方|他|她|ta|我)" +
            "(有|得了|患有|是|被诊断出|确诊了)?" +
            "(抑郁症|焦虑症|双相情感障碍|双相|躁郁症|强迫症|创伤后应激障碍|ptsd|" +
            "多动症|adhd|自闭症|孤独症|精神分裂|进食障碍|厌食症|暴食症|恐慌症|惊恐障碍)"
    )

    /** AI 对用户人格/偏好/关系的推断句式——D1 明确禁止入库。 */
    private val INFERENCE_PHRASES = listOf(
        "说明用户是", "表明用户是", "可以看出用户是", "用户属于", "用户倾向于",
        "用户是一个", "用户是个", "用户喜欢整个", "用户喜欢这一类", "用户喜欢这类",
        "用户讨厌这一类", "用户讨厌这类", "根据以上可以推断", "综合来看用户",
        "用户的人格", "用户的性格特点", "用户的依恋类型",
    )
}
