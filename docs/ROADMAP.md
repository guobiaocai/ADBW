# vibeADB 开发计划（v3 · 边缘中继版）

> 预估单人 ~3 周（v1 已完成网关/Dispatcher 的核心调研与实现，v3 复用其协议层）。每个里程碑有**验收标准**。
> v1（quick tunnel + 信箱 + Go CLI）完整保留在 `v1` 分支。

## 技术选型

| 模块 | 选型 | 理由 |
|---|---|---|
| Android app | Kotlin + Compose | 同 v1 |
| Shizuku 集成 | `dev.rikka.shizuku:api/provider` 13.1.5 | 同 v1 |
| 出站连接 | Java-WebSocket `WebSocketClient`（已有依赖） | 纯 Java、支持 wss+自定义 header，0 新依赖 |
| 边缘中继 | Cloudflare Durable Object（SQLite，免费档）+ Hibernation API | 恒定域名、免费、~100 行 |
| Agent 接入 | TypeScript MCP 服务器（`@modelcontextprotocol/sdk` + `ws`） | 原生工具直调；截图直接进模型上下文 |
| 测试 | vitest（relay/mcp 纯逻辑层）+ JVM 单测（android） | 全部跑在 Actions |

## M1 — 边缘中继 DO（2~3 天）

**交付物**（`relay/`）：
- DO：device/client 两腿配对管道（透明转发、latest-wins、离线拒绝、边缘控制帧、Hibernation）。
- Worker 路由：`/health`、`/device`、`/connect`；deviceId 校验（16-64 hex）。
- vitest：配对/转发/替换/断开通知全流程（fake state + fake WS）。

**验收标准**：本地测试全绿；`wrangler deploy --dry-run` 通过；`wrangler deploy` 后 `/health` 可达。

## M2 — Android 出站改造（3~5 天）

**交付物**（`android/`）：
- 删除 cloudflared 管理、jniLibs、信箱客户端、UrlParser。
- `RelayTunnelClient`：出站 wss + 自定义 header + wss socketFactory；auth 端到端校验；断线重连退避（2s→30s）。
- Dispatcher 复用（传输无关，v1 直接沿用）；AIDL 增加 relayHost/deviceId 参数与 status()（idle/connecting/online/retrying）。
- UI：设置页配对串常显（永久有效）；会话状态轮询展示中继连接状态。

**验收标准**：Actions 全绿（JVM 单测 + assembleRelease）；真机上开始会话 → App 显示"已连接中继"→ MCP `device_status` 返回 online。

## M3 — MCP 服务器（3~5 天）

**交付物**（`mcp/`）：
- `DeviceClient`：client 腿连接 + auth + JSON-RPC + 二进制分块（上传/下载）。
- 工具：`device_status / shell / screenshot（返回图片）/ ui_dump / tap / swipe / text_input / key / install_apk / uninstall / packages / logcat_tail / am_start`。
- vitest：配对解析、auth、分块往返、上传 eod、错误密码。

**验收标准**：测试全绿；在 Claude Code 中配置后完成一轮"install_apk → am_start → screenshot（模型看到截图）→ tap → logcat_tail"。

## M4 — 文档与发布（1~2 天）

- PROTOCOL.md v2、ARCHITECTURE v3、README（部署中继 → App 配置 → MCP 配置三步走）。
- CI：android / relay / mcp 三 job；release.yml 产出 APK + relay zip + mcp zip。

**验收标准**：CI 全绿；tag `v2.0.0` → Release 含三产物；从零跟随 README 可用 Claude Code 完成端到端测试循环。

## 明确不进 v3（Deferred）

| 项 | 说明 | 触发条件 |
|---|---|---|
| 多 client 腿并发复用 | 当前 1 device + 1 client；多 Agent 并行需会话复用/多路复用 | 出现真实需求 |
| 边缘限流 / 配额观测 | DO 免费档 100k req/day 个人够用 | 多人共用或被滥用 |
| Go CLI（v1 遗产） | 保留在 v1 分支；MCP 覆盖绝大多数 Agent 场景 | 需要 CLI 场景 |
| 保活 / 开机自启 | 会话制不需要 | 服务变长驻 |
| scrcpy 视频流 / 真 adbd / tty | 同 v1 | 刚需出现 |

## 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| DO 免费额度（每条 WS 消息 1 request） | 重度使用逼近 100k/day | 个人用量可控；控制台观测；必要时限流 |
| UserService 进程出站连接被 SELinux 限制 | M2 阻塞 | shell 域具备完整网络能力（同 v1 论证）；真机验证前置 |
| Hibernation 后 attachment/tags 语义 | DO 配对状态丢失 | tags 存于 acceptWebSocket；测试覆盖 |
| MCP SDK API 变动 | mcp 编译失败 | 锁定 ^1.12；registerTool API |
| 个别命令 OEM/SELinux 差异 | 命令面不确定性 | ≥2 台真机矩阵；明确错误码 |

## 依赖顺序

```
M1（relay）─┬─► M2（android）─┬─► M4（文档/发布）
            └─► M3（mcp）────┘
```
M2 与 M3 可并行（协议已冻结）。每里程碑打 tag，主分支保持可构建。

## 决策记录（v3 相对 v2 的变更）

1. **剔除 cloudflared**：入站隧道改为出站 WebSocket——省 30MB 二进制、JNI/exec 兼容性问题，模拟器可用。
2. **剔除 URL 信箱（worker/）**：中继域名恒定，无 URL 轮换 → 信箱失去存在意义；KV/secret/心跳全免。
3. **Agent 侧从 Go CLI 改为原生 MCP**：模型直接调工具，截图以内联图片进入模型上下文，测试闭环更紧。Go CLI 保留在 v1 分支。
4. **边缘中继零信任化**：密码端到端（手机校验），DO 无存储无解析——优于 v1 的"信箱存域名"模型。
