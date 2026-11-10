# vibeADB 协议 v2（边缘中继版）

> v2 变更核心：传输层从"quick tunnel 入站 + URL 信箱"改为"**Durable Object 边缘中继 + Android 出站 WebSocket**"——中继域名恒定、无 URL 轮换、无信箱、无 cloudflared。
> **端到端协议（鉴权/JSON-RPC/二进制分块）与 v1 完全一致**，仅传输与发现层变化。本文件是 Android 网关 / MCP 服务器 / Relay 三端的唯一契约。

## 1. 配对串（Pairing String）

```
vibeadb://<relay-host>/<deviceId>#<password>
```

- `<relay-host>`：边缘中继域名（如 `adbw-relay.xxx.workers.dev`），**恒定不变**。
- `<deviceId>`：App 生成的 32 字符小写 hex（128 bit），中继的实例地址 + 读取凭证。
- `<password>`：App 生成的 32 字符 base64url（24 随机字节），端到端凭证——**中继不接触**。
- 配对串**永久有效**（无 URL 轮换），一次配置长期使用。
- **EasyTier 直连**（可选模式）：`vibeadb://et/<host>:<port>#<password>`。
  手机在 EasyTier 虚拟网内监听 `ws://<host>:<port>`，MCP 直连，不经 Cloudflare 中继。
  协议层（auth / JSON-RPC / 二进制分块）与中继模式完全一致；EasyTier 负责加密与 NAT 穿越，密码仍由手机端到端校验。
  允许多个已鉴权 client 并存。

## 2. 边缘中继（Durable Object）

每个 deviceId 一个 DO 实例（`idFromName`）。DO 只做**配对管道**：透明转发帧，不解析业务协议，不存储任何数据。

### 路由

| 路由 | 用途 |
|---|---|
| `GET /health` | 连通性检查 |
| `GET /device`（header `X-Device-Id: <id>`） | 手机出站 WebSocket（device 腿，WS 升级） |
| `GET /connect?deviceId=<id>` | client 腿 WebSocket（MCP 服务器等，WS 升级） |

### 配对与生命周期

- **device 腿 latest-wins**：新 device 连接替换旧连接（4005 关闭旧腿），防 deviceId 泄露后被永久占坑。
- **client 腿 latest-wins**：同时只允许一条 client（新替换旧，4006）。
- **device 不在线时 client 直接被拒**：升级阶段返回 503 `{"error":"device offline"}`。
- **边缘控制帧**（DO 生成，两端自行处理/忽略）：
  `{"op":"edge","event":"paired"|"client_gone"|"device_gone"}`
- Hibernation API：空闲时 DO 不计费时长，WebSocket 断连由两端负责（手机侧自动重连退避）。

### 信任边界（重要）

- client 的 auth 帧**原样转发给手机，由手机校验密码**——边缘永远接触不到密码与业务流量内容。
- deviceId 泄露的最坏后果是 device 腿被占坑（DoS，可被真实手机重连顶替）+ 收到 client 的 auth 帧（密码泄露）。deviceId 只存在手机与 Agent 侧，勿外传。

## 3. 端到端协议（与 v1 相同，phone ↔ client 经 DO 透明管道）

### 3.1 鉴权（client 腿建连后第一帧，15s 内）

```json
→ {"op": "auth", "password": "<password>"}
← {"op": "auth", "ok": true}
```

失败：`← {"op":"auth","ok":false,"error":"bad password"}` + 4001 关闭。未鉴权收帧 → 4001。鉴权超时 → 4002。

### 3.2 JSON-RPC（文本帧）

```json
→ {"jsonrpc": "2.0", "id": 1, "method": "shell", "params": {"command": "echo hi"}}
← {"jsonrpc": "2.0", "id": 1, "result": {"exitCode": 0, "stdout": "hi\n", "stderr": ""}}
← {"jsonrpc": "2.0", "id": 1, "error": {"code": -32000, "message": "..."}}
```

### 3.3 二进制帧

```
[ 4 字节 big-endian uint32：请求 id ][ payload ]
```

服务端→客户端：流式输出（shell stream/logcat/screencap PNG）。客户端→服务端：pm.install 的 APK 分块（建议 64KB/块）。每请求：`0..N 二进制帧 → 1 JSON 响应帧（终结）`。

### 3.4 eod（上传结束符）

```json
→ {"op": "eod", "id": 2}
```

### 3.5 超时

空闲 5 分钟无帧 → 连接关闭（4000，由手机侧执行）。命令级超时见方法表。

## 4. JSON-RPC 方法表

| method | params | 二进制块 | result |
|---|---|---|---|
| `ping` | `{}` | - | `{"pong": true}` |
| `shell` | `{command, stream?, timeoutSec?}` | stream=true 服务端推块 | stream: `{exitCode}`；否则 `{exitCode, stdout, stderr}` |
| `screencap` | `{}` | 服务端推 1 块（PNG） | `{"size": n}` |
| `ui.dump` | `{}` | - | `{"xml": "..."}` |
| `input` | `{kind: "tap"\|"swipe"\|"text"\|"keyevent", x?, y?, x2?, y2?, durationMs?, text?, keyCode?}` | - | `{exitCode, output}` |
| `pm.install` | `{size}` | 客户端传 APK 块 + eod | `{exitCode, output}` |
| `pm.uninstall` | `{package}` | - | `{exitCode, output}` |
| `pm.list` | `{}` | - | `{output}` |
| `logcat` | `{}` | 服务端持续推块 | 客户端断开即停止 |
| `shell`（通用） | 任意命令经 `sh -c` | | 覆盖上表未列场景 |

错误码：-32700 解析失败 / -32600 请求不合法 / -32601 未知方法 / -32602 参数不合法 / -32000 执行失败。
WS 关闭码：4000 空闲超时，4001 鉴权失败，4002 鉴权超时，4004 设备不在线/断开，4005 设备腿被替换，4006 client 腿被替换。

## 5. MCP 工具层（mcp/）

MCP 服务器为每个工具调用独立建连（connect → auth → call → close）。工具与 RPC 方法的映射：

| MCP 工具 | RPC |
|---|---|
| `device_status` | `ping` |
| `shell` | `shell` |
| `screenshot` | `screencap`（PNG 以 MCP image 内容直接返回给模型） |
| `ui_dump` | `ui.dump` |
| `tap` / `swipe` / `text_input` / `key` | `input` |
| `install_apk` | `pm.install`（读本机文件上传） |
| `uninstall` / `packages` | `pm.uninstall` / `pm.list` |
| `logcat_tail` | `shell logcat -d -t N` |
| `am_start` | `shell am start` |

## 6. 额度（免费计划）

- Workers/DO 免费档（SQLite DO）：~100k requests/day；**每条 WS 消息计 1 request**。
- 个人短时测试会话（每秒几条帧、每天几小时）远低于限额；控制台可观测。
- 中继域名恒定 → 无 URL 轮换 → 无信箱 → 无心跳。
