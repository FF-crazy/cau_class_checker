package com.ffcrazy.cauclasschecker.web

import java.net.URLDecoder

/**
 * 解开 `WebView.evaluateJavascript` 的返回值。
 *
 * 那个回调拿到的是一段 **JSON 字符串字面量** —— 带外层引号，内部按 JSON 规则转义
 * （换行变成 `\n`、引号变成 `\"`、中文可能变成 `\uXXXX`）。直接当文本用会带着引号，
 * 手工反转义又很容易漏掉边角。
 *
 * 所以换个思路：**让 JS 端先 `encodeURIComponent`**（见 `ManualCheckInScreen.PROBE_JS`），
 * 回来的就只剩 ASCII 的百分号编码 —— 去掉外层引号、`URLDecoder` 一次即可，
 * 没有转义规则要猜。
 *
 * ⚠️ 这条捷径成立的前提是 JS 端**确实**做了 encodeURIComponent。
 * 直接 `evaluateJavascript("document.body.innerText")` 拿到的原始文本里可能是
 * 转义过的 `\n` 和 `\"`，那样这个函数会原样吐出来而不是解开 —— 所以约定写在这里。
 */
internal fun decodeJsResult(raw: String?): String {
    if (raw.isNullOrEmpty()) return ""

    val body = raw.trim().removeSurrounding("\"")

    // evaluateJavascript 对 null / undefined 直接返回这两个字面量
    if (body == "null" || body == "undefined") return ""

    return runCatching { URLDecoder.decode(body, "UTF-8") }.getOrDefault(body)
}
