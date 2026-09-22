package com.ffcrazy.cauclasschecker.web

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 账号文件的加密读写。
 *
 * ## 为什么用 Android Keystore
 * 密钥由系统生成并保管，**永不离开安全硬件**（支持的设备上），App 自己拿不到明文密钥，
 * 只能请求它做加解密。所以即使文件被拖走，在别的设备上也无法解开。
 *
 * 而且 Keystore 是系统自带的，**不需要引入任何依赖** —— 这正合「不引数据库」的初衷。
 *
 * ## 值不值得加密
 * App 私有目录本来就有沙箱 + 全盘加密保护，加密的额外收益主要是：
 * 被 root 的设备上直接扒文件、以及被备份工具抽走时，内容仍是密文。
 *
 * ## 文件格式
 * `[1 字节 IV 长度][IV][GCM 密文+认证标签]`
 * GCM 自带完整性校验，所以文件被改动过会解密失败而不是解出垃圾。
 */
class SecureAccountFile(private val file: File) : AccountStorage {

    /** 读出明文；文件不存在或解不开都返回 null。 */
    override fun readText(): String? {
        if (!file.exists()) return null
        return try {
            val blob = file.readBytes()
            if (blob.size < 2) return null
            val ivLen = blob[0].toInt() and 0xFF
            if (ivLen <= 0 || blob.size <= 1 + ivLen) return null

            val iv = blob.copyOfRange(1, 1 + ivLen)
            val cipherText = blob.copyOfRange(1 + ivLen, blob.size)

            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (t: Throwable) {
            // 密钥被系统轮换、文件被改坏、从别的设备拷过来的…… 都当空的重来。
            // 账号清单丢了可以重新登录，App 起不来才是真的麻烦。
            runCatching { file.delete() }
            null
        }
    }

    override fun writeText(text: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv

        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(iv.size.toByte(), *iv, *cipherText))
    }

    fun delete() {
        runCatching { file.delete() }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "cau_class_checker_accounts_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
