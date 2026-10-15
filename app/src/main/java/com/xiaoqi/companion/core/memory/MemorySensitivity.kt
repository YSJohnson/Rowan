package com.xiaoqi.companion.core.memory

/**
 * 记忆密级（D1）。存储值是 `MemoryEntity.sensitivity` / `MemoryDao` 的字符串，
 * 顺序即强度，**只允许追加更高级别，不要重排已有项**——已落库的数据靠 [stronger] 比较大小。
 */
enum class MemorySensitivity(val storageValue: String) {
    /** 普通内容，可进 prompt、可进摘要、可在记忆库列表正常展开。 */
    NORMAL("normal"),

    /**
     * 关系人、情绪困扰等情境信息。可进 prompt（仅在当轮话题相关时），
     * 但默认折叠展示，避免在列表里一眼看到。
     */
    PRIVATE("private"),

    /**
     * 健康、财务、性、创伤等高敏内容。只在当轮原话明确相关时才可进 prompt，
     * 永不进摘要，记忆库列表默认折叠。
     */
    SENSITIVE("sensitive");

    val isSensitive: Boolean get() = this != NORMAL

    /** 取更强的一方，用于合并写：高密级一旦写入不会被低密级覆盖。 */
    fun stronger(other: MemorySensitivity): MemorySensitivity =
        if (ordinal >= other.ordinal) this else other

    companion object {
        /** 未知/历史值一律降级为 [NORMAL]，避免因脏数据把内容误判成高敏而不展示。 */
        fun fromStorage(value: String?): MemorySensitivity =
            entries.firstOrNull { it.storageValue.equals(value?.trim(), ignoreCase = true) } ?: NORMAL
    }
}

/** 记忆来源。写入守卫按来源区分「用户亲口说的」与「模型推断的」。 */
object MemorySourceKinds {
    const val TOOL_SAVE_MEMORY = "tool:save_memory"
    const val TOOL_UPDATE_STATE = "tool:update_state"
    const val POST_TURN_FALLBACK = "post_turn:fallback"
    const val ONBOARDING = "onboarding"
    const val REFLECTION_VISION = "reflection:vision"

    /**
     * T5 对话模式偏好。这不是模型推断，而是把用户**当轮原话**里的显式信号
     * （"我该怎么办" / "别给建议"）转写成一句偏好，所以允许入库。
     */
    const val CHAT_MODE_PREFERENCE = "chat_mode:preference"

    /** 模型跨轮综合出的结论——属于推断，不进记忆库。 */
    const val REFLECTION_SYNTHESIS = "reflection:synthesis"

    /** 模型对用户人格/偏好/关系的判断——属于推断，不进记忆库。 */
    const val AI_INFERENCE = "ai:inference"

    /** MCP / 搜索等外部数据回写——不是用户说的，不进记忆库。 */
    const val EXTERNAL_TOOL_RESULT = "tool:external_result"

    /** 用户**原话**的转写来源；这些来源允许入库，即使内容是模型组织的句子。 */
    val USER_STATED: Set<String> = setOf(
        TOOL_SAVE_MEMORY,
        TOOL_UPDATE_STATE,
        POST_TURN_FALLBACK,
        ONBOARDING,
        REFLECTION_VISION,
        CHAT_MODE_PREFERENCE,
    )

    val INFERRED: Set<String> = setOf(REFLECTION_SYNTHESIS, AI_INFERENCE, EXTERNAL_TOOL_RESULT)
}

/** [MemoryWritePolicy.evaluate] 的产出。 */
data class MemoryWriteDecision(
    val shouldStore: Boolean,
    val sensitivity: MemorySensitivity,
    /** 净化后的内容；[shouldStore] 为 false 时无意义。 */
    val content: String,
    /** 白名单来源标识，便于排查「为什么这条没进库」。 */
    val source: String,
    /** [shouldStore] 为 false 时的原因；为 true 时为 null。 */
    val rejection: Rejection? = null,
) {
    enum class Rejection {
        /** 内容为空。 */
        EMPTY_CONTENT,

        /** 来源是模型推断（人格标签、跨轮结论）或外部工具结果。 */
        INFERRED_NOT_STATED,

        /** 文本命中推断句式（"用户喜欢…这一类""说明用户是…的人"）。 */
        INFERENCE_PHRASE,

        /**
         * D1「忘记这件事」：命中遗忘意图，不落库。
         * 真正的删除由 [MemoryRepository.forgetMemory] 执行。
         */
        FORGET_REQUEST,
    }
}

