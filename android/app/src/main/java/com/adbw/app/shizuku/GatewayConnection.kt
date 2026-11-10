package com.adbw.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import com.adbw.app.BuildConfig
import com.adbw.app.core.Prefs
import com.adbw.app.core.RingLog
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** App 侧：绑定 Shizuku UserService 并启停网关。须在后台线程调用（内部 latch 等待）。 */
object GatewayConnection : ServiceConnection {

    @Volatile
    private var binder: IGatewayService? = null
    private var latch = CountDownLatch(0)
    private var args: Shizuku.UserServiceArgs? = null

    private val deathRecipient = IBinder.DeathRecipient {
        RingLog.log("app", "GatewayService binder died")
        binder = null
        // 若 ensureStarted 正在 await，立刻醒来报错，别干等 15s
        latch.countDown()
    }

    /**
     * 绑定并启动网关。
     *
     * @param cleanRestart true 时先 unbind 并让 Shizuku 杀掉 UserService 进程再重拉，
     *   用于网关循环卡死/假重试的强制恢复；false 只解绑旧 connection 后重绑（进程可能复用）。
     */
    fun ensureStarted(
        context: Context,
        password: String,
        relayHost: String,
        deviceId: String,
        sid: String,
        epoch: Long,
        cleanRestart: Boolean = false,
        mode: String = Prefs.MODE_RELAY,
        listenPort: Int = 0,
        extraJson: String = "{}"
    ): Boolean {
        if (!Shizuku.pingBinder()) {
            throw IllegalStateException("Shizuku 未运行")
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("未授予 Shizuku 权限")
        }
        val a = Shizuku.UserServiceArgs(ComponentName(context, GatewayService::class.java))
            .processNameSuffix("gateway")
            .daemon(false) // 非 daemon 模式：宿主解除绑定或死亡时，Shizuku 自动清理
            .version(BuildConfig.VERSION_CODE)
            .tag("vibeadb")
        synchronized(this) {
            args = a
            // 关键：先解绑。UserService 进程死亡后 Shizuku 仍可能认为该 connection 已绑定，
            // 直接 bindUserService 不回调 onServiceConnected → 15s 超时假恢复。
            runCatching { Shizuku.unbindUserService(a, this, cleanRestart) }
            unlinkDeathQuietly()
            binder = null
            if (cleanRestart) {
                // 给 Shizuku 一点时间收掉旧 UserService 进程，避免 bind 到正在死亡的实例
                Thread.sleep(200)
            }
            latch = CountDownLatch(1)
            Shizuku.bindUserService(a, this)
        }
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw IllegalStateException("网关绑定超时")
        }
        val b = binder ?: throw IllegalStateException("网关绑定失败")
        return try {
            b.start(password, relayHost, deviceId, sid, epoch, mode, listenPort, extraJson)
        } catch (e: RemoteException) {
            binder = null
            throw IllegalStateException("网关启动通信异常: ${e.message}", e)
        }
    }

    @Synchronized
    fun stopSession(@Suppress("UNUSED_PARAMETER") context: Context) {
        unlinkDeathQuietly()
        args?.let { runCatching { Shizuku.unbindUserService(it, this, true) } }
        args = null
        binder = null
        latch.countDown()
    }

    /** 网关连接状态（"idle"/"connecting"/"online"/"retrying | ..."），未绑定返回 null */
    fun currentStatus(): String? = try {
        binder?.status()
    } catch (e: RemoteException) {
        binder = null
        null
    }

    /** 网关进程环形日志，未绑定返回 null */
    fun logs(): String? = try {
        binder?.getLogs()
    } catch (e: RemoteException) {
        binder = null
        null
    }

    fun clearLogs() {
        try {
            binder?.clearLogs()
        } catch (e: RemoteException) {
            binder = null
        }
        RingLog.clear()
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        try {
            service?.linkToDeath(deathRecipient, 0)
        } catch (_: RemoteException) {
            binder = null
            latch.countDown()
            return
        }
        binder = IGatewayService.Stub.asInterface(service)
        latch.countDown()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        unlinkDeathQuietly()
        binder = null
        latch.countDown()
    }

    private fun unlinkDeathQuietly() {
        try {
            binder?.asBinder()?.unlinkToDeath(deathRecipient, 0)
        } catch (_: Throwable) {
        }
    }
}
