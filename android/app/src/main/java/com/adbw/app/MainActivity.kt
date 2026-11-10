package com.adbw.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.adbw.app.core.Pairing
import com.adbw.app.core.Prefs
import com.adbw.app.shizuku.GatewayConnection
import rikka.shizuku.Shizuku
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startSessionService()
        }

    private val shizukuPermListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> UiTick.bump() }

    // Shizuku 死亡/回来时刷新卡片；配合 AppUi 里的周期 tick 兜底
    private val binderReceivedListener = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() {
            UiTick.bump()
        }
    }
    private val binderDeadListener = object : Shizuku.OnBinderDeadListener {
        override fun onBinderDead() {
            UiTick.bump()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermListener)
        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {
            // Shizuku provider 未就绪：AppUi 周期 tick 会兜底刷新
        }
        setContent { AppUi() }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    private fun startSessionService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, SessionService::class.java).setAction(SessionService.ACTION_START)
        )
    }

    private fun stopSessionService() {
        startService(
            Intent(this, SessionService::class.java).setAction(SessionService.ACTION_STOP)
        )
    }

    private fun requestNotifThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startSessionService()
        }
    }

    private fun requestShizukuPermission() {
        try {
            Shizuku.requestPermission(1001)
        } catch (t: Throwable) {
            Toast.makeText(this, "Shizuku 不可用：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- UI ----

    @Composable
    private fun AppUi() {
        val ctx = LocalContext.current
        val prefs = remember { Prefs(ctx) }
        val state by SessionState.state.collectAsState()
        val tick by UiTick.tick.collectAsState()
        val shizukuText = remember(tick) { shizukuStatus() }

        // Shizuku 状态不能只靠授权回调：服务死了/回来了都要能看见
        LaunchedEffect(Unit) {
            while (true) {
                delay(2500)
                UiTick.bump()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "ADBW",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            ShizukuCard(shizukuText) { requestShizukuPermission() }
            CrashCard()
            SessionCard(state, onStart = { requestNotifThenStart() }, onStop = { stopSessionService() })
            LogCard()
            SettingsCard(prefs)
        }
    }

    @Composable
    private fun CrashCard() {
        val ctx = LocalContext.current
        val tick by UiTick.tick.collectAsState()
        val crash = remember(tick) { File(ctx.filesDir, "crash-latest.txt").takeIf { it.exists() }?.readText() }
        if (crash == null) return
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("上次崩溃报告", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Text(crash.take(2000), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { copyText(ctx, "crash", crash) }) { Text("复制崩溃报告") }
                    OutlinedButton(onClick = {
                        File(ctx.filesDir, "crash-latest.txt").delete()
                        UiTick.bump()
                        Toast.makeText(ctx, "已清除", Toast.LENGTH_SHORT).show()
                    }) { Text("清除") }
                }
            }
        }
    }

    @Composable
    private fun ShizukuCard(status: String, onRequest: () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shizuku", fontWeight = FontWeight.Bold)
                Text("状态: $status")
                OutlinedButton(onClick = onRequest) { Text("请求授权") }
                Text(
                    "若未运行：安装 Shizuku 后，通过「无线调试」或 root 启动。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    @Composable
    private fun SessionCard(state: SessionUiState, onStart: () -> Unit, onStop: () -> Unit) {
        val ctx = LocalContext.current
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("会话", fontWeight = FontWeight.Bold)
                when (state) {
                    is SessionUiState.Idle, is SessionUiState.Stopped -> {
                        if (state is SessionUiState.Stopped) Text(state.message)
                        Text("未运行。开始会话后把配对串发给 MCP 服务器即可（永久有效）。")
                        Button(onClick = onStart) { Text("开始会话") }
                    }
                    is SessionUiState.Starting -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(Modifier.padding(2.dp))
                            Text(state.message)
                        }
                        OutlinedButton(onClick = onStop) { Text("停止") }
                    }
                    is SessionUiState.Running -> {
                        Text("通道: ${state.relayHost} · ${if (state.online) "已连接" else "连接中"}")
                        Text("Agent 可随时通过配对串连接（支持断线自动重连）。")
                        Text("配对串:", fontWeight = FontWeight.Bold)
                        Text(state.pairing, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { copyText(ctx, "pairing", state.pairing) }) {
                                Text("复制配对串")
                            }
                            OutlinedButton(onClick = onStop) { Text("停止会话") }
                        }
                    }
                    is SessionUiState.Failed -> {
                        Text("失败: ${state.message}", color = MaterialTheme.colorScheme.error)
                        Button(onClick = onStart) { Text("重试") }
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsCard(prefs: Prefs) {
        val ctx = LocalContext.current
        var mode by remember { mutableStateOf(prefs.mode) }
        var host by remember { mutableStateOf(prefs.relayHost) }
        var etHost by remember { mutableStateOf(prefs.etHost) }
        var etPort by remember { mutableStateOf(prefs.etPort.toString()) }
        var etNet by remember { mutableStateOf(prefs.etNetworkName) }
        var etSecret by remember { mutableStateOf(prefs.etSecret) }
        var etPeer by remember { mutableStateOf(prefs.etPeer) }
        var pw by remember { mutableStateOf(prefs.password) }

        val pairing = if (mode == Prefs.MODE_EASYTIER) {
            Pairing.easytier(
                etHost.ifBlank { "<phone-et-ip>" },
                etPort.toIntOrNull() ?: Prefs.DEFAULT_ET_PORT,
                prefs.password
            )
        } else {
            Pairing.worker(
                prefs.relayHost.ifBlank { "<relay-host>" },
                prefs.deviceId,
                prefs.password
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("设置", fontWeight = FontWeight.Bold)
                Text("传输模式", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mode == Prefs.MODE_RELAY) {
                        Button(onClick = { mode = Prefs.MODE_RELAY }) { Text("边缘中继") }
                    } else {
                        OutlinedButton(onClick = { mode = Prefs.MODE_RELAY }) { Text("边缘中继") }
                    }
                    if (mode == Prefs.MODE_EASYTIER) {
                        Button(onClick = { mode = Prefs.MODE_EASYTIER }) { Text("EasyTier") }
                    } else {
                        OutlinedButton(onClick = { mode = Prefs.MODE_EASYTIER }) { Text("EasyTier") }
                    }
                }
                if (mode == Prefs.MODE_RELAY) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("边缘中继地址（如 adbw-relay.xxx.workers.dev）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else {
                    OutlinedTextField(
                        value = etNet,
                        onValueChange = { etNet = it },
                        label = { Text("EasyTier 网络名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = etSecret,
                        onValueChange = { etSecret = it },
                        label = { Text("网络密钥（留空 = 配对密码）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = etPeer,
                        onValueChange = { etPeer = it },
                        label = { Text("对端/公共节点（可选，如 tcp://IP:11010）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = etHost,
                            onValueChange = { etHost = it },
                            label = { Text("绑定 IP（空=0.0.0.0）") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = etPort,
                            onValueChange = { etPort = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("WS 端口") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    Text(
                        "已内置 easytier-core（arm64，no_tun，免 root）。WS 只听 127.0.0.1，经 EasyTier port_forward 转出。" +
                        "PC 用相同网络名/密钥跑 easytier-core（Windows 开 TUN），连会话卡片里的虚拟 IP 配对串；" +
                        "同局域网也可直接连手机 IP:端口。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(onClick = {
                    prefs.mode = mode
                    if (mode == Prefs.MODE_RELAY) {
                        prefs.relayHost = host.trim()
                            .removePrefix("https://")
                            .removePrefix("http://")
                            .removePrefix("wss://")
                            .removePrefix("ws://")
                            .removeSuffix("/")
                        host = prefs.relayHost
                    } else {
                        prefs.etHost = etHost.trim()
                        prefs.etPort = (etPort.toIntOrNull() ?: Prefs.DEFAULT_ET_PORT)
                            .coerceIn(1024, 65535)
                        prefs.etNetworkName = etNet.trim().ifBlank { "vibeadb" }
                        prefs.etSecret = etSecret
                        prefs.etPeer = etPeer.trim()
                        etPort = prefs.etPort.toString()
                        etHost = prefs.etHost
                        etNet = prefs.etNetworkName
                    }
                    Toast.makeText(ctx, "已保存（重启会话生效）", Toast.LENGTH_SHORT).show()
                }) { Text("保存配置") }

                Text("配对串（永久有效）:", fontWeight = FontWeight.Bold)
                Text(pairing, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copyText(ctx, "pairing", pairing) }) {
                        Text("复制配对串")
                    }
                    OutlinedButton(onClick = { copyText(ctx, "deviceId", prefs.deviceId) }) {
                        Text("复制 ID")
                    }
                    OutlinedButton(onClick = {
                        pw = prefs.resetPassword()
                        Toast.makeText(ctx, "密码已重置（记得更新 Agent 侧配对串）", Toast.LENGTH_LONG).show()
                    }) { Text("重置密码") }
                }
                Text(
                    "密码: $pw",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    @Composable
    private fun LogCard() {
        val ctx = LocalContext.current
        var expanded by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("") }
        val scroll = rememberScrollState()

        LaunchedEffect(expanded) {
            while (expanded) {
                text = try {
                    GatewayConnection.logs() ?: "(网关未绑定：先开始会话)"
                } catch (t: Throwable) {
                    "(网关已断开连接)"
                }
                delay(1000)
            }
        }
        LaunchedEffect(text) {
            if (expanded) scroll.animateScrollTo(scroll.maxValue)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("实时日志", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Button(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起" else "展开")
                    }
                }
                if (expanded) {
                    Text(
                        text.ifBlank { "(空)" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .verticalScroll(scroll)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyText(ctx, "log", text) }) { Text("复制") }
                        OutlinedButton(onClick = {
                            val msg = exportLog(ctx, text)
                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        }) { Text("导出") }
                        OutlinedButton(onClick = {
                            runCatching { GatewayConnection.clearLogs() }
                            Toast.makeText(ctx, "已清除", Toast.LENGTH_SHORT).show()
                        }) { Text("清除") }
                    }
                }
            }
        }
    }

    private fun exportLog(ctx: Context, text: String): String {
        val name = "vibeadb-log-" +
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date()) + ".txt"
        return if (Build.VERSION.SDK_INT >= 29) {
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = ctx.contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv
            )
            ctx.contentResolver.openOutputStream(uri!!)!!.use { it.write(text.toByteArray()) }
            "已导出到 下载/$name"
        } else {
            val f = File(ctx.getExternalFilesDir(null), name)
            f.writeText(text)
            "已导出到 ${f.absolutePath}"
        }
    }

    private fun copyText(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
    }
}

private fun shizukuStatus(): String = try {
    when {
        !Shizuku.pingBinder() -> "Shizuku 未运行"
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "已授权"
        else -> "未授权"
    }
} catch (t: Throwable) {
    "不可用（未安装 Shizuku?）"
}
