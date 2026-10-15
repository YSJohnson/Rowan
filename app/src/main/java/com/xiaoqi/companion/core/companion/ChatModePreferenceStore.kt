package com.xiaoqi.companion.core.companion

import com.xiaoqi.companion.data.db.converter.MemoryType
import com.xiaoqi.companion.data.repository.MemoryRepository
import com.xiaoqi.companion.data.repository.SaveMemoryRequest
import javax.inject.Inject

/** 模式偏好记忆的来源标记，和 MemorySources 里的其他 source 同级，便于回溯。 */
const val SOURCE_CHAT_MODE_PREFERENCE = "chat_mode:preference"

private const val SENSITIVITY_NORMAL = "normal"
private const val SENSITIVITY_PRIVATE = "private"
private const val SENSITIVITY_SENSITIVE = "sensitive"

/**
 * D4 要求学会「什么情境下要建议、什么情境下只想被听」，所以偏好必须按情境维度存，
 * 一个全局开关表达不了。sensitivity 沿用 MemoryEntity 的 normal/private/sensitive 分层。
 */
enum class ChatModeSituation(
    val storageKey: String,
    val label: String,
    val sensitivity: String,
) {
    WORK("work", "工作/职场", SENSITIVITY_NORMAL),
    STUDY("study", "学业/考试", SENSITIVITY_NORMAL),
    RELATIONSHIP("relationship", "感情/亲密关系", SENSITIVITY_SENSITIVE),
    FAMILY("family", "家人", SENSITIVITY_PRIVATE),
    HEALTH("health", "身体/健康", SENSITIVITY_SENSITIVE),
    MONEY("money", "金钱/财务", SENSITIVITY_SENSITIVE),
    EMOTION("emotion", "情绪/自我", SENSITIVITY_PRIVATE),
    OTHER("other", "日常", SENSITIVITY_NORMAL),
}

data class ChatModePreference(
    val situation: ChatModeSituation,
    val mode: ChatMode,
    val observedAtMillis: Long,
)

object ChatModePreferenceExtractor {

    private val KEYWORDS: Map<ChatModeSituation, List<String>> = mapOf(
        ChatModeSituation.WORK to listOf(
            "工作", "职场", "上班", "老板", "同事", "加班", "项目", "面试", "跳槽", "离职", "裁员", "汇报", "开会", "kpi",
        ),
        ChatModeSituation.RELATIONSHIP to listOf(
            "感情", "恋爱", "男友", "女友", "男朋友", "女朋友", "老公", "老婆", "对象", "分手", "暧昧", "相亲", "出轨",
        ),
        ChatModeSituation.FAMILY to listOf(
            "爸妈", "父母", "妈妈", "爸爸", "家人", "家里", "婆婆", "公公", "亲戚", "孩子", "儿子", "女儿", "姐姐", "哥哥",
            "弟弟", "妹妹",
        ),
        ChatModeSituation.HEALTH to listOf(
            "健康", "生病", "医院", "医生", "身体", "失眠", "睡不着", "焦虑症", "抑郁", "体检", "吃药", "睡眠",
        ),
        ChatModeSituation.STUDY to listOf(
            "学习", "考试", "考研", "论文", "毕业", "学业", "学校", "老师", "成绩",
        ),
        ChatModeSituation.MONEY to listOf(
            "钱", "工资", "薪水", "房贷", "债务", "欠款", "投资", "理财", "花销", "财务",
        ),
        ChatModeSituation.EMOTION to listOf(
            "情绪", "难过", "难受", "焦虑", "压力", "孤独", "委屈", "崩溃", "心情", "烦",
        ),
    )

    /** 只有显式信号才落记忆；隐式追问是推断，返回 null。 */
    fun extract(turn: ChatModeTurn, decision: ChatModeDecision): ChatModePreference? {
        if (!decision.explicit) return null
        return ChatModePreference(
            situation = classify(turn.text),
            mode = decision.mode,
            observedAtMillis = turn.atMillis,
        )
    }

    /** 命中关键词最多的情境胜出；同分按 [ChatModeSituation] 声明顺序取先者，保证判定稳定。 */
    fun classify(text: String): ChatModeSituation {
        val haystack = text.lowercase()
        var best = ChatModeSituation.OTHER
        var bestHits = 0
        for ((situation, words) in KEYWORDS) {
            val hits = words.count { haystack.contains(it) }
            if (hits > bestHits) {
                best = situation
                bestHits = hits
            }
        }
        return best
    }

    /** 写成事实而非摘要，和 update_state 的记忆写法保持一致，便于 search_memory 命中。 */
    fun toMemoryContent(preference: ChatModePreference): String = when (preference.mode) {
        ChatMode.GUIDE ->
            "在「${preference.situation.label}」话题上，用户希望 Aura 直接给建议和办法，不要只倾听"
        ChatMode.LISTEN ->
            "在「${preference.situation.label}」话题上，用户只想被倾听，不要主动给建议"
    }
}

/** 偏好落库出口，便于单测注入假实现；真实实现见 [MemoryRepositoryChatModePreferenceSink]。 */
fun interface ChatModePreferenceSink {
    suspend fun remember(content: String, sensitivity: String, sourceMessageIds: List<String>)
}

/**
 * 把模式偏好写进长期记忆。冷却用进程内 map：重启后可能重复写一次，
 * 由 MemoryRepository 的 merge 兜底，不值得为此多读一次库。
 */
class ChatModePreferenceStore(
    private val sink: ChatModePreferenceSink,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MS,
) {

    private val lastWrittenAt = mutableMapOf<String, Long>()

    suspend fun record(
        turn: ChatModeTurn,
        decision: ChatModeDecision,
        sourceMessageIds: List<String> = emptyList(),
    ): ChatModePreference? {
        val preference = ChatModePreferenceExtractor.extract(turn, decision) ?: return null
        val key = "${preference.situation.storageKey}|${preference.mode.storageKey}"
        val now = nowMillis()
        val last = lastWrittenAt[key]
        if (last != null && now - last < cooldownMillis) return null
        sink.remember(
            content = ChatModePreferenceExtractor.toMemoryContent(preference),
            sensitivity = preference.situation.sensitivity,
            sourceMessageIds = sourceMessageIds,
        )
        lastWrittenAt[key] = now
        return preference
    }

    companion object {
        /** 同一情境同一模式 7 天内只写一次，避免每次说「给点建议」都产生一条记忆。 */
        const val DEFAULT_COOLDOWN_MS: Long = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * 走 MemoryRepository 现有写路径：type=FACT + source=[SOURCE_CHAT_MODE_PREFERENCE]。
 *
 * 注意：sensitivity 非 normal 的记忆会被 selectPromptContext 过滤掉（allowPrivateWhenRelevant=false），
 * 因此敏感情境（健康/金钱/亲密关系）的偏好目前只存不用 —— 接线时要一并放行，见 docs/plan/t5-ui-hook.md。
 */
class MemoryRepositoryChatModePreferenceSink @Inject constructor(
    private val memoryRepository: MemoryRepository,
) : ChatModePreferenceSink {

    override suspend fun remember(content: String, sensitivity: String, sourceMessageIds: List<String>) {
        memoryRepository.saveMemory(
            SaveMemoryRequest(
                content = content,
                type = MemoryType.FACT,
                importance = 0.6f,
                confidence = 0.9f,
                source = SOURCE_CHAT_MODE_PREFERENCE,
                sourceMessageIds = sourceMessageIds,
                sensitivity = sensitivity,
            ),
        )
    }
}
