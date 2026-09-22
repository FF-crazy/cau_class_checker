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
    /**
     * 这场签到是哪种模式，由扫到的链接决定（见 [SignMode]）。
     *
     * 默认普通模式，是为了兼容**不带端点信息**的输入：页面 URL 上的
     * `?ip=…&ipt=…` 分享链接、手输的裸查询串都无从判断模式。
     * 那种输入本来也只有一个来源 —— 我们自己分享出去的普通模式链接。
     */
    val mode: SignMode = SignMode.NORMAL,
)
