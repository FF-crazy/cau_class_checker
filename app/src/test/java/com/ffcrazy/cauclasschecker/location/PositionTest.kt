package com.ffcrazy.cauclasschecker.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标格式化。
 *
 * 这里只测拼串顺序，不碰 [android.location.Location]（那个在 JVM 单测里跑不了）。
 */
class PositionTest {

    @Test
    fun `坐标是经度在前`() {
        // 中国农业大学东校区：116.353782E, 40.003695N
        // 教师端后台存下来的正是这个样子。
        val s = Position.format(116.353782, 40.003695)
        assertEquals("116.353782,40.003695", s)
    }

    @Test
    fun `写反了会被这个用例抓住`() {
        // 光看字符串 "116.35,40.00" 是看不出对错的，所以对着地理范围断言一次：
        // 反过来的话第一段会是 40（非洲以东的大西洋），第二段会是 116（超出纬度上限）
        val (lng, lat) = Position.format(116.353782, 40.003695)
            .split(",").map(String::toDouble)

        assertTrue("第一段应该是经度，落在中国范围 73~135，实际 $lng", lng in 73.0..135.0)
        assertTrue("第二段应该是纬度，落在中国范围 3~54，实际 $lat", lat in 3.0..54.0)
    }
}
