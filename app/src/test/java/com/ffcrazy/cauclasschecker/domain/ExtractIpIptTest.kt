package com.ffcrazy.cauclasschecker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `extractIpIpt` / `parseSignUrl` 的表驱动测试。
 *
 * 输入形态来自真实使用场景：微信扫出来的链接、随手复制的一整段文字、
 * 分享页 `?ip=&ipt=`、以及各种残缺/畸形输入。
 */
class ExtractIpIptTest {

    private fun extract(raw: String?) = Sign.extractIpIpt(raw)

    private fun assertExtracts(raw: String, ip: String, ipt: String) {
        val (gotIp, gotIpt) = extract(raw)
        assertEquals("ip 提取错误，输入：$raw", ip, gotIp)
        assertEquals("ipt 提取错误，输入：$raw", ipt, gotIpt)
    }

    private fun assertExtractsNothing(raw: String?) {
        val (ip, ipt) = extract(raw)
        assertNull("不该提取出 ip，输入：$raw", ip)
        assertNull("不该提取出 ipt，输入：$raw", ipt)
    }

    // ------------------------------------------------------------- 第 1 级：完整 URL

    @Test
    fun `parses a full check-in URL`() {
        assertExtracts(
            "https://class.cau.edu.cn/casgeosig.php?ip=10.1.2.3&ipt=260914190149&t=1700000000&tt=c76d",
            "10.1.2.3",
            "260914190149",
        )
    }

    @Test
    fun `parses http as well as https`() {
        assertExtracts("http://class.cau.edu.cn/casgeosig.php?ip=10.1.2.3&ipt=abc", "10.1.2.3", "abc")
    }

    @Test
    fun `parses a URL whose query has extra params in any order`() {
        assertExtracts(
            "https://x.y/z?tt=c76d&ipt=abc&t=1&ip=10.1.2.3",
            "10.1.2.3",
            "abc",
        )
    }

    @Test
    fun `parses percent-encoded IPv6 in a full URL`() {
        assertExtracts("https://x.y/z?ip=2001%3Adb8%3A%3A1&ipt=abc", "2001:db8::1", "abc")
    }

    // ------------------------------------------------------------- 第 2 级：查询串

    @Test
    fun `parses a bare query string without any host`() {
        assertExtracts("ip=10.1.2.3&ipt=260914190149", "10.1.2.3", "260914190149")
    }

    @Test
    fun `parses a query string that still carries the leading question mark`() {
        assertExtracts("?ip=10.1.2.3&ipt=abc", "10.1.2.3", "abc")
    }

    @Test
    fun `treats plus as space and leaves trimming to validation`() {
        // `+` 解码成空格。注意 extractIpIpt **不做 trim**——网页版也不做
        // （index.html:386 直接返回原值），trim 发生在 validateSession 里。
        assertExtracts("ip=+10.1.2.3+&ipt=abc", " 10.1.2.3 ", "abc")

        // 走完整流程才会被 trim 干净
        assertEquals(Session("10.1.2.3", "abc"), Sign.parseSignUrl("ip=+10.1.2.3+&ipt=abc"))
    }

    @Test
    fun `takes the first value when a key repeats`() {
        // 对齐 JS URLSearchParams.get：重复键取第一个
        assertExtracts("ip=10.1.2.3&ip=9.9.9.9&ipt=abc&ipt=zzz", "10.1.2.3", "abc")
    }

    // ------------------------------------------- 有意分歧：第 2 级会剥掉 fragment

    @Test
    fun `strips the fragment before parsing a bare query string`() {
        // 网页版不剥，会得到 ipt="2#frag" 然后校验失败；我们剥掉后拿到干净的 ipt。
        assertExtracts("ip=10.1.2.3&ipt=abc#frag", "10.1.2.3", "abc")
    }

    @Test
    fun `full URL with fragment is handled by the URL parser`() {
        assertExtracts("https://x.y/z?ip=10.1.2.3&ipt=abc#frag", "10.1.2.3", "abc")
    }

    // ------------------------------------------------------------- 第 3 级：正则兜底

    @Test
    fun `falls back to regex when params are uppercase`() {
        // URLSearchParams.get 大小写敏感，所以前两级取不到；正则带 /i 能兜住
        assertExtracts("IP=10.1.2.3&IPT=abc", "10.1.2.3", "abc")
    }

    @Test
    fun `falls back to regex inside free text`() {
        assertExtracts("签到链接?ip=10.1.2.3&ipt=abc&t=1", "10.1.2.3", "abc")
    }

    // ------------------------------------------- 第 4 级：CAS 登录链接里的 service

