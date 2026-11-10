package com.adbw.app.gateway

import com.adbw.app.core.RingLog
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import javax.net.ssl.SSLSocketFactory

/**
 * v2 出站隧道：App（UserService 进程，shell/root UID）**主动连出**到边缘中继的 device 腿。
 * URL 包含 sid 与 epoch 参与中继端会话栅栏（Session Epoch Fencing），彻底杜绝旧僵尸连接抢占。
 */
class RelayTunnelClient(
    relayHost: String,
    deviceId: String,
    sid: String,
    epoch: Long,
    private val password: String,
    private val onState: (String) -> Unit,
) : WebSocketClient(buildUri(relayHost, deviceId, sid, epoch)) {

    private val dispatcher = Dispatcher(ProcessCommandRunner())

    @Volatile private var authed = false

    /** 最近一次连接关闭的详细信息（诊断用） */
    @Volatile var lastCloseInfo: String = "-"
        private set

    @Volatile var lastOpenInfo: String = "-"
        private set

    init {
        addHeader("X-Device-Id", deviceId)
        try {
            setSocketFactory(SSLSocketFactory.getDefault() as SSLSocketFactory)
        } catch (_: Throwable) {
        }
        // 30s WS ping 保活：防运营商 NAT/防火墙掐 60s 空闲长连接
        connectionLostTimeout = 30
        RingLog.log("ws", "connect -> ${uri} (sid=$sid, epoch=$epoch)")
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        lastOpenInfo = "http=${handshakedata?.httpStatus} msg=${handshakedata?.httpStatusMessage}"
        RingLog.log("ws", "OPEN $lastOpenInfo")
        onState("online")
        authed = false
    }

    override fun onMessage(message: String?) {
        val msg = message ?: return
        try {
            val obj = JSONObject(msg)
            when (obj.optString("op")) {
                "auth" -> {
                    if (obj.optString("password") == password) {
                        authed = true
                        RingLog.log("ws", "client auth OK")
                        send("""{"op":"auth","ok":true}""")
                    } else {
                        RingLog.log("ws", "client auth FAILED (bad password)")
                        send("""{"op":"auth","ok":false,"error":"bad password"}""")
                        close(4001, "auth failed")
                    }
                }
                "edge" -> {
                    val ev = obj.optString("event")
                    if (ev == "paired") RingLog.log("ws", "edge: client paired")
                    if (ev == "client_gone") RingLog.log("ws", "edge: client gone")
                    if (ev == "device_gone") RingLog.log("ws", "edge: device gone")
                }
                "" -> {
                    if (!authed) {
                        RingLog.log("ws", "frame before auth -> close 4001")
                        close(4001, "unauthorized")
                        return
                    }
                    dispatcher.dispatch(this, channel(), msg)
                }
            }
        } catch (e: Exception) {
            RingLog.log("ws", "bad frame: ${e.message}")
            try { send("""{"op":"protoError","error":"bad frame"}""") } catch (_: Exception) {}
        }
    }

    override fun onMessage(message: ByteBuffer) {
        if (!authed) {
            close(4001, "unauthorized")
            return
        }
        if (message.remaining() < 5) return
        val id = message.int
        val data = ByteArray(message.remaining())
        message.get(data)
        dispatcher.onChunk(this, id, data)
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        lastCloseInfo = "code=$code remote=$remote reason=${reason ?: "-"}"
        RingLog.log("ws", "CLOSE $lastCloseInfo")
        dispatcher.cancel(this)
        authed = false
    }

    override fun onError(ex: Exception?) {
        RingLog.log("ws", "ERROR: ${ex?.message ?: "-"}")
    }

    fun shutdown() {
        RingLog.log("ws", "shutdown requested")
        try { close() } catch (_: Exception) {}
    }

    private fun channel(): ReplyChannel = object : ReplyChannel {
        override fun sendText(text: String) {
            send(text)
        }

        override fun sendBinary(id: Int, data: ByteArray) {
            val buf = ByteBuffer.allocate(4 + data.size).putInt(id).put(data)
            buf.flip()
            send(buf)
        }
    }

    companion object {
        private fun buildUri(relayHost: String, deviceId: String, sid: String, epoch: Long): URI {
            val host = relayHost.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("wss://")
                .removePrefix("ws://")
                .removeSuffix("/")
            return URI("wss://$host/device?deviceId=$deviceId&sid=$sid&epoch=$epoch")
        }
    }
}
