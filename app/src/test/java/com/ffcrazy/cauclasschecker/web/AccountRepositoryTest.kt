package com.ffcrazy.cauclasschecker.web

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内存版存储，替代 Keystore 那份（那个是 Android 专有的，JVM 上跑不了）。 */
private class FakeStorage(var text: String? = null) : AccountStorage {
    var writes = 0
    override fun readText(): String? = text
    override fun writeText(text: String) {
        this.text = text
        writes++
    }
}

class AccountRepositoryTest {

    private val aCookies = listOf(
        StoredCookie("https://class.cau.edu.cn/", "PHPSESSID=aaa; path=/; domain=class.cau.edu.cn"),
    )
    private val bCookies = listOf(
        StoredCookie("https://class.cau.edu.cn/", "PHPSESSID=bbb; path=/; domain=class.cau.edu.cn"),
    )

    private fun repo(storage: AccountStorage = FakeStorage()) = AccountRepository(storage)

    // ---------------------------------------------------------- 多账号互不干扰

    @Test
    fun `logging in a second account does not touch the first`() {
        val r = repo()
        r.saveSession("A", aCookies, 1000)
        r.saveSession("B", bCookies, 2000)

        assertEquals(2, r.all().size)
        assertEquals("PHPSESSID=aaa; path=/; domain=class.cau.edu.cn", r.find("A")!!.cookies.single().value)
        assertEquals("PHPSESSID=bbb; path=/; domain=class.cau.edu.cn", r.find("B")!!.cookies.single().value)
        assertTrue("两个账号都该是有效的", r.all().all { it.valid })
    }

    @Test
    fun `each account gets its own jar`() {
        val r = repo()
        r.saveSession("A", aCookies, 1000)
        r.saveSession("B", bCookies, 2000)

        val url = "https://class.cau.edu.cn/".toHttpUrl()
        assertEquals(
            "PHPSESSID=aaa",
            r.jarFor("A").loadForRequest(url).single().let { "${it.name}=${it.value}" },
        )
        assertEquals(
            "PHPSESSID=bbb",
            r.jarFor("B").loadForRequest(url).single().let { "${it.name}=${it.value}" },
        )
    }

    @Test
    fun `re-logging the same account overwrites only itself`() {
        val r = repo()
        r.saveSession("A", aCookies, 1000)
        r.saveSession("B", bCookies, 2000)
        r.saveSession("A", listOf(StoredCookie("https://class.cau.edu.cn/", "PHPSESSID=new; path=/; domain=class.cau.edu.cn")), 3000)

        assertEquals("后登录的同一账号应覆盖前一条", 2, r.all().size)
        assertTrue(r.find("A")!!.cookies.single().value.contains("PHPSESSID=new"))
        assertEquals("A 应该被提到最前", "A", r.all().first().username)
    }

    // ------------------------------------------------------------------ 有效性

    @Test
    fun `newly saved session is valid`() {
        val r = repo()
        r.saveSession("A", aCookies, 1000)
        assertTrue(r.find("A")!!.valid)
    }

    @Test
    fun `setValidity only affects the named account`() {
        val r = repo()
        r.saveSession("A", aCookies, 1000)
        r.saveSession("B", bCookies, 2000)

        r.setValidity("A", false)

        assertTrue("A 应失效", !r.find("A")!!.valid)
        assertTrue("B 必须不受影响 —— 每个账号独立验活", r.find("B")!!.valid)
    }

    // ------------------------------------------------------------------ 增删查

    @Test
    fun `remove drops only that account`() {
        val r = repo()
        r.saveSession("A", aCookies, 1000)
        r.saveSession("B", bCookies, 2000)

        r.remove("A")

        assertNull(r.find("A"))
        assertTrue("B 不该受影响", r.find("B") != null)
    }

    @Test
    fun `missing account returns null and empty cookies`() {
        val r = repo()
        assertNull(r.find("无此人"))
        assertTrue(r.cookiesOf("无此人").isEmpty())
        assertTrue(r.jarFor("无此人").isEmpty())
    }

    // ------------------------------------------------------------------ 持久化

    @Test
    fun `state survives a restart via storage`() {
        val storage = FakeStorage()
        repo(storage).saveSession("A", aCookies, 1000)

        // 模拟 App 重启：新建仓库读同一份存储
        val reopened = AccountRepository(storage)
        assertEquals(1, reopened.all().size)
        assertEquals("A", reopened.all().single().username)
        assertEquals(aCookies.single().value, reopened.all().single().cookies.single().value)
    }

    @Test
    fun `every mutation writes through to storage`() {
        val storage = FakeStorage()
        val r = AccountRepository(storage)

        r.saveSession("A", aCookies, 1000)
        r.setValidity("A", false)
        r.remove("A")

        assertEquals("三次改动应各写一次", 3, storage.writes)
        assertTrue(AccountRepository(storage).all().isEmpty())
    }

    @Test
    fun `starts empty when storage is empty or corrupt`() {
        assertTrue(repo(FakeStorage(null)).all().isEmpty())
        assertTrue(repo(FakeStorage("乱码")).all().isEmpty())
        assertTrue(repo(FakeStorage("V2\nA 1 2 1")).all().isEmpty())
    }
}
