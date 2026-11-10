package com.adbw.app.shizuku

import android.content.Context
import com.adbw.app.core.RingLog
import com.adbw.app.gateway.EasyTierServer
import com.adbw.app.gateway.RelayTunnelClient
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/**
 * Shizuku UserService：以 shell/root UID 在独立进程运行。
 * relay：出站 WebSocket 到边缘中继。
 * easytier：内置 easytier-core（aarch64 静态 musl）组网 + 本机 WebSocket 监听。
 */
class GatewayService(private val context: Context?) : IGatewayService.Stub() {

    private var loopThread: Thread? = null
    @Volatile private var client: RelayTunnelClient? = null
    @Volatile private var server: EasyTierServer? = null
    @Volatile private var etProc: Process? = null

    @Volatile private var stopped = false
    @Volatile private var state = "idle"
    @Volatile private var mode = "relay"
    @Volatile private var etEndpoint: String? = null

    override fun start(
        password: String?,
        relayHost: String?,
        deviceId: String?,
        sid: String?,
        epoch: Long,
        mode: String?,
        listenPort: Int,
        extraJson: String?
    ): Boolean {
        if (password.isNullOrEmpty() || deviceId.isNullOrEmpty() || sid.isNullOrEmpty() || epoch <= 0) {
            RingLog.log("gw", "start rejected: empty/invalid args")
            return false
        }
        val m = if (mode == MODE_EASYTIER) MODE_EASYTIER else MODE_RELAY
        if (m == MODE_RELAY && relayHost.isNullOrEmpty()) {
            RingLog.log("gw", "start rejected: relay mode needs relayHost")
            return false
        }
        if (m == MODE_EASYTIER && (listenPort <= 0 || listenPort > 65535)) {
            RingLog.log("gw", "start rejected: easytier mode needs valid listenPort")
            return false
        }
        RingLog.log("gw", "start requested (mode=$m relay=$relayHost port=$listenPort sid=$sid epoch=$epoch)")
        stopTunnel()
        stopped = false
        this.mode = m
        generation += 1
        val myGen = generation
        val extra = try {
            JSONObject(extraJson ?: "{}")
        } catch (_: Throwable) {
            JSONObject()
        }
        val t = Thread {
            if (m == MODE_EASYTIER) {
                runEasyTierLoop(
                    password!!, listenPort, myGen, extra, deviceId!!
                )
            } else {
                runRelayLoop(password!!, relayHost!!, deviceId!!, sid!!, epoch, myGen)
            }
        }
        // UserService 进程不跑 VibeApp 的 crash handler；
        // 未捕获异常会直接杀进程，App 侧只看到 binder 死亡 + 假恢复。
        t.setUncaughtExceptionHandler { _, e ->
            RingLog.log("gw", "loop crashed: ${e.javaClass.simpleName}: ${e.message}")
            state = "crashed"
            try { client?.shutdown() } catch (_: Throwable) {}
            try { server?.shutdown() } catch (_: Throwable) {}
            try { etProc?.destroyForcibly() } catch (_: Throwable) {}
        }
        loopThread = t
        t.start()
        return true
    }

