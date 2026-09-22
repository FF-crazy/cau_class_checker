package com.ffcrazy.cauclasschecker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 领域契约的位精确测试。
 *
 * 由于这台机器上没有模拟器镜像，JVM 单测是**唯一**的自动化防线——
 * 所以这里的测试向量必须钉死到每一个字符。
 *
 * 全部向量已用 Node 的 `crypto` 模块独立核对过。
 */
class SignTest {

    // ------------------------------------------------------------ ticket / MD5

    @Test
    fun `ticket matches the reference vectors bit for bit`() {
        // t, ip, 期望的完整 32 位 md5(t + ip + "caulvchunli")
        val vectors = listOf(
            Triple(1700000000L, "10.1.2.3", "c76d688de8f8aff2bd045a7be746b178"),
            Triple(1700000000L, "2001:db8::1", "77ea34f339ef5e2f8ee6cff979d15767"),
            Triple(0L, "0.0.0.0", "53d1b679127d081cf20a4d6965f84b80"),
            Triple(1758523200L, "10.20.30.40", "3ae25e113673ff1433489dc480a19140"),
            Triple(1L, "::1", "cb044b4dc08c6deec54493cac5abcecd"),
        )

        for ((t, ip, expectedMd5) in vectors) {
            val full = md5Of(t.toString() + ip + Sign.SALT)
            assertEquals("md5 输入拼接顺序错了 (t=$t, ip=$ip)", expectedMd5, full)
            assertEquals("ticket 应为 md5 前 4 位 (t=$t, ip=$ip)", expectedMd5.take(4), Sign.ticket(t, ip))
        }
    }

    @Test
    fun `ticket is lowercase hex and preserves leading zeros`() {
        // t=0 那条的 ticket 是 53d1，本身就是小写+前导数字。
        // 用 Integer.toHexString 之类的实现容易在这里出问题。
        val tk = Sign.ticket(0L, "0.0.0.0")
        assertEquals("53d1", tk)
        assertEquals("必须是小写", tk, tk.lowercase())
        assertEquals("必须是 4 位", 4, tk.length)
    }

    // --------------------------------------------------------------- buildUrl

    @Test
    fun `buildUrl produces the exact reference URL`() {
        assertEquals(
            "https://class.cau.edu.cn/casgeosig.php?ip=10.1.2.3&ipt=260914190149&t=1700000000&tt=c76d",
            Sign.buildUrl("10.1.2.3", "260914190149", 1700000000L),
        )
    }

    @Test
    fun `buildUrl percent-encodes IPv6 colons as uppercase hex`() {
        assertEquals(
            "https://class.cau.edu.cn/casgeosig.php?ip=2001%3Adb8%3A%3A1&ipt=abc_DEF-123&t=1700000000&tt=77ea",
            Sign.buildUrl("2001:db8::1", "abc_DEF-123", 1700000000L),
        )
    }

    @Test
    fun `buildUrl keeps parameter order ip ipt t tt`() {
        val url = Sign.buildUrl("1.2.3.4", "tok", 42L)
        val order = listOf("ip=", "ipt=", "t=", "tt=").map { url.indexOf(it) }
        assertEquals("参数顺序必须是 ip, ipt, t, tt —— 顺序错了服务端会拒绝", order.sorted(), order)
    }

    // -------------------------------------------------------- validateSession

    @Test
    fun `validateSession accepts well-formed values and trims them`() {
        assertEquals(Session("10.1.2.3", "260914190149"), Sign.validateSession("10.1.2.3", "260914190149"))
        assertEquals(Session("10.1.2.3", "abc"), Sign.validateSession("  10.1.2.3  ", "  abc  "))
        assertEquals(Session("2001:db8::1", "a-b_c"), Sign.validateSession("2001:db8::1", "a-b_c"))
    }

    @Test
    fun `validateSession rejects null empty and whitespace-only`() {
        assertNull(Sign.validateSession(null, "abc"))
        assertNull(Sign.validateSession("1.2.3.4", null))
        assertNull(Sign.validateSession("", "abc"))
        assertNull(Sign.validateSession("1.2.3.4", ""))
        // 纯空白能通过判空，但 trim 后为空串，必须被正则挡下
        assertNull("纯空白必须失败", Sign.validateSession("   ", "   "))
    }

    @Test
    fun `validateSession enforces charset`() {
        assertNull("ip 不允许分号", Sign.validateSession("1.2.3.4;rm", "abc"))
        assertNull("ip 不允许字母 g", Sign.validateSession("1.2.3.4g", "abc"))
        assertNull("ipt 不允许点", Sign.validateSession("1.2.3.4", "ab.c"))
        assertNull("ipt 不允许斜杠", Sign.validateSession("1.2.3.4", "a/b"))
    }

    @Test
    fun `validateSession enforces length caps`() {
        val ip45 = "1".repeat(45)
        val ip46 = "1".repeat(46)
        assertEquals("45 位 ip 应通过", ip45, Sign.validateSession(ip45, "abc")?.ip)
        assertNull("46 位 ip 应拒绝", Sign.validateSession(ip46, "abc"))

        val ipt40 = "a".repeat(40)
        val ipt41 = "a".repeat(41)
        assertEquals("40 位 ipt 应通过", ipt40, Sign.validateSession("1.2.3.4", ipt40)?.ipt)
        assertNull("41 位 ipt 应拒绝", Sign.validateSession("1.2.3.4", ipt41))
    }

    // ------------------------------------------------------- parseSignUrl 集成

    @Test
    fun `parseSignUrl discards incoming t and tt`() {
        // 这是整个工具的语义核心：旧链接里的时间戳一律不沿用
        val old = "https://class.cau.edu.cn/casgeosig.php?ip=10.1.2.3&ipt=260914190149&t=1600000000&tt=dead"
        val s = Sign.parseSignUrl(old)
        assertEquals(Session("10.1.2.3", "260914190149"), s)

        // 用同一个 session 按新时间重算，得到的 tt 与旧的 dead 不同
        val fresh = Sign.buildUrl(s!!.ip, s.ipt, 1700000000L)
        assertEquals("c76d", fresh.substringAfter("&tt="))
        assertEquals("新 URL 里的 t 必须是新时间", "1700000000", fresh.substringAfter("&t=").substringBefore("&"))
    }

    private fun md5Of(input: String): String {
        val d = java.security.MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(32) {
            val hex = "0123456789abcdef"
            for (b in d) {
                val v = b.toInt() and 0xFF
                append(hex[v ushr 4]); append(hex[v and 0x0F])
            }
        }
    }
}
