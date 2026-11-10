package com.adbw.app.gateway

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * JSON-RPC 分发器（PROTOCOL.md §4）。
 * 运行于 Shizuku UserService 进程（shell/root UID）。
 */
class Dispatcher(private val runner: CommandRunner) {

    private class Upload(val process: Process, val size: Long) {
        @Volatile var received = 0L
        @Volatile var overflow = false
    }

    private val uploads = ConcurrentHashMap<String, Upload>()
    private val streams = ConcurrentHashMap<String, Process>()

    private fun key(conn: Any, id: Int) = "${System.identityHashCode(conn)}:$id"

    fun dispatch(conn: Any, ch: ReplyChannel, raw: String) {
        val req = try {
            JSONObject(raw)
        } catch (e: Exception) {
            ch.sendText(errJson(0, -32700, "parse error"))
            return
        }
        val id = req.optInt("id")
        if (req.optString("jsonrpc") != "2.0") {
            ch.sendText(errJson(id, -32600, "invalid request"))
            return
        }
        val method = req.optString("method")
        val params = req.optJSONObject("params") ?: JSONObject()
        try {
            when (method) {
                "ping" -> ch.sendText(okJson(id, JSONObject().put("pong", true)))
                "shell" -> shell(conn, ch, id, params)
                "screencap" -> screencap(ch, id)
                "ui.dump" -> uiDump(ch, id)
                "input" -> input(ch, id, params)
                "pm.install" -> pmInstall(conn, ch, id, params)
                "pm.uninstall" -> simple(ch, id, listOf("pm", "uninstall", params.optString("package")))
                "pm.list" -> simple(ch, id, listOf("pm", "list", "packages", "-3"))
                "logcat" -> startStream(conn, ch, id, "logcat -v time")
                else -> ch.sendText(errJson(id, -32601, "unknown method: $method"))
            }
        } catch (t: Throwable) {
            ch.sendText(errJson(id, -32000, t.message ?: "internal error"))
        }
    }

    /** 客户端上传的二进制块（pm.install 的 APK 数据） */
    fun onChunk(conn: Any, id: Int, data: ByteArray) {
        val u = uploads[key(conn, id)] ?: return
        if (u.overflow) return
        if (u.received + data.size > u.size) {
            u.overflow = true
            try { u.process.destroyForcibly() } catch (_: Exception) {}
            return
        }
        try {
            u.process.outputStream.write(data)
            u.process.outputStream.flush()
            u.received += data.size
        } catch (_: Exception) {
        }
    }

    /** 客户端上传结束（关闭 pm install 的 stdin） */
    fun onEod(conn: Any, id: Int) {
        uploads[key(conn, id)]?.let { u ->
            try { u.process.outputStream.close() } catch (_: Exception) {}
        }
    }

    /** 连接断开时清理该连接的全部任务 */
    fun cancel(conn: Any) {
        val prefix = "${System.identityHashCode(conn)}:"
        uploads.keys.filter { it.startsWith(prefix) }.forEach { k ->
            uploads.remove(k)?.let { u ->
                try { u.process.destroyForcibly() } catch (_: Exception) {}
            }
        }
        streams.keys.filter { it.startsWith(prefix) }.forEach { k ->
            streams.remove(k)?.let { proc ->
                try { proc.destroyForcibly() } catch (_: Exception) {}
            }
        }
    }

    // ---- method impls ----

    private fun okJson(id: Int, result: JSONObject): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()

