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

    /**
     * 线上真实页面的形状：**被注释掉的旧表单排在真表单前面**，而且指向旧的 `reg.php`。
     *
     * 这正是踩过的那个坑。不删注释就会先匹配到注释里那个，把请求发给旧端点 ——
     * 签到照样「成功」（身份取自会话，不取自提交体），但教师端后台的 GPS 列永远是空的。
     */
    private val pageWithCommentedLegacyForm = """
        <html><head><title> 登记姓名</title></head><body onload="initrun()">
        <!--	//<form  action="reg.php?ip=219.225.103.37&ipt=260922174709" method="POST" onsubmit="return checkForm(this)" >
        -->
            <div id="form1">
            <form action="https://class.cau.edu.cn/casgeoreg.php?ip=219.225.103.37&amp;ipt=260922174709&amp;pst=113&amp;pict=1790072747" method="POST" onsubmit="return checkForm(this)">
                <input id="p_cardid" type="text" name="id" value="2023000000000" readonly="">
                <input type="text" name="name" value="张三" readonly="">
                <input type="hidden" id="position" name="position" value="">
                <input type="hidden" id="browserfp" name="browserfp" value="">
        <!--
                <div class="row"><div class="input">
                    <span>手机</span><br>
                    <input id="p_tel" type="text" name="tel"/><br>
                </div></div>
        -->
            </form></div>
        </body></html>
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
    fun `注释里的旧表单不会被当成提交目标`() {
        // 回归用例：曾经直接拿正则扫全文，先撞上注释里那个 reg.php，
        // 于是每次签到都发给旧端点 —— 签到成功，但教师端后台 GPS 那一列一直是空的。
        val form = RegForm.parse(pageWithCommentedLegacyForm, pageUrl)!!
        assertTrue(
            "必须选中真表单 casgeoreg.php，而不是注释里那个 reg.php。实际：${form.action}",
            form.action.contains("/casgeoreg.php?"),
        )
        assertTrue("pst 要带上，实际：${form.action}", form.action.contains("pst=113"))
        assertTrue("pict 要带上，实际：${form.action}", form.action.contains("pict=1790072747"))
    }

    @Test
    fun `注释里的 input 不会进提交体`() {
        // 手机号那个 input 在注释里，浏览器根本不提交它 —— 我们提交了就和真实请求不一样
        val fields = RegForm.parse(pageWithCommentedLegacyForm, pageUrl)!!.fields
        assertFalse("tel 在注释里，不该出现：$fields", fields.containsKey("tel"))
        assertEquals(listOf("id", "name", "position", "browserfp"), fields.keys.toList())
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

    // ------------------------------------------------------------------ 提交体组装

    private fun pairsOf(body: okhttp3.FormBody): List<Pair<String, String>> =
        (0 until body.size).map { body.name(it) to body.value(it) }

    /** 页面上 position / browserfp 两个 input 的值就是空的，真值由页面脚本填。 */
    private val formFieldsInPageOrder = linkedMapOf(
        "id" to "2023000000000",
        "name" to "张三",
        "nouse" to "因签到需要，请允许获取您的位置",
        "position" to "",
        "browserfp" to "",
        "tel" to "",
    )

    @Test
    fun `每个参数名只出现一次`() {
        // 回归用例：曾经先把表单字段照抄一遍、再补一个自己的 position，
        // 提交体里就出现了两个同名参数（空串在前、坐标在后），服务端取哪个全凭运气。
        // 表现就是「明明拿到了坐标，教师端后台那一列却是空的」。
        val pairs = pairsOf(buildCheckInBody(formFieldsInPageOrder, "116.353782,40.003695"))

        assertEquals("参数名不能重复：$pairs", pairs.size, pairs.map { it.first }.toSet().size)
        assertEquals(
            "提交体里必须带的是真实坐标，不是空串",
            "116.353782,40.003695",
            pairs.single { it.first == "position" }.second,
        )
    }

    @Test
    fun `拿不到定位时退回非空占位符`() {
        // 留空会被页面脚本的拦截逻辑挡住，所以兜底值也必须非空
        val pairs = pairsOf(buildCheckInBody(formFieldsInPageOrder, ""))
        assertTrue(pairs.single { it.first == "position" }.second.isNotEmpty())
    }

    @Test
    fun `页面上没有这两个字段时也要补上`() {
        val pairs = pairsOf(buildCheckInBody(mapOf("id" to "2023000000000"), "1.0,2.0"))
        assertEquals("1.0,2.0", pairs.single { it.first == "position" }.second)
        assertTrue("browserfp 也要补", pairs.any { it.first == "browserfp" })
    }

    @Test
    fun `其它字段原样保留且顺序不变`() {
        val pairs = pairsOf(buildCheckInBody(formFieldsInPageOrder, "1.0,2.0"))
        assertEquals(
            listOf("id", "name", "nouse", "position", "browserfp", "tel"),
            pairs.map { it.first },
        )
        assertEquals("张三", pairs[1].second)
    }

    // ------------------------------------------------------------------ 严格模式的照片

    private val photo = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD"

    /** 严格模式的表单：比普通模式多一个 `photo`。 */
    private val strictFormFields = linkedMapOf(
        "id" to "2023000000000",
        "name" to "张三",
        "nouse" to "因签到需要，请允许获取您的位置",
        "position" to "",
        "browserfp" to "",
        "photo" to "",
    )

    @Test
    fun `严格模式会把照片发出去`() {
        val pairs = pairsOf(buildCheckInBody(strictFormFields, "1.0,2.0", photo))
        assertEquals(photo, pairs.single { it.first == "photo" }.second)
    }

    @Test
    fun `普通模式不会凭空多带一张照片`() {
        // 「页面上有没有 photo 字段」正是判断严格模式的依据。
        // 普通模式硬塞一张过去，就是在给服务端发它没要的东西。
        val pairs = pairsOf(buildCheckInBody(formFieldsInPageOrder, "1.0,2.0", photo))
        assertFalse("普通模式的表单里没有 photo，就不该出现：$pairs", pairs.any { it.first == "photo" })
    }

    @Test
    fun `照片不会破坏「每个参数名只出现一次」`() {
        val pairs = pairsOf(buildCheckInBody(strictFormFields, "1.0,2.0", photo))
        assertEquals("参数名不能重复：$pairs", pairs.size, pairs.map { it.first }.toSet().size)
    }

    @Test
    fun `严格模式不传照片时提交体里是空值`() {
        // 调用方（CasClient）会在发出去之前就拦下这种情况并给出提示，
        // 这里只保证拼装本身不会崩
        val pairs = pairsOf(buildCheckInBody(strictFormFields, "1.0,2.0", ""))
        assertEquals("", pairs.single { it.first == "photo" }.second)
    }
}
