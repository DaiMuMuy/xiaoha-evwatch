package com.evwatch.xiaoha.data.crypto

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 小哈智电（switchpowerapi.hellobike.com）报文加解密与签名
 *
 * - AES-128-CBC / PKCS7(key 与 iv 相同且固定)
 * - 请求体 = base64(AES(明文 JSON))
 * - 响应体同样需要解密
 * - 签名 = HmacSHA1(明文 + timestamp + nonce, secret) 的 hex
 * - timestamp 毫秒字符串，nonce 6 位随机数字
 */
object HbCrypto {

    private const val AES_KEY = "0199bec97dfa5e0d"
    private const val SIGN_SECRET = "386a09ea946d46f1b4ca3b4e3df8de45"

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(AES_KEY.toByteArray(Charsets.UTF_8))
        )
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(cipherText: String): String {
        val cleaned = cipherText.filterNot { it.isWhitespace() }
        val raw = Base64.decode(cleaned, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(AES_KEY.toByteArray(Charsets.UTF_8))
        )
        return String(cipher.doFinal(raw), Charsets.UTF_8)
    }

    fun sign(plain: String, timestamp: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(SIGN_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val digest = mac.doFinal((plain + timestamp + nonce).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
