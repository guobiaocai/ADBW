package com.adbw.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.adbw.app.core.Pairing
import com.adbw.app.core.Prefs
import com.adbw.app.core.RingLog
import com.adbw.app.shizuku.GatewayConnection
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * 会话前台服务：Shizuku UserService 网关 + 到边缘中继的出站 WebSocket。
 * 生命周期 = 会话生命周期；出站连接自带重连退避，不做开机自启/保活。
 *
 * 恢复语义（修“掉线后虚假重试 / UI 只轮询不拉起”）：
 * - binder 死亡 → 立即 unbind + 重绑 UserService
 * - connecting/retrying 持续超过 [STUCK_RESTART_MS] → 强制重建 UserService
 * - Service 销毁 / Android 15 dataSync 超时 → 停工作线程并更新 UI，不留孤儿线程和冻结状态
 * - sid/epoch 在 companion 持有：同一次会话内恢复复用 epoch；新会话严格递增，防互踢
 */
class SessionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var stopped = false
    @Volatile private var sessionActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> startSession()
                ACTION_STOP -> stopSession()
                else -> {
                    // 未知 action（含系统补发）：没有活线程就别留一个空前台
                    if (!isWorkerAlive()) {
                        SessionState.update(SessionUiState.Idle)
                        stopSelf()
                    }
                }
            }
        } catch (t: Throwable) {
            SessionState.update(SessionUiState.Failed("服务启动异常: ${t.javaClass.simpleName}: ${t.message}"))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 系统销毁 Service（FGS 超时 / 用户停止 / OEM 清理）时必须停掉工作线程，
        // 否则线程残留 + SessionState 冻结 = UI 假在线/假重试
        if (!stopped) {
            SessionState.update(SessionUiState.Stopped("服务已销毁，会话已停止"))
        }
        stopSessionInternal()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int) {
        RingLog.log("app", "FGS onTimeout (dataSync 6h limit?)")
        SessionState.update(SessionUiState.Failed("前台服务超时（Android 15 dataSync 6 小时限制），请重新开始会话"))
        stopSessionInternal()
        super.onTimeout(startId)
    }

    private fun startForegroundCompat() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ADBW 会话", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("ADBW 会话运行中")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
        } catch (t: Throwable) {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun isWorkerAlive(): Boolean = workerThread?.isAlive == true

    private fun startSession() {
        synchronized(lock) {
            val old = workerThread
            if (old != null && old.isAlive) {
                RingLog.log("app", "startSession ignored: worker already alive")
                return
            }
            stopped = false
            sessionActive = true
            startForegroundCompat()
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibeadb:session")
                .also { it.acquire(6 * 60 * 60 * 1000L) }
            SessionState.update(SessionUiState.Starting("启动中…"))
            val t = thread(name = "vibeadb-session") { runSession() }
            workerThread = t
        }
    }

    private fun runSession() {
        val prefs = Prefs(this)
        try {
            val mode = prefs.mode
            val isEt = mode == Prefs.MODE_EASYTIER
            if (!isEt && prefs.relayHost.isBlank()) {
                throw IllegalStateException("未配置边缘中继地址（设置页填写），或在设置里切换到 EasyTier 模式")
            }
            // sid 取 deviceId 前 16 位（稳定）；epoch 每次“开始会话”递增。
            // 同会话恢复复用 epoch，relay 侧才认作“网络重连”而不是新会话互踢。
            val sid = prefs.deviceId.take(16)
            val epoch = newSessionEpoch()
            val channel = if (isEt) "easytier" else "relay"
            val pairing = if (isEt) {
                Pairing.easytier(prefs.etHost.ifBlank { "<phone-et-ip>" }, prefs.etPort, prefs.password)
            } else {
                Pairing.worker(prefs.relayHost, prefs.deviceId, prefs.password)
            }
            RingLog.log("app", "ensureStarted (mode=$channel relay=${prefs.relayHost} et=${prefs.etHost}:${prefs.etPort} sid=$sid epoch=$epoch)")

            // 首次启动重试几次：开机/刚开 Shizuku 时 binder 常常还没就绪，
            // 之前一失败就 Failed 终态，BootReceiver 拉起必然挂。
            var ok = false
            var lastErr: Throwable? = null
            for (attempt in 0 until 3) {
                if (stopped) return
                try {
                    ok = GatewayConnection.ensureStarted(
                        this, prefs.password, prefs.relayHost, prefs.deviceId, sid, epoch,
                        mode = mode, listenPort = prefs.etPort, extraJson = etExtra(prefs)
                    )
                    lastErr = null
                    break
                } catch (t: Throwable) {
                    lastErr = t
                    RingLog.log("app", "ensureStarted attempt $attempt failed: ${t.javaClass.simpleName}: ${t.message}")
                    if (attempt < 2) Thread.sleep(2000)
                }
            }
            RingLog.log("app", "ensureStarted -> $ok")
            if (!ok) throw lastErr ?: IllegalStateException("网关启动失败")

            var lastOnlineAt = System.currentTimeMillis()
            var lastRecoverError: String? = null
            while (!stopped) {
                val st = GatewayConnection.currentStatus()
                val now = System.currentTimeMillis()
                if (st == null) {
                    RingLog.log("app", "gateway binder dead/disconnected, recovering...")
                    SessionState.update(SessionUiState.Starting("网关自动恢复中…"))
                    lastRecoverError = recover(prefs, sid, epoch, force = false)
                    lastOnlineAt = now
                    Thread.sleep(2000)
                    continue
                }
                if (st == "online" || st.startsWith("online | et=")) {
                    lastOnlineAt = now
                    val (display, pairingNow) = runningDisplay(prefs, pairing, st)
                    SessionState.update(SessionUiState.Running(display, pairingNow, true))
                } else {
                    val stuckMs = now - lastOnlineAt
                    if (stuckMs > STUCK_RESTART_MS) {
                        RingLog.log("app", "gateway stuck in '$st' for ${stuckMs}ms, force restart")
                        SessionState.update(SessionUiState.Starting("网关长时间未上线，强制重启…"))
                        lastRecoverError = recover(prefs, sid, epoch, force = true)
                        lastOnlineAt = now
                        Thread.sleep(2000)
                        continue
                    }
                    SessionState.update(
                        when {
                            st == "crashed" ||
                                st.startsWith("connecting") || st.startsWith("retrying") ->
                                SessionUiState.Starting("连接$channel（$st）…")
                            else -> SessionUiState.Starting("等待网关…")
                        }
                    )
                }
                Thread.sleep(1000)
            }
        } catch (t: Throwable) {
            RingLog.log("app", "session error: ${t.javaClass.simpleName}: ${t.message}")
            if (!stopped) {
                SessionState.update(SessionUiState.Failed(t.message ?: t.javaClass.simpleName))
                stopSessionInternal()
            }
        }
    }

    /** EasyTier 启动参数（jniLib 路径 + 网络名/密钥/对端） */
    private fun etExtra(prefs: Prefs): String = JSONObject().apply {
        put("nativeLibDir", applicationInfo.nativeLibraryDir ?: "")
        put("etNetworkName", prefs.etNetworkName)
        put("etSecret", prefs.etSecret.ifBlank { prefs.password })
        put("etPeer", prefs.etPeer)
        put("etBind", prefs.etHost)
    }.toString()

    /**
     * online 时 UI 展示的通道与配对串。
     * EasyTier 模式下从 gateway status 的 "online | et=<ip>:<port>" 里拿检测到的虚拟 IP。
     */
    private fun runningDisplay(prefs: Prefs, fallbackPairing: String, st: String): Pair<String, String> {
        if (prefs.mode != Prefs.MODE_EASYTIER) return Pair(prefs.relayHost, fallbackPairing)
        val m = Regex("et=([0-9.]+):(\\d+)").find(st)
        val port = m?.groupValues?.get(2)?.toIntOrNull() ?: prefs.etPort
        // 用户手填的绑定 IP 优先（探测可能撞上其他 tun）；否则用网关探测到的 EasyTier 虚拟 IP
        val ip = when {
            prefs.etHost.isNotBlank() && prefs.etHost != "0.0.0.0" -> prefs.etHost
            m != null && m.groupValues[1] != "0.0.0.0" && m.groupValues[1] != "-" -> m.groupValues[1]
            else -> null
        }
        return if (ip == null) {
            Pair("EasyTier :${prefs.etPort}", fallbackPairing)
        } else {
            Pair("EasyTier $ip:$port", Pairing.easytier(ip, port, prefs.password))
        }
    }

    /** 重绑/重建网关。返回错误摘要（写进 UI，App 侧 RingLog 本来就不上屏）。 */
    private fun recover(prefs: Prefs, sid: String, epoch: Long, force: Boolean): String? {
        return try {
            val ok = GatewayConnection.ensureStarted(
                this, prefs.password, prefs.relayHost, prefs.deviceId, sid, epoch,
                cleanRestart = force,
                mode = prefs.mode,
                listenPort = prefs.etPort,
                extraJson = etExtra(prefs)
            )
            RingLog.log("app", "recover ensureStarted -> $ok (force=$force)")
            if (ok) null else "网关启动被拒绝"
        } catch (re: Throwable) {
            val msg = "${re.javaClass.simpleName}: ${re.message}"
            RingLog.log("app", "gateway recovery failed: $msg")
            SessionState.update(SessionUiState.Starting("网关自动恢复中…（$msg）"))
            msg
        }
    }

    private fun stopSession() {
        SessionState.update(SessionUiState.Idle)
        stopSessionInternal()
    }

    private fun stopSessionInternal() {
        stopped = true
        sessionActive = false
        RingLog.log("app", "session stopped")
        workerThread?.interrupt()
        try { GatewayConnection.stopSession(this) } catch (_: Throwable) {}
        try { wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.adbw.app.action.START"
        const val ACTION_STOP = "com.adbw.app.action.STOP"
        private const val CHANNEL_ID = "session"
        private const val NOTIF_ID = 1

        /** connecting/retrying 超过该时长仍不 online，视为网关循环卡死，强制重建 UserService */
        private const val STUCK_RESTART_MS = 45_000L

        private val lock = Any()

        @Volatile private var workerThread: Thread? = null

        /** 会话纪元：跨 Service 实例共享，避免双实例用不同 epoch 互踢 */
        @Volatile private var epoch: Long = 0L

        fun newSessionEpoch(): Long {
            synchronized(lock) {
                epoch = maxOf(epoch + 1, System.currentTimeMillis())
                return epoch
            }
        }
    }
}
