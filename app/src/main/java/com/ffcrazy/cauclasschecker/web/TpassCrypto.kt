package com.ffcrazy.cauclasschecker.web

/**
 * 金智 tpass 统一身份认证的 `strEnc` 实现。
 *
 * ## 为什么不能直接用 javax.crypto
 * 服务端登录页的 `/tpass/comm/js/des.js` **不是标准 DES**。逐表核对结果：
 *
 * | 部件 | 是否标准 |
 * |---|---|
 * | 初始置换 IP | ✓ |
 * | 逆初始置换 IP⁻¹ | ✓ |
 * | 扩展置换 E | ✓ |
 * | P 置换 | ✓ |
 * | S 盒（8 张） | ✓ |
 * | 密钥压缩 PC-2 | ✓ |
 * | 循环左移表 | ✓ |
 * | 轮结构（16 轮 Feistel，末尾 R‖L） | ✓ |
 * | **PC-1 密钥置换** | **✗ 顺序被改过** |
 *
 * 用标准 DES 的经典测试向量（key `133457799BBCDFF1` / data `0123456789ABCDEF`）
 * 验证时会得到 `CC38B78305003643` 而非标准的 `85E813540F0AB405` —— 差异全部来自
 * PC-1。用「JS 的 PC-1 + 其余全标准」重算即可逐位复现，见 [PC1]。
 *
 * 正确性由 `TpassCryptoTest` 中**用 node 跑原版 JS 生成的参考向量**钉死。
 */
internal object TpassCrypto {

    // ------------------------------------------------------------ 置换表

