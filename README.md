# ADBW

> GitHub: [guobiaocai/ADBW](https://github.com/guobiaocai/ADBW) · Author: **guobiaocai** · App: **vibeADB**

AI Agent 通过**原生 MCP 工具**对真机/模拟器执行 ADB 级测试操作（装/卸应用、启动 Activity、注入输入、截屏、UI 层级、日志）。免 root（Shizuku）。

两种通道：

| 模式 | 说明 | 适用 |
|---|---|---|
| **边缘中继**（默认） | 手机出站 WebSocket 连 Cloudflare DO 恒定域名，无需公网 IP | 跨网，开箱即用 |
| **EasyTier**（内置，免 root） | APK 内置 `easytier-core`（aarch64），`no_tun + port_forward`，WS 只听 127.0.0.1 | 同网 / PC 开 TUN 直连虚拟 IP |

架构：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · 协议：[protocol/PROTOCOL.md](protocol/PROTOCOL.md)

## 组件

| 目录 | 内容 |
|---|---|
| `android/` | Kotlin+Compose App：Shizuku UserService 网关 + 出站 WS / 内置 EasyTier |
| `relay/` | Cloudflare Durable Object 边缘中继：按 deviceId 配对两腿，透明转发，不接触密码 |
| `mcp/` | MCP 服务器：`screenshot / tap / install_apk / shell / ui_dump / logcat_tail / …` |
| `protocol/` | 三端共享协议契约 |

## 上手

### 边缘中继（默认）

1. 部署中继（一次性）：`cd relay && npm i && npx wrangler deploy` → 恒定域名，见 [relay/DEPLOY.md](relay/DEPLOY.md)。
2. 手机装 Release APK → 装 [Shizuku](https://shizuku.rikka.app/) 并启动 → 设置页填中继地址 → 开始会话 → 复制配对串。
3. Agent（Claude Code）：
   ```json
   { "mcpServers": { "vibeadb": {
       "command": "node",
       "args": ["/path/adbw-mcp/dist/index.js"],
       "env": { "VIBEADB_PAIRING": "vibeadb://<relay-host>/<deviceId>#<password>" }
   } } }
   ```

### EasyTier（可选）

1. 设置页切到 EasyTier，填网络名/密钥（密钥留空 = 配对密码），开始会话。
2. PC 跑同网络：`easytier-core --network-name vibeadb --network-secret <密钥>`（Windows GUI 或 CLI，开 TUN）。
3. 会话卡片出现 `vibeadb://et/<虚拟IP>:<port>#<password>` 后填给 MCP。
4. 同局域网也可直接连 `手机IP:端口`。

内置 `easytier-core` 仅 arm64；不创建 TUN、不需要 root。跨网且 PC 没开 TUN 时请用中继模式。

## CI / Release

- push 到 `main`：`ci.yml` 跑 android 单测 + assembleRelease、relay/mcp 测试。
- tag `v*`：`release.yml` 产出 **APK / relay zip / mcp zip** 到 GitHub Release。
- Secrets（保证 APK 签名一致，支持非卸载升级）：
  - `ANDROID_KEYSTORE_B64`
  - `ANDROID_STORE_PASSWORD`
  - `ANDROID_KEY_PASSWORD`
  - `ANDROID_KEY_ALIAS`
- 本地 keystore 备份：`android/keystore/`（已 gitignore，勿提交）与仓库外 `../vibeadb-signing/`。

## 安全模型

- 密码端到端：client 的 auth 帧经中继**原样转发**，由手机校验；边缘不接触密码与业务内容。
- 中继模式手机只出不进；EasyTier 模式 WS 只听 127.0.0.1，经 port_forward 转出。
- 配对串 = 域名/虚拟IP + deviceId（128 位随机）+ 高熵密码。deviceId 勿外传。

## 已知限制

- DO 免费档 ~100k requests/day（每条 WS 消息计 1 request）。
- 中继模式同时 1 条 client 腿；EasyTier 模式允许多个已鉴权 client 并存。
- MCP 与 APK 须在同一台机器（`install_apk` 读本地路径）。
- 内置 easytier-core 仅 arm64。
