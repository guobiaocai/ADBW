#!/usr/bin/env node
/**
 * vibeADB MCP 服务器
 *
 * 让 AI Agent（Claude Code / Cursor 等）通过原生 MCP 工具直接调用真机能力：
 * 连接边缘中继（client 腿）→ 端到端密码鉴权（手机校验）→ JSON-RPC。
 * 每个工具调用独立建连，简单可靠；协议见 protocol/PROTOCOL.md。
 *
 * 配置：环境变量 ADBW_PAIRING / VIBEADB_PAIRING（或 --pairing 参数）= vibeadb://<relay-host>/<deviceId>#<password>
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { DeviceClient } from "./client.js";
import { parsePairing } from "./pairing.js";

const TEXT_LIMIT = 60_000;

function argValue(name: string): string | undefined {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

const rawPairing = process.env.ADBW_PAIRING ?? process.env.VIBEADB_PAIRING ?? argValue("--pairing");
if (!rawPairing) {
  console.error("缺少配对串：设置 VIBEADB_PAIRING 或 --pairing（vibeadb://<relay-host>/<deviceId>#<password>）");
  process.exit(2);
}
const target = parsePairing(rawPairing);

function text(s: string) {
  const clipped = s.length > TEXT_LIMIT ? s.slice(0, TEXT_LIMIT) + "\n…[已截断]" : s;
  return { content: [{ type: "text" as const, text: clipped }] };
}

function errText(e: unknown) {
  const msg = e instanceof Error ? e.message : String(e);
  return { content: [{ type: "text" as const, text: `错误：${msg}` }], isError: true as const };
}

async function withDevice<T>(fn: (c: DeviceClient) => Promise<T>): Promise<T> {
  const c = await DeviceClient.connect(target);
  try {
    return await fn(c);
  } finally {
    c.close();
  }
}

function execSummary(r: any): string {
  const parts: string[] = [];
  if (r?.stdout) parts.push(String(r.stdout));
  if (r?.output && !r?.stdout) parts.push(String(r.output));
  if (r?.stderr) parts.push(`[stderr]\n${r.stderr}`);
  parts.push(`exit=${r?.exitCode ?? "?"}`);
  return parts.join("\n");
}

const server = new McpServer({ name: "adbw", version: "2.3.1" });

server.registerTool(
  "device_status",
  {
    description: "检查目标手机是否在线（App 会话是否运行、中继是否配对成功）",
    inputSchema: {},
  },
  async () => {
    try {
      await withDevice((c) => c.call("ping", {}));
      return text("online：手机已连接中继，可以调用其他工具");
    } catch (e) {
      return text(`offline：${e instanceof Error ? e.message : String(e)}`);
    }
  },
);

server.registerTool(
  "shell",
  {
    description: "在真机上执行 shell 命令（等价 adb shell，以 shell UID 运行；支持管道）",
    inputSchema: {
      command: z.string().describe("要执行的命令，如 pm list packages -3"),
      timeoutSec: z.number().int().min(1).max(1800).optional().describe("超时秒数，默认 60"),
    },
  },
  async ({ command, timeoutSec }) => {
    try {
      const r = await withDevice((c) => c.call("shell", { command, timeoutSec }));
      return text(execSummary(r));
    } catch (e) {
      return errText(e);
    }
  },
);

server.registerTool(
  "screenshot",
  {
    description: "截取真机当前屏幕，直接返回 PNG 图片（供模型查看）",
    inputSchema: {
      path: z.string().optional().describe("可选：同时把 PNG 保存到该本地路径"),
    },
  },
  async ({ path }) => {
    try {
      const chunks: Buffer[] = [];
      await withDevice((c) => c.call("screencap", {}, { onChunk: (b) => chunks.push(b) }));
      const png = Buffer.concat(chunks);
      if (path) {
        const { writeFileSync } = await import("node:fs");
        writeFileSync(path, png);
      }
      return {
        content: [
          { type: "image" as const, data: png.toString("base64"), mimeType: "image/png" },
          ...(path ? [{ type: "text" as const, text: `已保存到 ${path}（${png.length} bytes）` }] : []),
        ],
      };
    } catch (e) {
      return errText(e);
    }
  },
);

server.registerTool(
  "ui_dump",
  {
    description: "获取当前界面 UI 层级（uiautomator dump 的 XML），用于分析控件坐标",
    inputSchema: {
      path: z.string().optional().describe("可选：同时把 XML 保存到该本地路径"),
    },
  },
  async ({ path }) => {
    try {
      const r = await withDevice((c) => c.call("ui.dump", {}));
      if (path) {
        const { writeFileSync } = await import("node:fs");
        writeFileSync(path, String(r.xml));
      }
      return text(String(r.xml));
    } catch (e) {
      return errText(e);
    }
  },
);

server.registerTool(
  "tap",
  {
    description: "点击屏幕坐标",
    inputSchema: { x: z.number().int(), y: z.number().int() },
  },
  async ({ x, y }) => {
    try {
      const r = await withDevice((c) => c.call("input", { kind: "tap", x, y }));
      return text(execSummary(r));
    } catch (e) {
      return errText(e);
    }
  },
);

server.registerTool(
  "swipe",
  {
    description: "从 (x1,y1) 滑动到 (x2,y2)",
    inputSchema: {
      x1: z.number().int(),
      y1: z.number().int(),
      x2: z.number().int(),
      y2: z.number().int(),
      durationMs: z.number().int().optional().describe("默认 300ms"),
    },
  },
  async (p) => {
    try {
      const r = await withDevice((c) => c.call("input", { kind: "swipe", ...p }));
      return text(execSummary(r));
    } catch (e) {
      return errText(e);
    }
  },
);

server.registerTool(
  "text_input",
  {
    description: "向当前焦点输入框输入文本",
    inputSchema: { text: z.string() },
  },
  async ({ text: s }) => {
    try {
      const r = await withDevice((c) => c.call("input", { kind: "text", text: s }));
      return text(execSummary(r));
    } catch (e) {
      return errText(e);
    }
  },
);

server.registerTool(
  "key",
  {
    description: "发送按键事件（如 4=BACK, 3=HOME, 82=MENU）",
    inputSchema: { keyCode: z.number().int() },
  },
  async ({ keyCode }) => {
    try {
      const r = await withDevice((c) => c.call("input", { kind: "keyevent", keyCode }));
      return text(execSummary(r));
    } catch (e) {
      return errText(e);
    }
  },
);

server.registerTool(
  "install_apk",
  {
    description: "把本地 APK 安装到真机（pm install -S 流式安装）",
    inputSchema: { path: z.string().describe("本机 APK 文件路径") },
  },
  async ({ path }) => {
    try {
      const { readFileSync } = await import("node:fs");
      const data = readFileSync(path);
      const r = await withDevice((c) => c.call("pm.install", { size: data.length }, { upload: data }));
      return text(execSummary(r));
    } catch (e) {
      return errText(e);
    }
  },
);

server.registerTool(
  "uninstall",
  {
    description: "卸载应用",
    inputSchema: { package: z.string() },
  },
  async ({ package: pkg }) => {
    try {
      const r = await withDevice((c) => c.call("pm.uninstall", { package: pkg }));
      return text(execSummary(r));
    } catch (e) {
      return errText(e);
    }
  },
);

server.registerTool(
  "packages",
  {
    description: "列出已安装的第三方应用",
    inputSchema: {},
  },
  async () => {
    try {
      const r = await withDevice((c) => c.call("pm.list", {}));
      return text(execSummary(r));
    } catch (e) {
      return errText(e);
    }
  },
);

server.registerTool(
  "logcat_tail",
  {
    description: "抓取最近的 logcat 日志（非流式）",
    inputSchema: {
      lines: z.number().int().min(1).max(5000).optional().describe("条数，默认 300"),
      grep: z.string().optional().describe("只保留包含该子串的行"),
    },
  },
  async ({ lines = 300, grep }) => {
    try {
      const r = await withDevice((c) =>
        c.call("shell", { command: `logcat -d -t ${lines} -v time`, timeoutSec: 30 }),
      );
      let out = String(r.stdout ?? "");
      if (grep) out = out.split("\n").filter((l) => l.includes(grep)).join("\n");
      return text(out || "(无日志)");
    } catch (e) {
      return errText(e);
    }
  },
);

server.registerTool(
  "am_start",
  {
    description: "启动一个 Activity（am start -n pkg/.Activity 或意图 URI）",
    inputSchema: { componentOrUri: z.string().describe("如 com.example/.MainActivity 或 market://...") },
  },
  async ({ componentOrUri }) => {
    try {
      const arg = componentOrUri.includes("://")
        ? `-a android.intent.action.VIEW -d "${componentOrUri}"`
        : `-n "${componentOrUri}"`;
      const r = await withDevice((c) => c.call("shell", { command: `am start ${arg}` }));
      return text(execSummary(r));
    } catch (e) {
      return errText(e);
    }
  },
);

async function main(): Promise<void> {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error(`[adbw-mcp] 已启动，目标 ${target.relayHost}/${target.deviceId}`);
}

main().catch((e) => {
  console.error("[adbw-mcp] 启动失败:", e);
  process.exit(1);
});