    /**
     * ★ 与标准 DES 的唯一差异。
     *
     * 取自 des.js 里 `generateKeys` 的硬编码赋值 `key[i*8+j] = keyByte[8*k+i]`
     * （i 取 0..6，k 取 7..0），展开即 `out[i*8+j] = in[56-8j+i]`。
     *
     * 前 28 项与标准 C 半区一致；**后 28 项（D 半区）顺序与标准不同**，
     * 这正是整体结果对不上标准 DES 的原因。
     */
    private val PC1 = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0,
        57, 49, 41, 33, 25, 17, 9, 1,
        58, 50, 42, 34, 26, 18, 10, 2,
        59, 51, 43, 35, 27, 19, 11, 3,
        60, 52, 44, 36, 28, 20, 12, 4,
        61, 53, 45, 37, 29, 21, 13, 5,
        62, 54, 46, 38, 30, 22, 14, 6,
    )

    private val PC2 = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7,
        15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47,
        43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )

    private val IP = intArrayOf(
        57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
        61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
        56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2,
        60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6,
    )

    private val IP1 = intArrayOf(
        39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30,
        37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28,
        35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24,
    )

    private val E = intArrayOf(
        31, 0, 1, 2, 3, 4, 3, 4, 5, 6, 7, 8, 7, 8, 9, 10, 11, 12,
        11, 12, 13, 14, 15, 16, 15, 16, 17, 18, 19, 20, 19, 20, 21, 22, 23, 24,
        23, 24, 25, 26, 27, 28, 27, 28, 29, 30, 31, 0,
    )

    private val P = intArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24,
    )

    private val SHIFTS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    private val SBOX = arrayOf(
        intArrayOf(14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7),
        intArrayOf(0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8),
        intArrayOf(4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0),
        intArrayOf(15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13),

        intArrayOf(15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10),
        intArrayOf(3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5),
        intArrayOf(0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15),
        intArrayOf(13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9),

        intArrayOf(10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8),
        intArrayOf(13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1),
        intArrayOf(13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7),
        intArrayOf(1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12),

        intArrayOf(7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15),
        intArrayOf(13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9),
        intArrayOf(10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4),
        intArrayOf(3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14),

        intArrayOf(2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9),
        intArrayOf(14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6),
        intArrayOf(4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14),
        intArrayOf(11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3),

        intArrayOf(12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11),
        intArrayOf(10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8),
        intArrayOf(9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6),
        intArrayOf(4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13),

        intArrayOf(4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1),
        intArrayOf(13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6),
        intArrayOf(1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2),
        intArrayOf(6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12),

        intArrayOf(13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7),
        intArrayOf(1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2),
        intArrayOf(7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8),
        intArrayOf(2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11),
    )

    // ------------------------------------------------------------ 对外入口

    /**
     * 复刻 des.js 的 `strEnc(data, firstKey, secondKey, thirdKey)`。
     *
     * 行为要点：
     *  - 数据按 **4 个字符一块**切分，不足 4 字的尾巴也单独成块
     *  - 每块用 `firstKey` 的每个子密钥做一次 `enc`，再 `secondKey`，再 `thirdKey`
     *    —— 是 **enc→enc→enc**，不是标准 3DES 的 enc→dec→enc
     *  - 每块输出 16 位**大写**十六进制
     */
    fun strEnc(data: String, firstKey: String, secondKey: String, thirdKey: String): String {
        val keySets = listOf(firstKey, secondKey, thirdKey)
            .filter { it.isNotEmpty() }
            .map { getKeyBytes(it) }

        val sb = StringBuilder()
        var i = 0
        while (i < data.length) {
            val chunk = data.substring(i, minOf(i + 4, data.length))
            var block = strToBt(chunk)
            for (keys in keySets) {
                for (k in keys) {
                    block = encrypt(block, k)
                }
            }
            sb.append(bt64ToHex(block))
            i += 4
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ 内部实现

    /** 复刻 `strToBt`：每字符取 `charCodeAt` 的 16 位（大端），4 字凑 64 位，不足补 0。 */
    internal fun strToBt(s: String): IntArray {
        val bt = IntArray(64)
        for (i in 0 until 4) {
            val code = if (i < s.length) s[i].code else 0
            for (j in 0 until 16) {
                bt[i * 16 + j] = (code shr (15 - j)) and 1
            }
        }
        return bt
    }

    /** 复刻 `getKeyBytes`：密钥按 4 字符一组拆成若干 64 位子密钥。 */
    private fun getKeyBytes(key: String): List<IntArray> {
        val out = mutableListOf<IntArray>()
        var i = 0
        while (i < key.length) {
            out += strToBt(key.substring(i, minOf(i + 4, key.length)))
            i += 4
        }
        return out
    }

    /** 复刻 `bt64ToHex`：64 位按每 4 位转 1 个大写十六进制字符。 */
    internal fun bt64ToHex(bits: IntArray): String {
        val sb = StringBuilder(16)
        for (i in 0 until 16) {
            var v = 0
            for (j in 0 until 4) v = (v shl 1) or bits[i * 4 + j]
            sb.append(HEX_UPPER[v])
        }
        return sb.toString()
    }

    private fun permute(bits: IntArray, table: IntArray): IntArray {
        val out = IntArray(table.size)
        for (i in table.indices) out[i] = bits[table[i]]
        return out
    }

    private fun subKeys(key: IntArray): Array<IntArray> {
        val k56 = permute(key, PC1)
        var c = k56.copyOfRange(0, 28)
        var d = k56.copyOfRange(28, 56)
        return Array(16) { round ->
            val s = SHIFTS[round]
            c = c.copyOfRange(s, 28) + c.copyOfRange(0, s)
            d = d.copyOfRange(s, 28) + d.copyOfRange(0, s)
            permute(c + d, PC2)
        }
    }

    private fun feistel(r: IntArray, k: IntArray): IntArray {
        val x = permute(r, E)
        for (i in x.indices) x[i] = x[i] xor k[i]

        val y = IntArray(32)
        for (box in 0 until 8) {
            val o = box * 6
            val row = (x[o] shl 1) or x[o + 5]
            val col = (x[o + 1] shl 3) or (x[o + 2] shl 2) or (x[o + 3] shl 1) or x[o + 4]
            val v = SBOX[box * 4 + row][col]
            val base = box * 4
            y[base] = (v shr 3) and 1
            y[base + 1] = (v shr 2) and 1
            y[base + 2] = (v shr 1) and 1
            y[base + 3] = v and 1
        }
        return permute(y, P)
    }

    /** 单块 DES 加密（这是标准轮结构，差异只在 [PC1]）。 */
    internal fun encrypt(data: IntArray, key: IntArray): IntArray {
        val ks = subKeys(key)
        val b = permute(data, IP)
        var l = b.copyOfRange(0, 32)
        var r = b.copyOfRange(32, 64)
        repeat(16) { round ->
            val f = feistel(r, ks[round])
            val nextR = IntArray(32) { l[it] xor f[it] }
            l = r
            r = nextR
        }
        // 末尾 R‖L —— 标准 DES 的最终交换
        return permute(r + l, IP1)
    }

    private const val HEX_UPPER = "0123456789ABCDEF"

    /** 登录页用的固定密钥，来自 `login6.js`。 */
    const val KEY1 = "1"
    const val KEY2 = "2"
    const val KEY3 = "3"
}