    private fun runRelayLoop(
        password: String,
        relayHost: String,
        deviceId: String,
        sid: String,
        epoch: Long,
        myGen: Int
    ) {
        var backoff = 2L
        while (!stopped && generation == myGen) {
            try {
                RingLog.log("gw", "connecting (gen=$myGen)")
                state = "connecting"
                val c = RelayTunnelClient(relayHost, deviceId, sid, epoch, password) { s -> state = s }
                client = c
                val opened = try {
                    c.connectBlocking(15, java.util.concurrent.TimeUnit.SECONDS)
                } catch (t: InterruptedException) {
                    false
                } catch (t: Throwable) {
                    RingLog.log("gw", "connect failed: ${t.message}")
                    false
                }
                if (opened && !stopped && generation == myGen) {
                    state = "online"
                    RingLog.log("gw", "online")
                    backoff = 2
                    while (!stopped && generation == myGen && c.isOpen) {
                        try {
                            Thread.sleep(500)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    if (generation == myGen) RingLog.log("gw", "connection lost (isOpen=false)")
                }
                try { c.shutdown() } catch (_: Exception) {}
            } catch (t: Throwable) {
                RingLog.log("gw", "tunnel error: ${t.message}")
            }
            if (stopped || generation != myGen) break
            state = "retrying"
            RingLog.log("gw", "retry in ${backoff}s")
            try {
                Thread.sleep(backoff * 1000)
            } catch (_: InterruptedException) {
                break
            }
            backoff = (backoff * 2).coerceAtMost(30)
        }
        RingLog.log("gw", "loop exited (gen=$myGen)")
    }

    // ---- EasyTier 内置（no_tun，不要求 root）----

    private fun sh(vararg script: String): String {
        return try {
            val pb = ProcessBuilder("sh", "-c", script.joinToString(" && "))
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.trim()
        } catch (t: Throwable) {
            RingLog.log("et", "sh failed: ${t.message}")
            ""
        }
    }

    private fun runEasyTierLoop(
        password: String,
        port: Int,
        myGen: Int,
        extra: JSONObject,
        deviceId: String
    ) {
        var backoff = 2L
        while (!stopped && generation == myGen) {
            try {
                state = "connecting"
                val bindHost = extra.optString("etBind").trim().takeIf { it.isNotBlank() && it != "0.0.0.0" } ?: "127.0.0.1"
                RingLog.log("gw", "et boot bind=$bindHost:$port no_tun (gen=$myGen)")

                val bin = installEasyTierBin(extra.optString("nativeLibDir"))
                    ?: throw IllegalStateException("找不到 libeasytiercore.so（需要 arm64 设备）")
                val networkName = extra.optString("etNetworkName").ifBlank { "vibeadb" }
                val secret = extra.optString("etSecret").ifBlank { password }
                val peer = extra.optString("etPeer").trim()
                val config = etConfigToml(networkName, secret, peer, wsPort = port, wsBind = bindHost)
                val work = File(ET_WORKDIR)
                work.mkdirs()
                val configFile = File(work, "config.toml")
                configFile.writeText(config)
                RingLog.log("et", "config written name=$networkName peer=${peer.ifBlank { "-" }} bind=$bindHost:$port")

                sh("pkill -f '$bin' || true")
                val pb = ProcessBuilder(bin, "-c", configFile.absolutePath, "--hostname", "vibeadb-${deviceId.take(8)}")
                pb.redirectErrorStream(true)
                pb.directory(work)
                val proc = pb.start()
                etProc = proc
                val ipRef = arrayOfNulls<String>(1)
                val pump = Thread {
                    try {
                        proc.inputStream.bufferedReader().forEachLine { line ->
                            val s = line.trim()
                            if (s.isEmpty()) return@forEachLine
                            RingLog.log("et", s.take(240))
                            // no_tun 下没有网卡，虚拟 IP 只能从日志里捡
                            val m = IP_10_126.find(s)
                            if (m != null && ipRef[0] == null) {
                                ipRef[0] = m.groupValues[1]
                                etEndpoint = "${m.groupValues[1]}:$port"
                                RingLog.log("et", "got virtual ip ${m.groupValues[1]}")
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
                pump.isDaemon = true
                pump.start()

                state = "connecting"
                val s = EasyTierServer(bindHost, port, password) { st ->
                    if (st == "online" && generation == myGen) {
                        etEndpoint = ipRef[0]?.let { "$it:$port" } ?: etEndpoint
                        state = "online"
                        RingLog.log("gw", "et online ${etEndpoint ?: "-"}")
                    }
                }
                server = s
                s.start()
                val deadline = System.currentTimeMillis() + 8000
                while (!s.listening && !stopped && generation == myGen &&
                    System.currentTimeMillis() < deadline
                ) {
                    Thread.sleep(100)
                }
                if (!s.listening) {
                    throw IllegalStateException("EasyTier WS 监听失败 $bindHost:$port")
                }
                state = "online"
                backoff = 2
                while (!stopped && generation == myGen && proc.isAlive) {
                    Thread.sleep(1000)
                }
                if (generation == myGen && !proc.isAlive) {
                    RingLog.log("et", "easytier-core exited code=${proc.exitValue()}")
                }
            } catch (t: Throwable) {
                RingLog.log("gw", "et tunnel error: ${t.message}")
            }
            try { etProc?.destroyForcibly() } catch (_: Throwable) {}
            etProc = null
            try { server?.shutdown() } catch (_: Throwable) {}
            server = null
            if (stopped || generation != myGen) break
            state = "retrying"
            RingLog.log("gw", "et retry in ${backoff}s")
            try {
                Thread.sleep(backoff * 1000)
            } catch (_: InterruptedException) {
                break
            }
            backoff = (backoff * 2).coerceAtMost(30)
        }
        RingLog.log("gw", "loop exited (gen=$myGen)")
    }

    /** 把 jniLibs 里的 libeasytiercore.so 拷到 /data/local/tmp 并加执行位 */
    private fun installEasyTierBin(nativeLibDir: String): String? {
        val work = File(ET_WORKDIR)
        work.mkdirs()
        val dest = File(work, "easytier-core")
        val srcCandidates = buildList {
            if (nativeLibDir.isNotBlank()) add(nativeLibDir + "/libeasytiercore.so")
            add("/data/local/tmp/vibeadb/libeasytiercore.so")
        }
        var src: String? = srcCandidates.firstOrNull { File(it).isFile }
        if (src == null) {
            val found = sh("find /data/app -name libeasytiercore.so 2>/dev/null | head -n 1")
            if (found.isNotBlank() && found.startsWith("/")) src = found
        }
        if (src == null) {
            RingLog.log("et", "libeasytiercore.so not found; natives=$nativeLibDir")
            return null
        }
        sh("cp '$src' '$dest'", "chmod 755 '$dest'")
        if (!dest.isFile || dest.length() < 1024) {
            RingLog.log("et", "copy failed src=$src")
            return null
        }
        RingLog.log("et", "bin ready ${dest.absolutePath} ${dest.length()}")
        return dest.absolutePath
    }

    /**
     * no_tun + smoltcp：
     * - 不创建 TUN，不需要 root
     * - WS 服务只绑 127.0.0.1:port（或用户指定的 etBind）
     * - EasyTier port_forward 把 0.0.0.0:port 转发到本机 WS，
     *   对端（PC 开 TUN）连虚拟 IP:port 即可；同网段也可连手机物理 IP:port
     */
    private fun etConfigToml(networkName: String, secret: String, peer: String, wsPort: Int, wsBind: String): String {
        val peerBlock = if (peer.isBlank()) "" else "[[peer]]\nuri = \"${peer.replace("\"", "")}\"\n\n"
        val pfBlock = if (wsBind == "127.0.0.1") {
            "[[port_forward]]\nbind_addr = \"0.0.0.0:$wsPort\"\ndst_addr = \"127.0.0.1:$wsPort\"\nproto = \"tcp\"\n\n"
        } else ""
        return """
instance_name = "adbw"
dhcp = true
listeners = [
    "tcp://0.0.0.0:11010",
    "udp://0.0.0.0:11010",
    "ws://0.0.0.0:11011/",
]
rpc_portal = "127.0.0.1:15888"

[network_identity]
network_name = "${networkName.replace("\"", "")}"
network_secret = "${secret.replace("\"", "")}"
${peerBlock}${pfBlock}[flags]
default_protocol = "tcp"
dev_name = "vibeadb0"
enable_encryption = true
enable_ipv6 = true
mtu = 1380
latency_first = true
no_tun = true
use_smoltcp = true
disable_p2p = false
relay_all_peer_rpc = false
""".trim() + "\n"
    }

    override fun status(): String = when {
        mode == MODE_EASYTIER && state == "online" ->
            "online | et=${etEndpoint ?: "-"}"
        state == "retrying" || state == "connecting" || state == "crashed" ->
            "$state | lastClose: ${client?.lastCloseInfo ?: "-"} | lastOpen: ${client?.lastOpenInfo ?: "-"}"
        else -> state
    }

    override fun getLogs(): String = RingLog.dump()

    override fun clearLogs() = RingLog.clear()

    override fun destroy() {
        RingLog.log("gw", "destroy called by Shizuku")
        stopTunnel()
        exitProcess(0)
    }

    @Synchronized
    private fun stopTunnel() {
        stopped = true
        generation += 1
        loopThread?.interrupt()
        loopThread = null
        try { client?.shutdown() } catch (_: Throwable) {}
        client = null
        try { server?.shutdown() } catch (_: Throwable) {}
        server = null
        try { etProc?.destroyForcibly() } catch (_: Throwable) {}
        etProc = null
        try { sh("pkill -f '$ET_WORKDIR/easytier-core' || true") } catch (_: Throwable) {}
        etEndpoint = null
    }

    companion object {
        const val MODE_RELAY = "relay"
        const val MODE_EASYTIER = "easytier"
        const val ET_WORKDIR = "/data/local/tmp/vibeadb"

        @Volatile private var generation = 0

        private val IP_10_126 = Regex("\\b(10\\.126\\.126\\.\\d+)\\b")
    }
}
