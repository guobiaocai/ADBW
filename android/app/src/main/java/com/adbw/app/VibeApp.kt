package com.adbw.app

import android.app.Application
import android.util.Log
import java.io.File
import java.util.Date

/**
 * 全局崩溃捕获：任何未捕获异常写入 filesDir/crash-latest.txt（UI 可读）与
 * Android/data/com.adbw.app/files/（USB 可见），然后交回系统默认处理。
 */
class VibeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val text = buildString {
                    appendLine("time: ${Date()}")
                    appendLine("thread: ${t?.name}")
                    appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("stack:")
                    appendLine(Log.getStackTraceString(e))
                }
                runCatching { File(filesDir, "crash-latest.txt").writeText(text) }
                runCatching {
                    getExternalFilesDir(null)?.let { File(it, "crash-latest.txt").writeText(text) }
                }
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}
