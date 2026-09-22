package com.ffcrazy.cauclasschecker.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表单解析与结果判定。
 *
 * 下面的页面片段和响应文案都是照着 2026-09-22 线上实测到的真实内容写的，
 * 只把学号 / 姓名替换成了假值。
 */
class RegFormTest {

    /** 取到表单页时**实际**的地址（跟随跳转之后的），相对 action 靠它补全。 */
    private val pageUrl =
        "https://class.cau.edu.cn/casgeosig.php?ip=219.225.103.37&ipt=260922174709&t=1790073328&tt=5879"

    /** 实测形态之一：相对 action。线上拿到的就是这一个。 */
    private val relativeActionPage = """
        <html><head><title> 登记姓名</title>
        <style type="text/css"> #form1 { margin-top:20px; } .row { height:50px; } </style>
        </head><body onload="initrun()">
        <div id="form1">
        <form action="reg.php?ip=219.225.103.37&ipt=260922174709" method="POST" onsubmit="return checkForm(this)">
            <input id="p_cardid" type="text" name="id" value="2023000000000" readonly="">
            <input type="text" name="name" value="张三" readonly="">
            <input type="text" name="nouse" value="因签到需要，请允许获取您的位置" readonly="">
            <input type="hidden" id="position" name="position" value="">
            <input type="hidden" id="browserfp" name="browserfp" value="">
            <input id="p_tel" type="text" name="tel"/>
            <input type="submit" name="go" value="提交信息">
            <button type="submit" id="submitinitrun">点我后才能签到</button>
        </form></div></body></html>
    """.trimIndent()

    /** 实测形态之二：绝对 action，且 `&` 被 HTML 转义过。 */
    private val absoluteActionPage = """
        <html><body>
        <form action="https://class.cau.edu.cn/casgeoreg.php?ip=219.225.103.37&amp;ipt=260922174709&amp;pst=113&amp;pict=1790072747" method="POST">
            <input type="text" name="id" value="2023000000000" readonly="">
        </form></body></html>
    """.trimIndent()

    /** 没有表单的错误页 —— 服务端在校验不通过时返回的就是这个。 */
    private val errorPage = """
        <html><head><title> 登记姓名</title>
        <style type="text/css"> *{margin:0;} </style></head>
        <body onload="initrun()">
        1790072703, 如果您在教室，请重新扫描签到的二维码，如果您扫描的是转发的图片， 那是不对的，有困难应该和老师说明原因。
        </body></html>
    """.trimIndent()

    // ------------------------------------------------------------------ action 解析

    @Test
    fun `相对 action 会补成绝对地址`() {
        val form = RegForm.parse(relativeActionPage, pageUrl)!!
        assertEquals(
            "reg.php 是相对路径，必须解析到站点根下",
            "https://class.cau.edu.cn/reg.php?ip=219.225.103.37&ipt=260922174709",
            form.action,
        )
    }

    @Test
    fun `绝对 action 会做 HTML 反转义`() {
        val form = RegForm.parse(absoluteActionPage, pageUrl)!!
        assertEquals(
            "pst / pict 要原样保留，不去解释含义",
            "https://class.cau.edu.cn/casgeoreg.php?ip=219.225.103.37&ipt=260922174709&pst=113&pict=1790072747",
            form.action,
        )
    }

    @Test
    fun `端点形态不固定，绝不能写死`() {
        // 同一个 ipt，两次实测拿到的 action 一个是 reg.php、一个是 casgeoreg.php
        assertTrue(RegForm.parse(relativeActionPage, pageUrl)!!.action.contains("/reg.php?"))
        assertTrue(RegForm.parse(absoluteActionPage, pageUrl)!!.action.contains("/casgeoreg.php?"))
    }

    // ------------------------------------------------------------------ 字段解析