    private fun errJson(id: Int, code: Int, message: String): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message)).toString()

    private fun execReply(ch: ReplyChannel, id: Int, cmd: List<String>, timeoutSec: Int) {
        val r = runner.exec(cmd, timeoutSec)
        if (r.exitCode != 0) {
            ch.sendText(errJson(id, -32000, "exit ${r.exitCode}: ${r.stderr.ifBlank { "failed" }}"))
            return
        }
        ch.sendText(
            okJson(id, JSONObject().put("exitCode", r.exitCode).put("output", r.data.toString(Charsets.UTF_8)))
        )
    }

    private fun simple(ch: ReplyChannel, id: Int, cmd: List<String>) = execReply(ch, id, cmd, 60)

    private fun shell(conn: Any, ch: ReplyChannel, id: Int, params: JSONObject) {
        val command = params.optString("command")
        if (command.isBlank()) {
            ch.sendText(errJson(id, -32602, "command required"))
            return
        }
        if (params.optBoolean("stream", false)) {
            startStream(conn, ch, id, command)
            return
        }
        val timeout = params.optInt("timeoutSec", 60).coerceIn(1, 1800)
        val r = runner.execShell(command, timeout)
        ch.sendText(
            okJson(id, JSONObject().put("exitCode", r.exitCode).put("stdout", r.stdout).put("stderr", r.stderr))
        )
    }

    private fun startStream(conn: Any, ch: ReplyChannel, id: Int, command: String) {
        if (command.isBlank()) {
            ch.sendText(errJson(id, -32602, "command required"))
            return
        }
        val proc = runner.spawnShell(command)
        val k = key(conn, id)
        streams[k] = proc
        val t = Thread {
            try {
                proc.inputStream.use { ins ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n > 0) ch.sendBinary(id, buf.copyOf(n))
                    }
                }
                val code = proc.waitFor()
                try { ch.sendText(okJson(id, JSONObject().put("exitCode", code))) } catch (_: Throwable) {}
            } catch (t: Throwable) {
                try { proc.destroyForcibly() } catch (_: Exception) {}
                try { ch.sendText(errJson(id, -32000, "stream aborted")) } catch (_: Throwable) {}
            } finally {
                streams.remove(k)
            }
        }
        t.start()
    }

    private fun screencap(ch: ReplyChannel, id: Int) {
        val r = runner.exec(listOf("screencap", "-p"), 60)
        if (r.exitCode != 0) {
            ch.sendText(errJson(id, -32000, "screencap failed: ${r.stderr.ifBlank { "exit ${r.exitCode}" }}"))
            return
        }
        // Cloudflare WS 入站单帧上限 1 MiB；整张 PNG 一帧发出去会被掐线再进重试循环
        val chunk = 256 * 1024
        var off = 0
        while (off < r.data.size) {
            val end = minOf(off + chunk, r.data.size)
            ch.sendBinary(id, r.data.copyOfRange(off, end))
            off = end
        }
        ch.sendText(okJson(id, JSONObject().put("size", r.data.size)))
    }

    private fun uiDump(ch: ReplyChannel, id: Int) {
        val dump = runner.exec(listOf("uiautomator", "dump", UI_DUMP_PATH), 30)
        if (dump.exitCode != 0) {
            ch.sendText(errJson(id, -32000, "uiautomator dump failed: ${dump.stderr.ifBlank { "exit ${dump.exitCode}" }}"))
            return
        }
        val cat = runner.exec(listOf("cat", UI_DUMP_PATH), 15)
        if (cat.exitCode != 0) {
            ch.sendText(errJson(id, -32000, "read dump failed"))
            return
        }
        ch.sendText(okJson(id, JSONObject().put("xml", String(cat.data, Charsets.UTF_8))))
    }

    private fun input(ch: ReplyChannel, id: Int, params: JSONObject) {
        fun arg(name: String): String =
            if (params.has(name)) params.get(name).toString() else "0"
        val cmd: List<String> = when (params.optString("kind")) {
            "tap" -> listOf("input", "tap", arg("x"), arg("y"))
            "swipe" -> listOf(
                "input", "swipe", arg("x"), arg("y"), arg("x2"), arg("y2"),
                params.optInt("durationMs", 300).toString()
            )
            "text" -> listOf("input", "text", params.optString("text"))
            "keyevent" -> listOf("input", "keyevent", arg("keyCode"))
            else -> {
                ch.sendText(errJson(id, -32602, "unknown input kind"))
                return
            }
        }
        execReply(ch, id, cmd, 30)
    }

    private fun pmInstall(conn: Any, ch: ReplyChannel, id: Int, params: JSONObject) {
        val size = params.optLong("size", -1L)
        if (size <= 0) {
            ch.sendText(errJson(id, -32602, "size required"))
            return
        }
        val proc = runner.spawn("pm", "install", "-S", size.toString())
        uploads[key(conn, id)] = Upload(proc, size)
        val t = Thread {
            var exitCode = -1
            var output = ""
            try {
                val done = proc.waitFor(300, TimeUnit.SECONDS)
                if (done) {
                    exitCode = proc.exitValue()
                    output = proc.inputStream.bufferedReader().readText().trim()
                } else {
                    proc.destroyForcibly()
                    output = "timeout"
                }
            } catch (t: Throwable) {
                output = "aborted"
            } finally {
                uploads.remove(key(conn, id))
                // 连接已断时 send 会抛 WebsocketNotConnectedException；
                // 之前在 try 外面，未捕获异常会打死整个 UserService 进程
                try {
                    ch.sendText(okJson(id, JSONObject().put("exitCode", exitCode).put("output", output)))
                } catch (_: Throwable) {
                }
            }
        }
        t.start()
    }

    companion object {
        const val UI_DUMP_PATH = "/data/local/tmp/vibeadb_ui.xml"
    }
}
