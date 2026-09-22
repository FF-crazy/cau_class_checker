package com.ffcrazy.cauclasschecker.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountCodecTest {

    private fun acc(
        name: String,
        at: Long,
        valid: Boolean = true,
        cookies: List<StoredCookie> = emptyList(),
    ) = AccountRecord(name, at, valid, cookies)

    @Test
    fun `round trips accounts with cookies`() {
        val list = listOf(
            acc(
                "2023000000000", 1758523200000, valid = true,
                cookies = listOf(
                    StoredCookie("https://onecas.cau.edu.cn/tpass", "CASTGC=TGT-1; path=/tpass; domain=onecas.cau.edu.cn"),
                    StoredCookie("https://class.cau.edu.cn/", "PHPSESSID=abc; path=/; domain=class.cau.edu.cn"),
                ),
            ),
            acc("2023999999", 1758520000000, valid = false),
        )
        assertEquals(list, AccountCodec.decode(AccountCodec.encode(list)))
    }

    @Test
    fun `keeps each account's cookies separate`() {
        // 多账号的核心：Cookie 必须挂到各自账号名下，不能串
        val list = listOf(
            acc("A", 2, cookies = listOf(StoredCookie("https://class.cau.edu.cn/", "PHPSESSID=aaa; path=/"))),
            acc("B", 1, cookies = listOf(StoredCookie("https://class.cau.edu.cn/", "PHPSESSID=bbb; path=/"))),
        )
        val back = AccountCodec.decode(AccountCodec.encode(list))
        assertEquals("PHPSESSID=aaa; path=/", back.first { it.username == "A" }.cookies.single().value)
        assertEquals("PHPSESSID=bbb; path=/", back.first { it.username == "B" }.cookies.single().value)
    }

    @Test
    fun `cookie values keep their spaces`() {
        // Cookie 串里有空格，是最后一个字段，不能被切掉
        val cookie = "CASTGC=TGT-123; expires=Fri, 01 Jan 2027 00:00:00 GMT; path=/tpass"
        val list = listOf(acc("A", 1, cookies = listOf(StoredCookie("https://onecas.cau.edu.cn/tpass", cookie))))
        assertEquals(cookie, AccountCodec.decode(AccountCodec.encode(list)).single().cookies.single().value)
    }

    @Test
    fun `output is sorted newest first`() {
        val list = listOf(acc("old", 1000), acc("new", 3000), acc("mid", 2000))
        assertEquals(
            listOf("new", "mid", "old"),
            AccountCodec.decode(AccountCodec.encode(list)).map { it.username },
        )
    }

    @Test
    fun `validity flag round trips both ways`() {
        val list = listOf(acc("A", 2, valid = true), acc("B", 1, valid = false))
        val back = AccountCodec.decode(AccountCodec.encode(list))
        assertTrue(back.first { it.username == "A" }.valid)
        assertTrue(!back.first { it.username == "B" }.valid)
    }

    @Test
    fun `empty input and wrong version yield empty list`() {
        assertTrue(AccountCodec.decode("").isEmpty())
        assertTrue(AccountCodec.decode("V2\nA 1 2 1").isEmpty())
        assertTrue(AccountCodec.decode("随便什么东西").isEmpty())
        assertEquals("V1\n", AccountCodec.encode(emptyList()))
    }

    @Test
    fun `malformed lines are skipped rather than fatal`() {
        // 半损坏的文件应该尽量救回能用的账号，而不是整个清单作废
        val text = """
            V1
            A good 1000 1
            A bad-not-a-number 1
            A tooshort
            C good https://class.cau.edu.cn/ PHPSESSID=x; path=/
            C 不存在的账号 https://class.cau.edu.cn/ PHPSESSID=y; path=/
            C good
            X 未知类型 1 2
        """.trimIndent()

        val back = AccountCodec.decode(text)
        assertEquals(1, back.size)
        assertEquals("good", back.single().username)
        // 只保留能对应上账号的那条 Cookie
        assertEquals(1, back.single().cookies.size)
    }

    @Test
    fun `an account with no cookies still round trips`() {
        val list = listOf(acc("A", 1, cookies = emptyList()))
        val back = AccountCodec.decode(AccountCodec.encode(list)).single()
        assertTrue(back.cookies.isEmpty())
    }
}