    @Test
    fun `表单字段全部取出来`() {
        val f = RegForm.parse(relativeActionPage, pageUrl)!!.fields
        assertEquals("2023000000000", f["id"])
        assertEquals("张三", f["name"])
        assertEquals("因签到需要，请允许获取您的位置", f["nouse"])
        assertEquals("没有 value 属性时记为空串", "", f["tel"])
        assertEquals("", f["position"])
        assertEquals("", f["browserfp"])
    }

    @Test
    fun `提交按钮不会被当成字段`() {
        val f = RegForm.parse(relativeActionPage, pageUrl)!!.fields
        assertFalse("type=submit 的 input 不该进提交体", f.containsKey("go"))
        assertFalse(f.containsKey("submitinitrun"))
    }

    @Test
    fun `字段顺序按页面出现顺序`() {
        val keys = RegForm.parse(relativeActionPage, pageUrl)!!.fields.keys.toList()
        assertEquals(listOf("id", "name", "nouse", "position", "browserfp", "tel"), keys)
    }

    @Test
    fun `没有表单就返回 null`() {
        assertNull(RegForm.parse(errorPage, pageUrl))
        assertNull(RegForm.parse("", pageUrl))
    }

    // ------------------------------------------------------------------ 结果判定

    @Test
    fun `签到成功页判为 Success`() {
        val text = "签到结果 学号:2023000000000 姓名: 张三 签到成功 课堂:219.225.103.37:260922174709 时间: 2026-09-22 18:37:36"
        assertTrue(classifyCheckIn(text) is CasClient.CheckInResult.Success)
    }

    @Test
    fun `已签到优先于签到成功`() {
        // 重复签到时服务端很可能两句话一起说，那次其实是重复签到
        val text = "签到结果 学号:2023000000000 姓名: 张三 您已签到成功"
        assertTrue(classifyCheckIn(text) is CasClient.CheckInResult.AlreadyDone)
    }

    @Test
    fun `重新扫描判为 Rejected`() {
        val text = "1790072703, 如果您在教室，请重新扫描签到的二维码，如果您扫描的是转发的图片， 那是不对的，有困难应该和老师说明原因。"
        assertTrue(classifyCheckIn(text) is CasClient.CheckInResult.Rejected)
    }

    @Test
    fun `LastVisit 超时不该把账号标成失效`() {
        val r = classifyCheckIn("LastVisit timeout! Please Scan the QRcode again")
        assertTrue(r is CasClient.CheckInResult.Rejected)
        assertFalse("超时是这一步太慢，不是登录态没了", r is CasClient.CheckInResult.LoginExpired)
    }

    @Test
    fun `缺 t 参数判为 Rejected`() {
        assertTrue(classifyCheckIn("GET[t] not set! Please Scan the QR code") is CasClient.CheckInResult.Rejected)
    }

    @Test
    fun `认不出来的响应归 Unknown 并保留原文`() {
        val r = classifyCheckIn("服务器今天心情不好")
        assertTrue(r is CasClient.CheckInResult.Unknown)
        assertEquals("服务器今天心情不好", r.detail)
    }

    @Test
    fun `空响应归 Unknown`() {
        assertTrue(classifyCheckIn("   ") is CasClient.CheckInResult.Unknown)
    }

    // ------------------------------------------------------------------ 去标签

    @Test
    fun `style 正文不会挤掉真正的内容`() {
        // 签到成功页前面是一大段 CSS；只剥标签的话「签到成功」会被截断挡在外面
        val css = "body{margin:0;padding:0;font-family:sans-serif}" + "x".repeat(300)
        val html = "<html><head><style>$css</style></head><body><h1>签到成功</h1></body></html>"
        val text = plainText(html)
        assertTrue("截断之后仍要能看见「签到成功」，实际是：$text", text.contains("签到成功"))
        assertFalse(text.contains("font-family"))
    }

    @Test
    fun `script 与注释都会被去掉`() {
        val html = "<body><script>var x=1;</script><!-- 广告 -->签到成功</body>"
        assertEquals("签到成功", plainText(html))
    }
}
