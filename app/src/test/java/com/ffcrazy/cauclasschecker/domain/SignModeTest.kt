package com.ffcrazy.cauclasschecker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两种签到模式的识别与生成。
 *
 * 链接取自 2026-09-22 线上实测。
 */
class SignModeTest {

    private val strictUrl =
        "https://class.cau.edu.cn/casgeopicsig.php?ip=219.225.103.37&ipt=260922191552&t=1790076110&tt=286d"

    private val normalUrl =
        "https://class.cau.edu.cn/casgeosig.php?ip=219.225.103.37&ipt=260922174709&t=1790072747&tt=3007"

    // ------------------------------------------------------------------ 识别

    @Test
    fun `严格模式的链接能被认出来`() {
        assertEquals(SignMode.STRICT, Sign.detectMode(strictUrl))
    }

    @Test
    fun `普通模式还是普通模式`() {
        assertEquals(SignMode.NORMAL, Sign.detectMode(normalUrl))
    }

    @Test
    fun `裸查询串和空值都当普通模式`() {
        // 裸查询串没有端点信息，判不出来 —— 而它只有一个来源：
        // 我们自己分享出去的普通模式链接
        assertEquals(SignMode.NORMAL, Sign.detectMode("?ip=1.2.3.4&ipt=tok"))
        assertEquals(SignMode.NORMAL, Sign.detectMode(null))
    }

    // ------------------------------------------------------------------ 解析

    @Test
    fun `扫到严格模式时解析结果带着模式`() {
        val session = Sign.parseSignUrl(strictUrl)!!
        assertEquals("219.225.103.37", session.ip)
        assertEquals("260922191552", session.ipt)
        assertEquals(SignMode.STRICT, session.mode)
    }

    @Test
    fun `ip 和 ipt 的解析与模式无关`() {
        // 两种模式的 URL 形状完全一样，只有端点名不同
        val strict = Sign.parseSignUrl(strictUrl)!!
        val normal = Sign.parseSignUrl(normalUrl)!!
        assertEquals(normal.ip, strict.ip)
    }

    // ------------------------------------------------------------------ 生成

    @Test
    fun `严格模式生成严格模式的链接`() {
        // 最容易错的一处：扫到严格模式却生成普通模式的码，扫进去当然签不上
        val url = Sign.buildUrl("10.1.2.3", "260914190149", 1700000000L, SignMode.STRICT)
        assertTrue("端点必须换成 casgeopicsig.php，实际：$url", url.startsWith("https://class.cau.edu.cn/casgeopicsig.php?"))
        assertTrue("参数形状两种模式完全一样，实际：$url", url.endsWith("&t=1700000000&tt=c76d"))
    }

    @Test
    fun `不传模式时仍是普通模式`() {
        assertTrue(Sign.buildUrl("10.1.2.3", "t", 1L).startsWith("https://class.cau.edu.cn/casgeosig.php?"))
        assertEquals(SignMode.NORMAL, Sign.validateSession("1.2.3.4", "tok")!!.mode)
    }

    @Test
    fun `扫进来再发出去，模式不会丢`() {
        val session = Sign.parseSignUrl(strictUrl)!!
        val url = Sign.buildUrl(session.ip, session.ipt, 1790076110L, session.mode)
        assertTrue(url.contains("casgeopicsig.php"))
    }
}