    /**
     * 真实样本：签到页发现未登录，把用户踢到统一身份认证，
     * 原始地址被整个百分号编码塞进 service（`ip=` 变成 `ip%3D`）。
     */
    private val realCasUrl =
        "https://onecas.cau.edu.cn/tpass/login?service=https%3A%2F%2Fclass.cau.edu.cn" +
            "%2Fcasgeosig.php%3Fip%3D219.225.103.37%26ipt%3D260922155536" +
            "%26t%3D1790065298%26tt%3D08d5"

    @Test
    fun `parses a real CAS login URL by descending into service`() {
        assertExtracts(realCasUrl, "219.225.103.37", "260922155536")
    }

    @Test
    fun `real CAS URL yields a full valid session`() {
        assertEquals(
            Session("219.225.103.37", "260922155536"),
            Sign.parseSignUrl(realCasUrl),
        )
    }

    @Test
    fun `descends into service over plain http too`() {
        assertExtracts(
            "https://onecas.cau.edu.cn/tpass/login?service=http%3A%2F%2Fclass.cau.edu.cn" +
                "%2Fcasgeosig.php%3Fip%3D10.1.2.3%26ipt%3Dabc",
            "10.1.2.3",
            "abc",
        )
    }

    @Test
    fun `does not descend when service carries no session`() {
        // service 指向别处，里面没有 ip/ipt —— 不该误报
        assertExtractsNothing(
            "https://onecas.cau.edu.cn/tpass/login?service=https%3A%2F%2Fexample.com%2Fhome",
        )
    }

    @Test
    fun `descends only one level`() {
        // CAS 套 CAS。只下钻一层，不该一路钻到底。
        val inner = "https://class.cau.edu.cn/casgeosig.php?ip=10.1.2.3&ipt=abc"
        val encodedInner = java.net.URLEncoder.encode(inner, "UTF-8")
        val middleCas = "https://onecas.cau.edu.cn/tpass/login?service=$encodedInner"
        val outerCas = "https://onecas.cau.edu.cn/tpass/login?service=" +
            java.net.URLEncoder.encode(middleCas, "UTF-8")
        assertExtractsNothing(outerCas)
    }

    // ------------------------------------------------------------- 失败路径

    @Test
    fun `returns nothing for null empty or whitespace`() {
        assertExtractsNothing(null)
        assertExtractsNothing("")
        assertExtractsNothing("   ")
    }

    @Test
    fun `returns nothing when one of the two params is missing`() {
        assertExtractsNothing("ip=10.1.2.3")
        assertExtractsNothing("ipt=abc")
        assertExtractsNothing("https://x.y/z?ip=10.1.2.3&t=1&tt=c76d")
    }

    @Test
    fun `returns nothing for unrelated text`() {
        assertExtractsNothing("这是一段完全无关的文字")
        assertExtractsNothing("https://www.baidu.com")
    }

    @Test
    fun `fidelity quirk - a space before ip equals does not match`() {
        // 网页版就是这个行为（index.html:389 的正则要求 ip= 前面是行首或 ?&）。
        // 如实复刻，不偷偷"修好"——要不要放宽是产品决策。
        val (ip, _) = extract("随便文字 ip=10.1.2.3&ipt=abc")
        assertNull("空格紧邻 ip= 时前两级和正则都取不到", ip)
    }

    // ------------------------------------------------- parseSignUrl 端到端

    @Test
    fun `parseSignUrl returns a validated session`() {
        assertEquals(
            Session("10.1.2.3", "260914190149"),
            Sign.parseSignUrl("https://x.y/z?ip=10.1.2.3&ipt=260914190149&t=1&tt=abcd"),
        )
    }

    @Test
    fun `parseSignUrl rejects extracted values that fail validation`() {
        // 能抠出来，但 ip6 含非法字符 g
        assertNull(Sign.parseSignUrl("https://x.y/z?ip=1.2.3.4g&ipt=abc"))
        // ipt 含点
        assertNull(Sign.parseSignUrl("https://x.y/z?ip=1.2.3.4&ipt=ab.c"))
        // ip 超长
        assertNull(Sign.parseSignUrl("https://x.y/z?ip=${"1".repeat(46)}&ipt=abc"))
    }

    @Test
    fun `parseSignUrl survives malformed percent escapes`() {
        // JS 的 decodeURIComponent 会抛，网页版靠外层 catch；这里应安静地失败而不是崩溃
        assertNull(Sign.parseSignUrl("ip=1.2.3.4&ipt=%E0%A4%A"))
        assertNull(Sign.parseSignUrl("https://x.y/z?ip=%&ipt=%"))
    }
}
