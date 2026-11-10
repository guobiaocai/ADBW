# vibeADB 架构设计（v3 · 边缘中继版）

> 定位：个人开发者让 **AI Agent** 对真机/模拟器执行**短时** ADB 级测试操作（装/卸应用、启动 Activity、注入输入、截图、取 UI 层级、抓日志）。免 root（Shizuku/adb 启动，Sui 路径支持 root）。
>
> v3（即 v2 实现）核心变更：**Durable Object 边缘中继 + Android 出站 WebSocket + 原生 MCP**。剔除 cloudflared JNI 依赖（通吃真机与 x86 模拟器），剔除 URL 信箱（中继域名恒定），Agent 侧改为 MCP 工具直调。

## 0. 设计原则

1. **会话制**：开始会话 → 干活 → 结束。出站连接自带重连退避，不做保活、开机自启。
2. **出站即通**：手机**主动连出**到边缘中继（NAT 友好，无需任何入站隧道/公网 IP）；中继域名恒定 → 无 URL 轮换 → 无信箱无心跳。
3. **边缘最小信任**：DO 是透明配对管道；密码端到端由手机校验，边缘不接触秘密与业务内容。
4. **Agent 原生化**：MCP 工具直调真机能力，模型无需拼命令行。

## 1. 总体架构

```
 AI Agent（Claude Code / Cursor / 任何 MCP 客户端）
   │  MCP 工具调用（screenshot / tap / install_apk / shell / …）
   ▼
 vibeADB MCP 服务器（本机 node 进程，stdio）
   │  ② WSS client 腿：wss://<relay>/connect?deviceId=X
   ▼
 Cloudflare Durable Object「边缘中继」（恒定域名，每 deviceId 一实例）
   ▲  透明转发帧（文本/二进制），不接触密码
   │  ③ WSS device 腿：wss://<relay>/device（App 出站连接，断线自动重连）
 Android 手机 / 模拟器
   └─ App：Shizuku UserService 网关（shell/root UID）→ 执行 JSON-RPC 命令
```

- 配对串 `vibeadb://<relay-host>/<deviceId>#<password>` **一次配置永久有效**。
- **EasyTier 模式（可选，内置，免 root）**：APK 内置 `easytier-core`（aarch64 静态 musl）。`no_tun + smoltcp`，不创建 TUN。WS 只绑 127.0.0.1，`port_forward` 把 0.0.0.0:port 转到本机 WS。PC 开 TUN 连虚拟 IP:port，或同网段连手机物理 IP:port。配对串 `vibeadb://et/<ip>:<port>#<password>`。
- 会话 = App 内点「开始会话」（建立出站连接）；停止 = 断开出站连接。
- 无 cloudflared、无 URL 信箱、无心跳。

## 2. 组件职责

| 组件 | 职责 | 关键点 |
|---|---|---|
| Android App | 会话启停、Shizuku 授权引导、**出站** WS 客户端（自动重连退避）、配对串展示 | 前台服务仅存活于会话期间；纯原生 Java WebSocket，通吃真机与模拟器 |
| UserService 网关 | 持有出站连接 + 端到端鉴权 + JSON-RPC 命令执行 | 以 shell/root UID 运行；Dispatcher 与 v1 相同（传输无关） |
| 边缘中继（DO） | 按 deviceId 配对两条腿，透明转发帧；latest-wins 防占坑 | ~100 行 TS；Hibernation API；不存储不解析 |
| MCP 服务器 | 把真机能力暴露为 MCP 工具；截图直接返回图片给模型 | stdio 传输；每工具独立建连 |

## 3. Shizuku 关键判定（沿用 v1 调研结论）

- **Shizuku ≠ adbd**：以 adb（shell）身份执行操作，非真 adbd 协议。
- 免 root 身份 **UID 2000 / `u:r:shell:s0`**；Sui（Magisk）时 UID 0。
- UserService 首选：独立进程、无 non-SDK 限制、可开网络连接；`destroy`（transaction `16777115`）清理并 `System.exit()`。
- UserService 进程限制：`registerReceiver`、`getContentResolver` 等不可用。

## 4. 能力边界

**支持（首版命令面，均验证为 shell 身份可行）：**

| 类别 | 命令 |
|---|---|
| 包管理 | `pm install / uninstall / list / grant`（APK 经 WS 分块上传，`pm install -S` 流式安装） |
| 应用控制 | `am start / stop / force-stop / broadcast` |
| 输入注入 | `input tap / swipe / text / keyevent` |
| 截图 | `screencap`（FLAG_SECURE 安全窗口黑屏，需容忍） |
| UI 层级 | `uiautomator dump` |
| 日志 | `logcat`（流式订阅 / MCP 侧 tail） |
| 状态 | `settings / dumpsys / getprop` |
| 通用 | `shell`（任意命令） |

**不支持 / 不承诺：** 真 adbd、scrcpy 视频流、交互式 tty、root-only 操作、程序化开启无线调试。命令可用性受 Android 版本 / OEM / SELinux 影响 → 测试矩阵（见 ROADMAP 风险表）。

## 5. 安全模型（两条 + 一条信任备注）

1. **端到端握手鉴权**：client 的 auth 帧经中继**原样转发**，由手机校验密码（≥32 字符高熵，App 生成，仅存手机与 Agent 侧）。**边缘中继永远接触不到密码**。域名+deviceId 泄露 ≠ 可连接。
2. **出站连接**：手机只出不进，无需公网 IP；无本地监听端口（除进程内）。
3. **信任备注**：中继与隧道（Cloudflare）可见"有加密流量在流动"但不可见内容与密码；deviceId 泄露最坏后果 = device 腿被占坑（DoS，latest-wins 可被真机顶替）+ 截获 client auth 帧（密码）。deviceId 勿外传。

**明确不做**：边缘限流、边缘存储、密码哈希/轮换、审计、CF Access。理由：短会话 + 高熵密码 + 端到端鉴权下，边缘无秘密可保护。

## 6. 免费额度

- Workers/DO 免费档（SQLite DO）：~100k requests/day，每条 WS 消息计 1 request。个人短时测试远低于限额。
- 无 KV、无 secret、无 cron——部署即 `wrangler deploy` 一条命令。

## 7. 仓库结构

```
vibeADB/
├── android/     # Kotlin app：会话 UI + Shizuku 授权引导 + 出站中继客户端
│   └── gateway/ # UserService：Dispatcher（命令面实现，传输无关）
├── relay/       # Durable Object 边缘中继（~100 行 TS）
├── mcp/         # MCP 服务器（原生工具层）
├── protocol/    # 三端共享协议契约（PROTOCOL.md v2）
└── docs/        # 架构 / 路线图
```

## 8. 与 v1 的对比（为什么改）

| 维度 | v1（quick tunnel + 信箱） | v3（DO 中继 + MCP） |
|---|---|---|
| 入站方式 | cloudflared 入站隧道（JNI/asset 二进制 ~30MB） | 出站 WebSocket（纯原生，0 依赖） |
| 模拟器支持 | ✗（仅 arm64 真机） | ✓（任何能跑 App 的设备） |
| Agent 接入 | Go CLI 拼命令行 | MCP 原生工具，截图直接进模型上下文 |
| URL 稳定性 | 随机域名+轮换 → 需要信箱 | 恒定域名 → 无信箱无心跳 |
| 部署件 | Worker+KV+secret | 单 Worker+DO，一条命令 |
| 边缘信任 | Worker 存域名（无密码） | DO 透传（无任何存储） |

v1 完整实现保留在 `v1` 分支。
