package com.adbw.app.core

/** 配对串构建与解析（PROTOCOL.md §1） */
object Pairing {

    data class Parsed(
        val mailboxHost: String?,
        val deviceId: String?,
        val tunnelHost: String?,
        val password: String,
    ) {
        val viaMailbox: Boolean get() = deviceId != null

        /** vibeadb://et/<host>:<port>#<password>：EasyTier 直连 */
        val viaEasyTier: Boolean get() = mailboxHost == "et" && deviceId != null
    }

    fun worker(workerHost: String, deviceId: String, password: String): String =
        "vibeadb://$workerHost/$deviceId#$password"

    fun direct(tunnelHost: String, password: String): String =
        "vibeadb://$tunnelHost#$password"

    /** EasyTier P2P：vibeadb://et/<host>:<port>#<password> */
    fun easytier(host: String, port: Int, password: String): String =
        worker("et", "$host:$port", password)

    fun parse(raw: String): Parsed {
        var s = raw.trim()
        if (!s.startsWith("vibeadb://")) {
            throw IllegalArgumentException("无效配对串：缺少 vibeadb:// 前缀")
        }
        s = s.removePrefix("vibeadb://")
        val body = s.substringBefore("#")
        val password = s.substringAfter("#", "")
        if (body.isEmpty() || password.isEmpty()) {
            throw IllegalArgumentException("无效配对串")
        }
        return if (body.contains("/")) {
            val host = body.substringBefore("/")
            val device = body.substringAfter("/")
            if (host.isEmpty() || device.isEmpty()) {
                throw IllegalArgumentException("无效配对串")
            }
            Parsed(host, device, null, password)
        } else {
            Parsed(null, null, body, password)
        }
    }
}
