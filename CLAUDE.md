# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

CAU（中国农业大学）易签到课堂签到的安卓客户端。原生 Kotlin + Compose 重写，**不打包浏览器内核**，**零 Google Play 服务依赖**（目标用户在境内）。

三件事：**扫码拿到 ip/ipt → 用保存的账号无头签到 → 生成带当前时间的二维码**。

同项目族的 `cau-class-qr-minimal`（静态网页）是**规格说明书**，不是运行时依赖 ——
`SALT` / `BASE` / 校验正则都从那里来，但不要指望它在编译期存在。

**协议知识是硬换来的**：下面带 ⚠️ 的每一条都对应一次线上踩坑，改动前请先读。

## Commands

```bash
./gradlew testDebugUnitTest                            # 单元测试（唯一的自动化防线）
./gradlew testDebugUnitTest --tests '*RegFormTest*'    # 跑单个测试类
./gradlew assembleDebug                                # 出 APK
```

- 产物 `app/build/outputs/apk/debug/app-debug.apk`，发布副本在 `~/apk/cau-checkin-v1.0-debug.apk`。
- **机器上没有模拟器**（无 system-images、无 AVD）。相机、定位、真机签到**只能真机验**，
  所以 JVM 单测是唯一的自动化防线 —— 凡是能抽成纯函数的一律抽出来测。
- 没有 lint / ktlint 步骤。编辑文件即产物。

## 技术栈

| 层 | 选型 | 版本 |
|---|---|---|
| 语言 / UI | Kotlin + Jetpack Compose (Material 3) | Kotlin 2.2.10 / Compose BOM 2026.02.01 |
| 构建 | AGP 9.4.1 / Gradle 9.6 / JDK 25 | `minSdk 29` / `targetSdk 37` |
| 扫码 + 出码 | **ZXing core**（一个依赖两用） | 3.5.4 |
| 相机 | CameraX | 1.6.2（锁死：修了 JDK 25 上解析 JSpecify 崩溃的问题） |
| 网络 | OkHttp | 5.5.0 |

**不要随意加依赖。** 加密用 Keystore、定位用系统 `LocationManager`、权限申请用已有的
`activity-compose` —— 这三处本来都能靠引库省事，但都忍住了。

## 签到协议（本项目的核心）

### 时间签名

```
SALT = "caulvchunli"
BASE = "https://class.cau.edu.cn/casgeosig.php"

t  = 当前 Unix 秒
tt = lowerhex(md5(t.toString() + ip + SALT))[0:4]      // 无分隔符，小写
URL = BASE?ip=…&ipt=…&t=…&tt=…                          // 参数顺序固定
```

**输入里的 t/tt 一律丢弃、按当前时间重算** —— 这是整个工具的立足点。
`domain/Sign.kt` 是这条契约的唯一实现，零 Android 依赖，必须有测试覆盖。

### ip / ipt 是什么

- `ip`：教室网络的 IP。`ipt` 形如 `260922185518`，解出来是 **2026-09-22 18:55:18** ——
  即**这一场签到的开始时刻**。原样透传即可，**不要去解析或重算它**。
- 合法的 `ip`/`ipt` 由 `validateSession` 把关：先判空 → trim → 正则 → 长度。
  `ip: ^[\d.a-fA-F:]+$` ≤45；`ipt: ^[0-9A-Za-z_-]+$` ≤40。

### 签到是两步，不是一步

```
① GET  casgeosig.php?ip&ipt&t&tt        带该账号**自己的** Cookie
   ├─ 未登录       → 302 到 onecas.cau.edu.cn/tpass/login
   ├─ t 取值不合法 → 200 + "…请重新扫描签到的二维码…"
   └─ 通过         → 200 + 一张 <form>，同时服务端 $_SESSION['LastVisit'] 被种下

② POST 表单 action（从页面里解析，见 RegForm）
   ├─ 没有 LastVisit → "LastVisit timeout! Please Scan the QRcode again"
   └─ 有            → "签到结果 学号:… 姓名:… 签到成功 课堂:<ip>:<ipt> 时间: …"
```

