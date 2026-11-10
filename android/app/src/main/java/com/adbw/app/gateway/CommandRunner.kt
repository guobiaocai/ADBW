package com.adbw.app.gateway

import java.util.concurrent.TimeUnit

/** 网关回复通道（便于测试抽象，WebSocket 服务端适配此接口） */
interface ReplyChannel {
    fun sendText(text: String)
    fun sendBinary(id: Int, data: ByteArray)
}

data class ExecResult(val exitCode: Int, val data: ByteArray, val stderr: String)
data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

interface CommandRunner {
    /** 执行 argv 命令，stdout 以字节返回（screencap 的 PNG 等） */
    fun exec(cmd: List<String>, timeoutSec: Int): ExecResult

    /** 执行 sh -c 命令，stdout 以文本返回 */
    fun execShell(command: String, timeoutSec: Int): ShellResult

    /** 流式命令（shell stream / logcat），合并 stderr */
    fun spawnShell(command: String): Process

    /** 直接 argv 启动（pm install -S 等，需要写 stdin） */
    fun spawn(vararg cmd: String): Process
}

class ProcessCommandRunner : CommandRunner {

    override fun exec(cmd: List<String>, timeoutSec: Int): ExecResult {
        val p = ProcessBuilder(cmd).start()
        val out = java.io.ByteArrayOutputStream()
        val err = java.io.ByteArrayOutputStream()
        val t1 = Thread {
            try {
                p.inputStream.copyTo(out)
            } catch (_: Throwable) {
                // Ignore stream close/interrupt on process kill or timeout
            }
        }
        val t2 = Thread {
            try {
                p.errorStream.copyTo(err)
            } catch (_: Throwable) {
                // Ignore stream close/interrupt on process kill or timeout
            }
        }
        t1.start()
        t2.start()
        val done = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!done) {
            p.destroyForcibly()
            return ExecResult(-1, ByteArray(0), "timeout")
        }
        t1.join(5000)
        t2.join(5000)
        return ExecResult(p.exitValue(), out.toByteArray(), err.toString("UTF-8"))
    }

    override fun execShell(command: String, timeoutSec: Int): ShellResult {
        val r = exec(listOf("sh", "-c", command), timeoutSec)
        return ShellResult(r.exitCode, r.data.toString(Charsets.UTF_8), r.stderr)
    }

    override fun spawnShell(command: String): Process =
        ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()

    override fun spawn(vararg cmd: String): Process = ProcessBuilder(*cmd).start()
}
