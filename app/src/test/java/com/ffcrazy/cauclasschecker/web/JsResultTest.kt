package com.ffcrazy.cauclasschecker.web

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 解开 `WebView.evaluateJavascript` 的返回值。
 *
 * 手工反转义 JSON 字符串最容易被边角料坑到，所以改成「JS 端先 encodeURIComponent」。
 * 这个文件钉的就是那条捷径的边界。
 */
class JsResultTest {

    @Test
    fun `解开 encodeURIComponent 过的正文`() {
        // JS 返回的是 encodeURIComponent(document.body.innerText)，
        // 外面还套着 evaluateJavascript 加的引号
        assertEquals("签到成功", decodeJsResult("\"%E7%AD%BE%E5%88%B0%E6%88%90%E5%8A%9F\""))
    }

    @Test
    fun `null undefined 空串都当空`() {
        // evaluateJavascript 对没有返回值的脚本直接给这两个字面量
        assertEquals("", decodeJsResult("null"))
        assertEquals("", decodeJsResult("undefined"))
        assertEquals("", decodeJsResult(null))
        assertEquals("", decodeJsResult(""))
        assertEquals("", decodeJsResult("\"\""))
    }

    @Test
    fun `加号不会被当成空格`() {
        // 这条捷径唯一要小心的地方：URLDecoder 默认把 + 解成空格。
        // 但 encodeURIComponent 把空格编成 %20、把 + 编成 %2B ——
        // 所以正文里的 + 回来还是 +，不会被吃掉
        assertEquals("a+b", decodeJsResult("\"a%2Bb\""))
        assertEquals("a b", decodeJsResult("\"a%20b\""))
    }

    @Test
    fun `换行和引号都能还原`() {
        // 手工反转义最容易漏的就是这两个
        assertEquals(
            "第一行\n第二行",
            decodeJsResult("\"%E7%AC%AC%E4%B8%80%E8%A1%8C%0A%E7%AC%AC%E4%BA%8C%E8%A1%8C\""),
        )
    }

    @Test
    fun `没有外层引号也能解`() {
        assertEquals("签到成功", decodeJsResult("%E7%AD%BE%E5%88%B0%E6%88%90%E5%8A%9F"))
    }

    @Test
    fun `解不开时原样返回而不是抛异常`() {
        // 真出问题宁可把原文交给判定逻辑（会归到「未识别」并显示出来），
        // 也不要在这里崩掉整个签到流程。裸的 % 就是解不开的典型。
        assertEquals("100%", decodeJsResult("\"100%\""))
    }

    @Test
    fun `解出来的正文能直接被判定认出`() {
        // 这一头一尾必须对得上：WebView 里取到的文本要能喂给 classifyCheckIn
        val fromWebView = "\"%E7%AD%BE%E5%88%B0%E6%88%90%E5%8A%9F\""
        assertEquals(
            true,
            classifyCheckIn(decodeJsResult(fromWebView)) is CasClient.CheckInResult.Success,
        )
    }
}