- **只发第一步不会签上任何人** —— 它只取回一张表单。
- 两步**必须共用同一个 Cookie 罐**：`LastVisit` 是跟着会话走的。
- 第二步要在 **120 秒**内完成，那正是服务端给 `LastVisit` 的有效期。
- 登录前的校验顺序是**反的**：`tt` / `ip` / `ipt` 都等**登录之后**才验。
  所以未登录时无论 `tt` 对错，回应都是同一个 302。

### ⚠️ 页面里藏着一份被注释掉的旧表单

```html
<!--	//<form action="reg.php?ip=…&ipt=…" …>
-->                                                  ← 排前面，指向**旧**端点
	<form action="…/casgeoreg.php?…&pst=…&pict=…">    ← 真表单，排后面
```

拿正则扫全文会先撞上注释里那个。后果**极其隐蔽**：

- 签到照样回「签到成功」——因为身份取自**会话**，不取自提交体；
- 但旧端点 `reg.php` **不记 GPS**，教师端后台那一列永远是空的。

**`RegForm.parse` 必须先整块删掉注释 / `<script>` / `<style>` 再找表单与 input。**
同一个坑还会污染验证手段：随手写的浏览器探针若用同样天真的正则，会"证明"出一个错误结论。

`tel` 那个 input 也在注释里，浏览器根本不提交它 —— 删注释后我们的提交体才和真实浏览器一致。

### ⚠️ 提交体里每个参数名只能出现一次

页面上 `position` / `browserfp` 两个 input 的值是**空的**（真值由页面脚本在提交前填）。
先把表单字段照抄一遍、再补一个自己的值，提交体里就会出现**两个同名参数** ——
服务端取哪个全凭它的解析习惯。`buildCheckInBody` 的就地替换和它的回归用例钉的就是这个。

### 服务端响应文案（全部实测，判定见 `classifyCheckIn`）

| 场景 | 文案 |
|---|---|
| 参数不全 | `GET[t] not set! Please Scan the QR code` |
| 签名不通过 | `<服务端时间>, 如果您在教室，请重新扫描签到的二维码，如果您扫描的是转发的图片， 那是不对的，有困难应该和老师说明原因。` |
| 少了第一步 | `LastVisit timeout! Please Scan the QRcode again` |
| 成功 | `签到结果 学号:… 姓名:… 签到成功 课堂:<ip>:<ipt> 时间: …` |

两个判定上的坑：

1. **「已签到」必须排在「签到成功」前面** —— 重复签到时服务端很可能两句话一起说。
2. **错误页与成功页共用同一个标题**「登记姓名」，`<style>` 也一样。只能看正文。
   顺带：`plainText()` 必须先整块剥掉 `<script>`/`<style>`，否则成功页前面那一大段 CSS
   会把「签到成功」挤出截断长度，永远判成「未识别」。

`LastVisit timeout` 归 `Rejected` 而**不是** `LoginExpired` —— 它说的是这一步太慢，
与账号登录态无关，不能因此把账号标成失效。

### 定位

- 表单里 `position` 的格式是 `"经度,纬度"` —— **经度在前**（写反了界面上看不出来，
  后台那一列却成了地球上另一个地方，所以 `Position.format` 单独抽了纯函数并配了用例）。
- 服务端**会解析** `position`，但解析失败**不阻止签到**，只是存成空。
  所以「不传定位」不会失败，只会让那几条签到在后台一眼可见地与众不同。
- 取的是 `LocationManager` 的**真实坐标**，**不要写死一个教室坐标** —— 那是伪造位置证据。
- 拿不到定位**不阻止签到**，但必须把原因说出来（`Position.Fix.Unavailable.reason`）：
  用户看到后台是空的，得知道该去打开定位开关还是改权限。
- `browserfp` 服务端**不校验**，填 32 个 `0` 即可。
  真实值是 FingerprintJS 的 visitorId，服务端会把它回种成 `bfp` cookie。
  这是**已知偏差**：目前不影响签到，但出怪事时它是第一个该查的对象。

### CAS 无头登录（`web/CasLogin.kt` + `web/TpassCrypto.kt`）

```
1. GET  /tpass/login?service=<站点根>  → 抓 lt 与 execution
2. rsa = strEnc(用户名 + 密码 + lt, "1","2","3")
3. POST 同上，表单见 CasLogin.buildForm
4. OkHttp 跟随 302，class.cau.edu.cn 种下一份新的 PHPSESSID
```

