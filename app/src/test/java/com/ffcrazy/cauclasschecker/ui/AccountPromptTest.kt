package com.ffcrazy.cauclasschecker.ui

import com.ffcrazy.cauclasschecker.web.AccountRecord
import com.ffcrazy.cauclasschecker.web.StoredCookie
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「有没有能拿去签到的账号」。
 *
 * 这里就是几条布尔分支的组合，但判错了用户会看到完全错误的提示，
 * 而且不值得为它开一次真机验证 —— 所以抽成纯函数测掉。
 */
class AccountPromptTest {

    private val someCookie = listOf(
        StoredCookie("https://class.cau.edu.cn/", "PHPSESSID=x; path=/; domain=class.cau.edu.cn"),
    )

    private fun account(
        valid: Boolean = true,
        cookies: List<StoredCookie> = someCookie,
    ) = AccountRecord("2023000000000", 1000L, valid, cookies)

    @Test
    fun `一个账号都没有时要提醒`() {
        assertFalse(hasUsableAccount(emptyList()))
    }

    @Test
    fun `有能用的账号就不打扰`() {
        assertTrue(hasUsableAccount(listOf(account())))
    }

    @Test
    fun `账号都在但全失效了，同样要提醒`() {
        // 最容易漏的一种：列表里有东西，用户以为没事，
        // 点签到却一个都签不上 —— 那才是最需要提醒的时候
        assertFalse(hasUsableAccount(listOf(account(valid = false), account(valid = false))))
    }

    @Test
    fun `只要有一个能用就不提醒`() {
        assertTrue(hasUsableAccount(listOf(account(valid = false), account())))
    }

    @Test
    fun `被标成有效但没有 Cookie 的不算数`() {
        // valid 只是个缓存的标记。空 Cookie 的记录点下去照样签不上，
        // 提示用户「你有账号」等于骗他
        assertFalse(hasUsableAccount(listOf(account(valid = true, cookies = emptyList()))))
    }

    @Test
    fun `标失效但有 Cookie 的也不算数`() {
        // 反过来同理：Cookie 还在，但已经明确验出失效了
        assertFalse(hasUsableAccount(listOf(account(valid = false))))
    }
}
