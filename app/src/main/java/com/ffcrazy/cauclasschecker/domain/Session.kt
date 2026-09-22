package com.ffcrazy.cauclasschecker.domain

/**
 * 一次有效的签到会话。
 *
 * 只有 [ip] 和 [ipt] 是真正携带身份的字段；URL 里的 `t` / `tt` 一律丢弃，
 * 在渲染时按当前时间重算。这是整个工具的立身之本——旧码换个新时间戳就复活了。
 */
data class Session(
    val ip: String,
    val ipt: String,
)
