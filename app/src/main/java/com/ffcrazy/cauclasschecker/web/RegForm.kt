package com.ffcrazy.cauclasschecker.web

import java.net.URI

/**
 * 签到登记表单。
 *
 * `casgeosig.php` 校验通过后返回的**不是结果，而是一张表单** —— 真正的落库发生在提交它。
 * 所以一次签到是两步：先 GET 取表，再 POST 提交。
 *
 * action 的形态**实测有两种**，所以端点绝不能写死：
 *
 * ```
 * reg.php?ip=219.225.103.37&ipt=260922174709
 * https://class.cau.edu.cn/casgeoreg.php?ip=…&ipt=…&pst=113&pict=1790072747
 * ```
 *
 * 前者是相对路径，后者是绝对路径且被 HTML 转义成 `&amp;`。服务端为什么换端点我们不知道，
 * 也不需要知道 —— **解析出来是什么就提交什么**，不去解释 `pst` / `pict` 的含义。
 */
data class RegForm(
    /** 已解析成绝对地址、且 HTML 反转义过的提交目标。 */
    val action: String,
    /** 表单里的全部 input，name -> value（没有 value 属性的记为空串）。 */
    val fields: Map<String, String>,
) {
    companion object {

        private val FORM_ACTION = Regex(
            """<form\b[^>]*?\baction\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
            RegexOption.IGNORE_CASE,
        )
        private val INPUT_TAG = Regex("""<input\b([^>]*)>""", RegexOption.IGNORE_CASE)

        private val NAME_ATTR =
            Regex("""\bname\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
        private val VALUE_ATTR =
            Regex("""\bvalue\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
        private val TYPE_ATTR =
            Regex("""\btype\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

        /**
         * 从页面里解析出登记表单；页面上没有表单（例如拿到的是「请重新扫描」错误页）时返回 null。
         *
         * [pageUrl] 是**实际取到这个页面的地址**（跟随跳转之后的），相对 action 靠它补全。
         */
        fun parse(html: String, pageUrl: String): RegForm? {
            val m = FORM_ACTION.find(html) ?: return null
            val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
                .ifEmpty { m.groupValues[3] }
                .ifEmpty { return null }

            val action = resolve(unescape(raw), pageUrl) ?: return null

            val fields = LinkedHashMap<String, String>()
            for (input in INPUT_TAG.findAll(html)) {
                val attrs = input.groupValues[1]

                // 提交按钮没有 name，但万一有，也别当成字段提交上去
                val type = firstGroup(TYPE_ATTR.find(attrs))
                if (type.equals("submit", true) || type.equals("button", true)) continue

                val name = firstGroup(NAME_ATTR.find(attrs))
                if (name.isEmpty()) continue

                fields[name] = unescape(firstGroup(VALUE_ATTR.find(attrs)))
            }

            return RegForm(action, fields)
        }

        /** 三个可选分组（双引号 / 单引号 / 不带引号）里取第一个非空的。 */
        private fun firstGroup(m: MatchResult?): String =
            if (m == null) "" else m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }

        /** 把相对 action 补成绝对地址。 */
        private fun resolve(raw: String, pageUrl: String): String? = try {
            URI(pageUrl).resolve(raw).toString()
        } catch (_: Exception) {
            null
        }

        /**
         * HTML 反转义。
         *
         * `&amp;` **必须最后换**，否则 `&amp;lt;` 会被拆成 `<`（多解一层）。
         */
        private fun unescape(s: String): String = s
            .replace("&#38;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
    }
}