⚠️ **`strEnc` 是一套非标准 DES**：

- 与标准 DES 唯一的差别在 **PC-1 的 D 半段顺序**，其余（PC-2、S 盒、P、IP、E、移位表）全标准。
- `strEnc` 对每组依次用三个 key 各做一次 **enc-enc-enc**，**不是**标准 3DES 的 EDE。
- `strToBt` 把 4 个字符按 16 位大端打包进 64 位，不足补零。

⚠️ **登录失败的原因不在 `#errormsg`**（那个元素在成功页和失败页上都始终为空），
在 **`#errormsghide`** —— 它只在失败页出现。

⚠️ **每个账号一个全新的空 Cookie 罐**。残留的 `CASTGC` 会让 CAS 直接放行、
不显示登录表单，于是解析不出 `lt`/`execution` —— 这正是历史上那个「登录页结构不认识了」的 bug。

## 架构

```
app/src/main/java/com/ffcrazy/cauclasschecker/
├── MainActivity.kt            单 Activity，三个 Tab（签到 / 账号管理 / 关于）
├── AccountViewModel.kt        账号清单、登录、验活、批量签到
├── CheckInViewModel.kt        二维码 / URL / 刷新循环
├── domain/                    ← **零 Android 依赖**，纯 Kotlin，可 JVM 单测
│   ├── Sign.kt                时间签名、URL 拼装、ip/ipt 校验、extractIpIpt 三级回退
│   └── Session.kt
├── web/                       OkHttp：CAS 登录 + 两步签到
│   ├── CasClient.kt           登录 / 验活 / 签到；结果判定 classifyCheckIn
│   ├── CasLogin.kt            登录页解析、表单构造、错误原因提取
│   ├── TpassCrypto.kt         非标准 DES
│   ├── RegForm.kt             登记表单解析（先删注释！）
│   ├── AccountRepository.kt   账号读写 + AccountCookieJar（每账号一罐）
│   └── SecureAccountFile.kt   Keystore AES-GCM 加密的单文件
├── scan/                      CameraX + ZXing（相册路径也在这里）
├── location/Position.kt       取一次真实坐标，失败带原因
├── qr/                        纯 JVM 编码（可单测）+ Android 侧 Bitmap 转换
└── ui/                        Compose 界面 + theme/
```

**多账号同时有效**：每个账号保存**自己的**一份会话 Cookie（`AccountCookieJar`）。
`class.cau.edu.cn` 是 PHP，按 `PHPSESSID` 区分会话，所以多个账号能同时处于登录态 ——
这是「多账号一起签到」的前提，也是当初放弃「一个共享会话」方案的原因。

**落盘不引数据库**：账号数 ≤ 15，整份清单连同 Cookie 一起用一个 Keystore 密钥
（alias `cau_class_checker_accounts_v1`）加密成单文件，格式 `[1 字节 IV 长度][IV][GCM 密文+tag]`。

**批量签到是串行的**，且**每个账号发请求前才现算 URL** —— `t` 按秒滚动，
提前批量生成会让靠后的账号拿到过期的码。定位也只取一次，所有账号共用。

## 约定与坑

- **界面文案全中文**。新增用户可见字符串照此办理。
- **测试夹具不要放真实学号 / 姓名**。所有用例里的 `2023000000000` / `张三` 都是替换过的假值。
- **注释写「为什么」，不写「是什么」**。本项目最难的几个坑（注释里的旧表单、
  重复的同名参数、`#errormsghide`、PC-1 的 D 半段）全都只有「为什么」讲得清；
  这些知识一旦退化成「照着这样写」，下一个改动的人就会把它改回去。
- **不要删 `buildCheckInBody` 的回归用例** —— 它钉的是真 bug，不是形式主义。
- 改网络层时留意：请求头应尽量贴近真实手机 Chrome（见 `CasClient.asBrowser`），
  但**不要手工设 `Accept-Encoding`** —— OkHttp 自己会加 gzip 并自动解压，
  写上 `br`/`zstd` 会让它拿到解不开的内容。
  `FormBody` 的 `Content-Type` 本来就与浏览器一致（不带 charset），**不要"修"它**。
