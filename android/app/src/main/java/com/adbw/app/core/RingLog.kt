package com.adbw.app.core

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 进程内环形日志（每个进程一个实例）。
 * 网关进程记录连接/命令事件；App 进程记录会话/绑定事件。
 * 通过 AIDL getLogs() 汇聚到 UI 实时查看与导出。
 */
object RingLog {
    private const val CAP = 800
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(tag: String, msg: String) {
        lines.addLast("${fmt.format(Date())} [$tag] $msg")
        while (lines.size > CAP) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
