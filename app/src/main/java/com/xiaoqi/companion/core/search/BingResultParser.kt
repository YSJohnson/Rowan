package com.xiaoqi.companion.core.search

/**
 * 从 Bing 结果页 HTML 里抠出搜索结果。
 *
 * 免密钥的代价就是要解析页面结构，所以这里对 HTML 的假设要写得**宽**：先按 `b_algo`
 * 块切分，再在每块里分别找标题链接和摘要；任何一块抠不出链接就跳过，不影响其它块。
 * Bing 改版时最坏的结果是返回空列表（上层会提示换后端），而不是抛异常。
 *
 * 抽成纯函数是为了能直接用抓下来的 HTML 做单测 —— provider 的端点是写死的常量，
 * 走 Hilt 注入（`@Inject` 构造不支持默认参数）塞不进 MockWebServer 地址。
 */
internal object BingResultParser {

    /** 一条结果就是一个 `<li class="b_algo">`；用下一个块或 `</ol>` 作为结束边界。 */
    private val RESULT_BLOCK = Regex(
        """<li class="b_algo".*?(?=<li class="b_algo"|</ol>)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val RESULT_LINK = Regex(
        """<h2[^>]*>\s*<a[^>]*\bhref="([^"]+)"""",
        RegexOption.IGNORE_CASE,
    )

    private val RESULT_TITLE = Regex(
        """<h2[^>]*>\s*<a[^>]*>(.*?)</a>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val RESULT_SNIPPET = Regex(
        """<p[^>]*>(.*?)</p>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val TAG = Regex("<[^>]+>")

    /** 块级标签要换成空格，行内标签（`<b>`/`<strong>` 等）要直接删掉。 */
    private val BLOCK_TAG = Regex(
        """</?(?:br|p|div|li|ul|ol|h[1-6]|tr|td|span)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(html: String, maxResults: Int): List<WebSearchResult> {
        if (html.isBlank()) return emptyList()
        return RESULT_BLOCK.findAll(html)
            .mapNotNull { block -> block.value.toResult() }
            .take(maxResults.coerceAtLeast(1))
            .toList()
    }

    private fun String.toResult(): WebSearchResult? {
        val url = RESULT_LINK.find(this)?.groupValues?.get(1)?.let(::decodeHtmlEntities)
        if (url.isNullOrBlank()) return null
        val title = RESULT_TITLE.find(this)
            ?.groupValues?.get(1)
            ?.let(::plainText)
            .orEmpty()
        val snippet = RESULT_SNIPPET.find(this)
            ?.groupValues?.get(1)
            ?.let(::plainText)
            .orEmpty()
        return WebSearchResult(title = title, url = url, snippet = snippet)
    }

    /**
     * 去标签 → 还原实体 → 折叠空白。
     *
     * 行内标签必须**删空**而不是换空格：Bing 会把命中的查询词用 `<b>` 包起来，
     * 一律换空格的话中文标题会变成「最新 人工智能 快讯」这种夹空格的怪样子。
     * 只有块级标签才换空格，用来保住词与词之间的分隔。
     */
    private fun plainText(htmlFragment: String): String =
        decodeHtmlEntities(TAG.replace(BLOCK_TAG.replace(htmlFragment, " "), ""))
            .replace(Regex("\\s+"), " ")
            .trim()

    private val ENTITY = Regex("""&(#x?[0-9A-Fa-f]+|[a-zA-Z]+);""")

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "ensp" to " ",
        "emsp" to " ",
        "thinsp" to " ",
        "middot" to "·",
        "hellip" to "…",
        "mdash" to "—",
        "ndash" to "–",
        "lsquo" to "‘",
        "rsquo" to "’",
        "ldquo" to "“",
        "rdquo" to "”",
    )

    /**
     * 还原 HTML 实体。Bing 的摘要里混着 `&ensp;` 和 `&#0183;` 这类字符，
     * 不还原的话搜索结果读起来全是乱码。
     */
    internal fun decodeHtmlEntities(input: String): String =
        ENTITY.replace(input) { match ->
            val body = match.groupValues[1]
            val decoded = if (body.startsWith("#")) {
                val codePoint = if (body.startsWith("#x", ignoreCase = true)) {
                    body.drop(2).toIntOrNull(16)
                } else {
                    body.drop(1).toIntOrNull()
                }
                codePoint?.takeIf { it in 1..MAX_CODE_POINT }?.let { String(Character.toChars(it)) }
            } else {
                NAMED_ENTITIES[body.lowercase()]
            }
            decoded ?: match.value
        }

    private const val MAX_CODE_POINT = 0x10FFFF
}
