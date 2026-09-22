package com.ffcrazy.cauclasschecker.domain

/**
 * 签到的两种模式。从**扫到的链接本身**分辨，不需要额外的开关。
 *
 * 两者的 URL 形状完全一样（`ip` / `ipt` / `t` / `tt` 四个参数），只有端点名不同 ——
 * 严格模式多了个 `pic`：`casgeo**pic**sig.php`。
 *
 * | | 普通 | 严格 |
 * |---|---|---|
 * | 第一步 | `casgeosig.php` | `casgeopicsig.php` |
 * | 第二步 | `casgeoreg.php` / `reg.php` | `casgeopicreg.php` |
 * | 提交字段 | `id, name, nouse, position, browserfp` | 同左 **+ `photo`** |
 *
 * 「严格」严在照片上：提交时必须再带一张 **base64 的 JPEG**（`photo` 字段，
 * 形如 `data:image/jpeg;base64,/9j/4AAQ…`）。走的是**普通表单字段**而不是文件上传，
 * 所以 Content-Type 仍是 `application/x-www-form-urlencoded`，不是 `multipart/form-data`。
 */
enum class SignMode {
    /** 只要 ip / ipt / t / tt。 */
    NORMAL,

    /** 严格模式：提交时额外要一张照片，见 [SignMode] 的说明。 */
    STRICT,
}
