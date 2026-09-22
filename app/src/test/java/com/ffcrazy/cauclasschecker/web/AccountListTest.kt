package com.ffcrazy.cauclasschecker.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountListTest {

    // ------------------------------------------------------- 同一账号后覆盖前

    @Test
    fun `logging in the same account again overwrites instead of duplicating`() {
        var list = emptyList<StoredAccount>()
        list = AccountList.upsert(list, "2023123456", 1000)
        list = AccountList.upsert(list, "2023123456", 2000)
        list = AccountList.upsert(list, "2023123456", 3000)

        assertEquals("反复登录同一账号只应留一条", 1, list.size)
        assertEquals("时间应刷新为最后一次", 3000, list.single().loginAt)
        assertEquals("2023123456", list.single().username)
    }

    @Test
    fun `logging in again moves the account back to the front`() {
        var list = emptyList<StoredAccount>()
        list = AccountList.upsert(list, "A", 1000)
        list = AccountList.upsert(list, "B", 2000)
        assertEquals(listOf("B", "A"), list.map { it.username })

        // A 重新登录，应回到最前
        list = AccountList.upsert(list, "A", 3000)
        assertEquals(listOf("A", "B"), list.map { it.username })
        assertEquals(2, list.size)
    }

    @Test
    fun `different accounts are all kept`() {
        var list = emptyList<StoredAccount>()
        list = AccountList.upsert(list, "A", 1000)
        list = AccountList.upsert(list, "B", 2000)
        list = AccountList.upsert(list, "C", 3000)
        assertEquals(listOf("C", "B", "A"), list.map { it.username })
    }

    @Test
    fun `usernames are matched exactly, not by prefix`() {
        var list = emptyList<StoredAccount>()
        list = AccountList.upsert(list, "2023123", 1000)
        list = AccountList.upsert(list, "20231234", 2000)
        list = AccountList.upsert(list, "202312345", 3000)
        assertEquals("长度不同的学号是不同账号", 3, list.size)
    }

    // ------------------------------------------------------------------ 删除

    @Test
    fun `remove drops only the matching account`() {
        val list = listOf(acc("A", 1), acc("B", 2), acc("C", 3))
        assertEquals(listOf("B", "C"), AccountList.remove(list, "A").map { it.username })
    }

    @Test
    fun `removing an unknown account is a no-op`() {
        val list = listOf(acc("A", 1))
        assertEquals(list, AccountList.remove(list, "ZZZ"))
    }

    // -------------------------------------------------------------- 序列化

    @Test
    fun `serialize and parse round trip`() {
        val list = listOf(acc("2023123456", 1758523200000), acc("2023999999", 1758520000000))
        assertEquals(list, AccountList.parse(AccountList.serialize(list)))
    }

    @Test
    fun `parse returns newest first regardless of stored order`() {
        val text = "A\t1000\nB\t3000\nC\t2000"
        assertEquals(listOf("B", "C", "A"), AccountList.parse(text).map { it.username })
    }

    @Test
    fun `parse tolerates empty and malformed input`() {
        assertTrue(AccountList.parse(null).isEmpty())
        assertTrue(AccountList.parse("").isEmpty())
        assertTrue(AccountList.parse("\n\n  \n").isEmpty())
        // 缺时间戳 / 缺用户名 / 时间戳不是数字 —— 都应被跳过而不是崩溃
        assertTrue(AccountList.parse("没有制表符").isEmpty())
        assertTrue(AccountList.parse("\t123").isEmpty())
        assertTrue(AccountList.parse("A\t不是数字").isEmpty())
        assertEquals(listOf("A"), AccountList.parse("A\t1\n坏数据\tx").map { it.username })
    }

    @Test
    fun `empty list serializes to empty string`() {
        assertEquals("", AccountList.serialize(emptyList()))
        assertTrue(AccountList.parse("").isEmpty())
    }

    // ------------------------------------------------------------ 有效性标记

    @Test
    fun `newly upserted account is valid`() {
        val list = AccountList.upsert(emptyList(), "A", 1000)
        assertTrue("刚登录成功应为有效", list.single().valid)
    }

    @Test
    fun `logging in as one account invalidates the others`() {
        var list = emptyList<StoredAccount>()
        list = AccountList.upsert(list, "A", 1000)
        list = AccountList.upsert(list, "B", 2000)

        // 服务端只有一条会话，B 登录成功意味着 A 那条被顶掉了
        list = AccountList.invalidateAllExcept(list, "B")

        assertEquals(2, list.size)
        assertTrue("B 应有效", list.first { it.username == "B" }.valid)
        assertTrue("A 应失效", !list.first { it.username == "A" }.valid)
    }

    @Test
    fun `invalidateAllExcept marks exactly one account valid`() {
        val list = listOf(acc("A", 1), acc("B", 2), acc("C", 3))
        val after = AccountList.invalidateAllExcept(list, "B")
        assertEquals(1, after.count { it.valid })
        assertEquals("B", after.first { it.valid }.username)
    }

    @Test
    fun `withValidity only touches the named account and keeps order`() {
        val list = listOf(acc("A", 3), acc("B", 2), acc("C", 1))
        val after = AccountList.withValidity(list, "B", false)

        assertEquals(listOf("A", "B", "C"), after.map { it.username })
        assertTrue(after.first { it.username == "A" }.valid)
        assertTrue("B 应被改成失效", !after.first { it.username == "B" }.valid)
        assertTrue(after.first { it.username == "C" }.valid)
    }

    @Test
    fun `withValidity on an unknown account changes nothing`() {
        val list = listOf(acc("A", 1))
        assertEquals(list, AccountList.withValidity(list, "ZZZ", false))
    }

    @Test
    fun `validity survives the serialize round trip`() {
        val list = listOf(acc("A", 2, valid = true), acc("B", 1, valid = false))
        assertEquals(list, AccountList.parse(AccountList.serialize(list)))
    }

    @Test
    fun `old two-field entries load as invalid rather than guessing`() {
        // 第三段是后加的。老数据没有有效性信息，宁可保守判失效 ——
        // 真实状态由 App 启动时的会话检查重新判定。
        val parsed = AccountList.parse("A\t1000\nB\t2000")
        assertEquals(2, parsed.size)
        assertTrue("老格式应默认失效", parsed.all { !it.valid })
    }

    @Test
    fun `malformed validity flag is treated as invalid`() {
        assertTrue(!AccountList.parse("A\t1\tx").single().valid)
        assertTrue(!AccountList.parse("A\t1\t").single().valid)
        assertTrue(!AccountList.parse("A\t1\ttrue").single().valid)
        assertTrue(AccountList.parse("A\t1\t1").single().valid)
    }

    private fun acc(name: String, at: Long, valid: Boolean = true) =
        StoredAccount(name, at, valid)
}
