package com.adbw.app.gateway

import com.adbw.app.core.RingLog
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * EasyTier 直连模式：手机侧 WebSocket **服务端**，等 MCP 经 EasyTier 虚拟网直连。
 * 协议与中继模式完全一致（auth + JSON-RPC + 二进制分块），只是传输从“出站 WS 客户端”
 * 变成“组网内 TCP 监听”。EasyTier 自身负责加密与 NAT 穿越；密码仍是端到端鉴权。
 *
 * 允许多个已鉴权 client 并存（每个连接独立 Dispatcher），并行 MCP 工具调用不再互踢。
 */
class EasyTierServer(
    listenHost: String,
    port: Int,
    private val password: String,
    private val onState: (String) -> Unit,
) : WebSocketServer(
    if (listenHost.isBlank()) InetSocketAddress(port) else InetSocketAddress(listenHost, port)
) {

    private class Conn {
        @Volatile var authed = false
        val dispatcher = Dispatcher(ProcessCommandRunner())
    }

    private val conns = ConcurrentHashMap<WebSocket, Conn>()
    private val authTimers = ConcurrentHashMap<WebSocket, java.util.concurrent.Future<*>>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "et-auth-timeout").apply { isDaemon = true }
    }

    @Volatile var listening = false
        private set

    init {
        isReuseAddr = true
        // 30s WS ping：EasyTier 虚拟网内一般不会被 NAT 掐，但保留以快速发现死连接
        connectionLostTimeout = 30
        RingLog.log("et", "prepare listen $address")
    }

    override fun onStart() {
        listening = true
        RingLog.log("et", "START $address")
        onState("online")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        RingLog.log("et", "OPEN ${conn.remoteSocketAddress}")
        conns[conn] = Conn()
        // PROTOCOL §3.1：15s 内必须 auth，否则 4002
        authTimers[conn] = scheduler.schedule({
            if (conns[conn]?.authed != true) {
                RingLog.log("et", "auth timeout -> 4002 ${conn.remoteSocketAddress}")
                try { conn.close(4002, "auth timeout") } catch (_: Exception) {}
            }
        }, 15, TimeUnit.SECONDS)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val c = conns[conn] ?: return
        val obj = try {
            JSONObject(message)
        } catch (e: Exception) {
            try { conn.send("""{"op":"protoError","error":"bad frame"}""") } catch (_: Exception) {}
            return
        }
        when (obj.optString("op")) {
            "auth" -> {
                if (obj.optString("password") == password) {
                    c.authed = true
                    authTimers.remove(conn)?.cancel(false)
                    RingLog.log("et", "auth OK ${conn.remoteSocketAddress}")
                    try { conn.send("""{"op":"auth","ok":true}""") } catch (_: Exception) {}
                } else {
                    RingLog.log("et", "auth FAILED ${conn.remoteSocketAddress}")
                    try { conn.send("""{"op":"auth","ok":false,"error":"bad password"}""") } catch (_: Exception) {}
                    try { conn.close(4001, "auth failed") } catch (_: Exception) {}
                }
            }
            "edge" -> {
                // 服务端模式没有边缘控制帧；为兼容 client 侧日志静默忽略
            }
            "" -> {
                if (!c.authed) {
                    try { conn.close(4001, "unauthorized") } catch (_: Exception) {}
                    return
                }
                c.dispatcher.dispatch(conn, replyChannel(conn), message)
            }
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        val c = conns[conn] ?: return
        if (!c.authed) {
            try { conn.close(4001, "unauthorized") } catch (_: Exception) {}
            return
        }
        if (message.remaining() < 5) return
        val id = message.int
        val data = ByteArray(message.remaining())
        message.get(data)
        c.dispatcher.onChunk(conn, id, data)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        RingLog.log("et", "CLOSE code=$code remote=$remote reason=${reason ?: "-"} ${conn.remoteSocketAddress}")
        authTimers.remove(conn)?.cancel(false)
        conns.remove(conn)?.dispatcher?.cancel(conn)
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        RingLog.log("et", "ERROR: ${ex?.message ?: "-"}")
    }

    fun shutdown() {
        RingLog.log("et", "shutdown requested")
        listening = false
        try { stop(500) } catch (_: Throwable) {}
        scheduler.shutdownNow()
        conns.clear()
    }

    private fun replyChannel(conn: WebSocket): ReplyChannel = object : ReplyChannel {
        override fun sendText(text: String) {
            conn.send(text)
        }

        override fun sendBinary(id: Int, data: ByteArray) {
            val buf = ByteBuffer.allocate(4 + data.size).putInt(id).put(data)
            buf.flip()
            conn.send(buf)
        }
    }
}
