package com.adbw.app.core

import java.security.SecureRandom
import java.util.Base64

/** 随机凭证生成（见 PROTOCOL.md §1：密码 ≥32 字符 base64url，deviceId 32 字符 hex） */
object Password {

    /** ≥32 字符 base64url 高熵密码 */
    fun generate(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** 32 字符小写 hex deviceId（128 bit，信箱地址即读取凭证） */
    fun deviceId(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
