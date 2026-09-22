package com.ffcrazy.cauclasschecker.web

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 与 **原版 des.js** 的对拍测试。
 *
 * 所有期望值都是把 `/tpass/comm/js/des.js` 下载下来、用 node 直接调它的
 * `strEnc(data,'1','2','3')` 跑出来的 —— 不是我们自己算的。
 * 这样任何一位不对都会立刻暴露，不存在「两边错得一样」的可能。
 *
 * 覆盖了各种长度：数据长度对 4 取余的 0/1/2/3 四种情况都有，
 * 因为 strEnc 是按 4 字符分块处理的，余数分支最容易写错。
 */
class TpassCryptoTest {

    /** 服务端登录页真实下发的 lt。 */
    private val lt = "LT-1538773-4sWCgfCMjQNe1ODfNsR9w7eWoe7nui-tpass"

    private fun enc(data: String) = TpassCrypto.strEnc(data, "1", "2", "3")

    @Test
    fun `matches the original des js on short inputs`() {
        assertEquals("79E8E3AF508DA690", enc("0123"))
        assertEquals("8464FE022385FBA6D7ECB4832A17EF58", enc("hello"))
    }

    @Test
    fun `matches the original des js on real login payloads`() {
        // 每条都是 strEnc(用户名 + 密码 + lt) —— 与 login6.js 里完全一致的调用方式
        val vectors = listOf(
            // 长度 49，余 1
            "a|b|58AEC09EE3380E0C69D645B07DB84FC0EBD52DA2A85EE61EADD5F0C2D7735D2A" +
                "66490757BCEBEDA5B95D8905C3A54FA92A6E72C22FD5061767DFDC550A81ACA2BC247" +
                "CDF3968C17A5A2F92265768AF5E656333225DBA65927FC545B38D6F1738B2DA8880EA270330",
            // 长度 51，余 3
            "ab|cd|A9CF2704230383D13F87C346C8672F7C04466811425E6D89BE1DE02043D7F6CA" +
                "0884384B23A1F04BE2C52791E3EEC872DBF812AC756F2FF3B02253DE11FA951D3E902BC" +
                "B32D6FFF4682B9B32E7FC96CD3644B6ACF047AE2A926395FE891257539668FE464C1DBD45",
            // 长度 52，余 0
            "abc|de|A9CF2704230383D1F6E8F83450C43B6841648C81FFA4C107D1F36436CAE9E4E1" +
                "51B16B8C28E62ECDFC4D0EF8ABA952BC9A3B3937BED3E9A2039B4F2DCF0D059A5EC8B9D" +
                "A220156D72E6D02E32A170ED77B4C709D715336832C929D79FED2FD540303D47B562E51A9",
            // 长度 55，余 3
            "user|pass|EB6D16D13160C8DC0303D47B562E51A93F87C346C8672F7C04466811425E6D89" +
                "BE1DE02043D7F6CA0884384B23A1F04BE2C52791E3EEC872DBF812AC756F2FF3B02253DE" +
                "11FA951D3E902BCB32D6FFF4682B9B32E7FC96CD3644B6ACF047AE2A926395FE891257539668FE464C1DBD45",
            // 长度 57，余 1
            "user1|pass1|EB6D16D13160C8DC43B2C3B0FF6910B1F29E045AE811A83669D645B07DB84FC0" +
                "EBD52DA2A85EE61EADD5F0C2D7735D2A66490757BCEBEDA5B95D8905C3A54FA92A6E72C2" +
                "2FD5061767DFDC550A81ACA2BC247CDF3968C17A5A2F92265768AF5E656333225DBA65927FC545B38D6F1738B2DA8880EA270330",
            // 长度 67，余 3
            "2023123456|MyPassw0rd|494845A373A4576BC1BB5938DF9F21902792A376B069F3567D6B" +
                "982EC91FCCA440D6C5A9B2ABE5BE3F87C346C8672F7C04466811425E6D89BE1DE02043D" +
                "7F6CA0884384B23A1F04BE2C52791E3EEC872DBF812AC756F2FF3B02253DE11FA951D3E902BCB32D6FFF4682B9B32E7FC96CD3644B6ACF047AE2A926395FE891257539668FE464C1DBD45",
            // 长度 66，余 2
            "testuser|testpass123|D8D35E5019288C41EB6D16D13160C8DCD8D35E5019288C410303D47B" +
                "562E51A957B8CAD82E38D314DDC9334FAC5CF63BBA954436E754D9B0DFCD9690A088D07B" +
                "8BB0879C30E32405DE346451B6944238A1C26FF2C66359770F763159D77AD33F701897DD9E8F8C21B3DF318F5A2349CCE7C24ADA121EEEF4568AB252F27C6846FD6A959843DC7E15",
        )

        for (v in vectors) {
            val parts = v.split("|")
            val user = parts[0]
            val pass = parts[1]
            val expected = parts[2]
            assertEquals("strEnc($user+$pass+lt) 与 des.js 不一致", expected, enc(user + pass + lt))
        }
    }

    @Test
    fun `strToBt packs chars as 16 bit big endian`() {
        // 位序正确是整套实现的地基，单独钉一条
        val bits = TpassCrypto.strToBt("0123")
        assertEquals(64, bits.size)
        // '0'=0x30 -> 00000000 00110000, '1'=0x31 -> 00000000 00110001, ...
        val asString = bits.joinToString("")
        assertEquals(
            "0000000000110000" + "0000000000110001" + "0000000000110010" + "0000000000110011",
            asString,
        )
    }

    @Test
    fun `strToBt zero pads a short tail`() {
        // "o" -> 'o'=0x6F -> 00000000 01101111，其余 48 位补 0
        val bits = TpassCrypto.strToBt("o")
        assertEquals("0000000001101111" + "0".repeat(48), bits.joinToString(""))
    }

    @Test
    fun `is deterministic`() {
        assertEquals(enc("someone" + "secret" + lt), enc("someone" + "secret" + lt))
    }
}
