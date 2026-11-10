# vibeADB MCP 服务器

AI Agent（Claude Code / Cursor / 任何 MCP 客户端）通过原生 MCP 工具直接调用真机能力。
连接边缘中继的 client 腿，密码端到端由手机校验，边缘不接触秘密。

## 构建

```bash
cd mcp
npm install && npm run build   # 产物在 dist/index.js
```

（或直接下载 Release 里的 `adbw-mcp-*.zip`，已含构建产物。）

## 配置（Claude Code 示例）

```json
{
  "mcpServers": {
    "vibeadb": {
      "command": "node",
      "args": ["/path/to/dist/index.js"],
      "env": {
        "VIBEADB_PAIRING": "vibeadb://<relay-host>/<deviceId>#<password>"
      }
    }
  }
}
```

配对串从手机 App 设置页复制，**永久有效**。

## 工具一览

| 工具 | 说明 |
|---|---|
| `device_status` | 手机是否在线 |
| `shell` | 执行 shell 命令（shell UID，支持管道） |
| `screenshot` | 截屏，直接返回 PNG 图片给模型 |
| `ui_dump` | uiautomator dump 的 XML（分析控件坐标） |
| `tap` / `swipe` / `text_input` / `key` | 输入注入 |
| `install_apk` | 上传并安装本机 APK |
| `uninstall` / `packages` | 卸载 / 列出第三方应用 |
| `logcat_tail` | 最近 N 行日志（可过滤） |
| `am_start` | 启动 Activity / 意图 URI |

典型测试循环：`packages` → `install_apk` → `am_start` → `screenshot`（看图）→ `ui_dump`（找坐标）→ `tap` → `logcat_tail`。
