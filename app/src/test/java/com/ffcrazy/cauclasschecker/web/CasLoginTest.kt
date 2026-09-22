package com.ffcrazy.cauclasschecker.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CasLoginTest {

    /** 真实下发的 lt。 */
    private val lt = "LT-1538773-4sWCgfCMjQNe1ODfNsR9w7eWoe7nui-tpass"

    // ------------------------------------------------------------- 登录入口

    @Test
    fun `login url points service at the site root`() {
        assertEquals(
            "https://onecas.cau.edu.cn/tpass/login?service=https%3A%2F%2Fclass.cau.edu.cn%2F",
            CasLogin.loginUrl(),
        )
    }

    // --------------------------------------------------------- 隐藏域抓取

    @Test
    fun `parses hidden fields from a realistic login page`() {
        // 真实页面的属性顺序：lt 是 id 在前 name 在中 value 在后；
        // execution 只有 name 和 value，没有 id
        val html = """
            <form id="loginForm" action="/tpass/login?service=x" method="post">
              <input type="hidden" id="rsa" name="rsa"/>
              <input type="hidden" id="ul" name="ul"/>
              <input type="hidden" id="pl" name="pl"/>
              <input type="hidden" id="sl" name="sl"/>
              <input type="hidden" id="lt" name="lt" value="$lt" />
              <input type="hidden" name="execution" value="e1s1" />
              <input type="hidden" name="_eventId" value="submit" />
              <input id="un" type="text" value="" />
              <input id="pd" type="password" value="" />
            </form>
        """.trimIndent()

        val fields = CasLogin.parseHiddenFields(html)
        assertEquals(lt, fields["lt"])
        assertEquals("e1s1", fields["execution"])
        assertEquals("submit", fields["_eventId"])
        assertEquals("", fields["rsa"])
        // 可见输入框不该被当成隐藏域收进来
        assertTrue("un 不应出现", "un" !in fields)
        assertTrue("pd 不应出现", "pd" !in fields)
    }

    @Test
    fun `hidden field parsing tolerates attribute order quotes and case`() {
        // 属性顺序颠倒、单引号、大写标签名 —— 正则写窄了就抓不到
        val html = """
            <INPUT value='AAA' NAME="lt" TYPE='HIDDEN' id="lt">
            <input value="BBB" type="hidden" id="execution" name="execution">
        """.trimIndent()

        val fields = CasLogin.parseHiddenFields(html)
        assertEquals("AAA", fields["lt"])
        assertEquals("BBB", fields["execution"])
    }

    @Test
    fun `hidden field parsing falls back to id when name is absent`() {
        val fields = CasLogin.parseHiddenFields("""<input type="hidden" id="lt" value="X" />""")
        assertEquals("X", fields["lt"])
    }

    @Test
    fun `missing hidden fields yield an empty map rather than throwing`() {
        assertTrue(CasLogin.parseHiddenFields("").isEmpty())
        assertTrue(CasLogin.parseHiddenFields("<html><body>nothing</body></html>").isEmpty())
    }

    // ------------------------------------------------------------- 表单构造

    @Test
    fun `builds the exact form login6 js would send`() {
        val form = CasLogin.buildForm("user", "pass", lt, "e1s1")

        assertEquals("4", form["ul"])   // 用户名长度，不是用户名本身
        assertEquals("4", form["pl"])   // 密码长度
        assertEquals("0", form["sl"])   // 0 = 账号密码登录
        assertEquals(lt, form["lt"])
        assertEquals("e1s1", form["execution"])
        assertEquals("submit", form["_eventId"])

        // rsa 必须与 des.js 完全一致（该值由 node 跑原版 JS 得出）
        assertEquals(
            "EB6D16D13160C8DC0303D47B562E51A93F87C346C8672F7C04466811425E6D89" +
                "BE1DE02043D7F6CA0884384B23A1F04BE2C52791E3EEC872DBF812AC756F2FF3B" +
                "02253DE11FA951D3E902BCB32D6FFF4682B9B32E7FC96CD3644B6ACF047AE2A926" +
                "395FE891257539668FE464C1DBD45",
            form["rsa"],
        )

        // 用户名/密码明文绝不能出现在表单里
        assertTrue("表单不该含明文密码", form.values.none { it == "pass" })
        assertTrue("表单不该含明文用户名", form.values.none { it == "user" })
        assertEquals(
            "字段集合应固定为这六个",
            setOf("ul", "pl", "rsa", "sl", "lt", "execution", "_eventId"),
            form.keys,
        )
    }

    @Test
    fun `length fields count characters not bytes`() {
        // 中文用户名按字符数算长度，不是 UTF-8 字节数
        val form = CasLogin.buildForm("张三", "密码密码", lt, "e1s1")
        assertEquals("2", form["ul"])
        assertEquals("4", form["pl"])
    }

    // ------------------------------------------------------------- 错误提取

    /**
     * 真实的失败页面结构（已实测）：
     * `#errormsg` 有两个且**都是空的**，服务端的失败原因写在 `#errormsghide` 里。
     * 只读 `#errormsg` 的话永远拿不到原因 —— 这是踩过的坑。
     */
    @Test
    fun `reads the failure reason from errormsghide, not the always-empty errormsg`() {
        val realFailurePage = """
            <div class="row">
              <span class="login_box_title_notice script_red" style="display:none" id="errormsg"></span>
            </div>
            <p>
              <span id="errormsghide" class="login_box_title_notice script_red">您还剩20次机会！若密码连续输错60次，账号将被锁定19分钟。</span>
            </p>
            <span class="login_box_title_notice script_red" id="errormsg"></span>
        """.trimIndent()

        assertEquals(
            "您还剩20次机会！若密码连续输错60次，账号将被锁定19分钟。",
            CasLogin.parseErrorMessage(realFailurePage),
        )
    }

    @Test
    fun `a clean login page yields no error message`() {
        // 首次 GET 时 errormsghide 根本不存在，errormsg 是空的
        val cleanPage = """
            <span class="login_box_title_notice script_red" style="display:none" id="errormsg"></span>
            <span class="login_box_title_notice script_red" id="errormsg"></span>
        """.trimIndent()
        assertNull(CasLogin.parseErrorMessage(cleanPage))
    }

    @Test
    fun `extracts the error message from a failed login page`() {
        val html = """<div class="tip"><span id="errormsg">用户名或密码错误</span></div>"""
        assertEquals("用户名或密码错误", CasLogin.parseErrorMessage(html))
    }

    @Test
    fun `error extraction strips inner markup and collapses whitespace`() {
        val html = """<span id="errormsg">  账号   <b>已锁定</b>  ，请稍后再试 </span>"""
        assertEquals("账号 已锁定 ，请稍后再试", CasLogin.parseErrorMessage(html))
    }

    @Test
    fun `returns null when there is no error span or it is empty`() {
        assertNull(CasLogin.parseErrorMessage("<html></html>"))
        assertNull(CasLogin.parseErrorMessage("""<span id="errormsg"></span>"""))
        assertNull(CasLogin.parseErrorMessage("""<span id="errormsg">   </span>"""))
    }
}