/** 密级分层用的信号词。命中即升级，不看上下文——宁可高估密级，也不要让高敏内容裸奔。 */
internal object SensitivityLexicon {

    private val HEALTH_TERMS = listOf(
        "癌", "肿瘤", "化疗", "放疗", "手术", "确诊", "抑郁", "焦虑症", "双向情感障碍", "双相",
        "失眠", "厌食", "暴食", "自残", "自杀", "轻生", "割腕", "成瘾", "戒断", "药物", "吃药",
        "抗抑郁", "安定", "安眠药", "止痛药", "激素", "输血", "透析", "糖尿病", "高血压", "心脏病",
        "哮喘", "癫痫", "乙肝", "艾滋", "hpv", "hiv", "怀孕", "流产", "月经", "痛经", "更年期",
        "体检报告", "化验单", "活检", "复诊", "住院", "急诊",
    )

    private val FINANCE_TERMS = listOf(
        "工资", "薪水", "年薪", "月薪", "存款", "负债", "欠款", "欠债", "网贷", "信用卡", "逾期",
        "房贷", "车贷", "花呗", "借呗", "理财", "基金", "股票", "亏损", "爆仓", "破产", "借钱",
        "还不上", "被裁", "失业", "赔偿金", "彩礼", "分手费", "赡养费",
    )

    private val RELATIONSHIP_TERMS = listOf(
        "出轨", "劈腿", "外遇", "小三", "离婚", "复婚", "前任", "分手", "家暴", "冷暴力",
        "性侵", "性骚扰", "猥亵", "被渣", "渣男", "渣女", "异地恋", "网恋", "流产", "堕胎",
    )

    private val TRAUMA_TERMS = listOf(
        "去世", "过世", "走了", "葬礼", "遗书", "霸凌", "校园暴力", "虐待", "童年", "阴影",
        "原生家庭", "被抛弃", "猥亵", "性侵",
    )

    private val PRIVATE_TERMS = listOf(
        "我妈", "我爸", "我哥", "我姐", "我弟", "我妹", "老公", "老婆", "男朋友", "女朋友",
        "伴侣", "对象", "儿子", "女儿", "孩子", "婆婆", "公公", "岳母", "岳父", "领导", "老板",
        "同事", "室友", "闺蜜", "兄弟", "家里",
    )

    /** 命中任意词即算该层。返回最高命中的层。 */
    fun classify(content: String): MemorySensitivity {
        val haystack = content.lowercase()
        return when {
            HEALTH_TERMS.any { haystack.contains(it.lowercase()) } -> MemorySensitivity.SENSITIVE
            FINANCE_TERMS.any { haystack.contains(it.lowercase()) } -> MemorySensitivity.SENSITIVE
            RELATIONSHIP_TERMS.any { haystack.contains(it.lowercase()) } -> MemorySensitivity.SENSITIVE
            TRAUMA_TERMS.any { haystack.contains(it.lowercase()) } -> MemorySensitivity.SENSITIVE
            PRIVATE_TERMS.any { haystack.contains(it.lowercase()) } -> MemorySensitivity.PRIVATE
            else -> MemorySensitivity.NORMAL
        }
    }

    /** 用户显式要求保密的措辞，直接把密级顶到 [MemorySensitivity.SENSITIVE]。 */
    private val SECRECY_REQUESTS = listOf(
        "别告诉别人", "不要告诉别人", "别跟别人说", "不要跟别人说", "只有你知道", "你别记",
        "别记下来", "不要记", "别写进记忆", "保密", "这是秘密", "别说出去",
    )

    fun requestsSecrecy(content: String): Boolean {
        val haystack = content.lowercase()
        return SECRECY_REQUESTS.any { haystack.contains(it) }
    }

    /**
     * D1「忘掉这件事」的意图识别。命中后本轮不写入，
     * 且调用方应转调 [MemoryRepository.forgetMemory] 执行真实删除。
     */
    private val FORGET_PHRASES = listOf(
        "忘掉这件事", "忘掉这个", "忘掉吧", "忘了这件事", "忘了吧", "别记得这件事",
        "把这个忘了", "把这件事忘了", "不要再提起", "别再提了", "删掉这条记忆",
        "删掉这个记忆", "删除这条记忆", "忘记我说过的",
    )

    fun requestsForget(content: String): Boolean {
        val haystack = content.lowercase()
        return FORGET_PHRASES.any { haystack.contains(it) }
    }
}
