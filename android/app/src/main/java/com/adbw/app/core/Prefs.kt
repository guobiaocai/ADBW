package com.adbw.app.core

import android.content.Context

/** 密码 / deviceId / 中继地址 / 传输模式的本地存储。密码不落任何服务器，只存本地。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("vibeadb", Context.MODE_PRIVATE)

    val password: String
        get() = sp.getString(KEY_PASSWORD, null) ?: Password.generate().also {
            sp.edit().putString(KEY_PASSWORD, it).apply()
        }

    val deviceId: String
        get() = sp.getString(KEY_DEVICE_ID, null) ?: Password.deviceId().also {
            sp.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    /** 边缘中继域名（恒定，如 adbw-relay.xxx.workers.dev） */
    var relayHost: String
        get() = sp.getString(KEY_RELAY_HOST, "") ?: ""
        set(value) = sp.edit().putString(KEY_RELAY_HOST, value).apply()

    /** 传输模式：[MODE_RELAY] 走 Cloudflare DO 中继；[MODE_EASYTier] 走 EasyTier 组网直连 */
    var mode: String
        get() = sp.getString(KEY_MODE, MODE_RELAY) ?: MODE_RELAY
        set(value) = sp.edit().putString(KEY_MODE, value).apply()

    /** EasyTier 模式：WebSocket 监听端口 */
    var etPort: Int
        get() = sp.getInt(KEY_ET_PORT, DEFAULT_ET_PORT)
        set(value) = sp.edit().putInt(KEY_ET_PORT, value).apply()

    /** EasyTier 模式：绑定 IP。留空 = 0.0.0.0；可填本机 EasyTier 虚拟 IP 收窄监听面 */
    var etHost: String
        get() = sp.getString(KEY_ET_HOST, "") ?: ""
        set(value) = sp.edit().putString(KEY_ET_HOST, value).apply()

    /** EasyTier 网络名；留空用 vibeadb */
    var etNetworkName: String
        get() = sp.getString(KEY_ET_NETWORK, "vibeadb") ?: "vibeadb"
        set(value) = sp.edit().putString(KEY_ET_NETWORK, value).apply()

    /** EasyTier 网络密钥；留空复用配对密码 */
    var etSecret: String
        get() = sp.getString(KEY_ET_SECRET, "") ?: ""
        set(value) = sp.edit().putString(KEY_ET_SECRET, value).apply()

    /** 可选对端 / 公共共享节点，如 tcp://IP:11010 */
    var etPeer: String
        get() = sp.getString(KEY_ET_PEER, "") ?: ""
        set(value) = sp.edit().putString(KEY_ET_PEER, value).apply()

    fun resetPassword(): String {
        val p = Password.generate()
        sp.edit().putString(KEY_PASSWORD, p).apply()
        return p
    }

    companion object {
        const val MODE_RELAY = "relay"
        const val MODE_EASYTIER = "easytier"
        const val DEFAULT_ET_PORT = 27183

        private const val KEY_PASSWORD = "password"
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_RELAY_HOST = "relayHost"
        private const val KEY_MODE = "mode"
        private const val KEY_ET_PORT = "etPort"
        private const val KEY_ET_HOST = "etHost"
        private const val KEY_ET_NETWORK = "etNetwork"
        private const val KEY_ET_SECRET = "etSecret"
        private const val KEY_ET_PEER = "etPeer"
    }
}
