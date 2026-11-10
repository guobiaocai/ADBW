/**
 * DeviceClient：client 腿 WebSocket 客户端。
 * 连边缘中继 → auth（端到端，由手机校验）→ JSON-RPC + 二进制分块（PROTOCOL.md §3-§4）。
 */
import WebSocket from "ws";
import { connectUrl, type PairingTarget } from "./pairing.js";

export class RpcError extends Error {
  constructor(
    public readonly code: number,
    message: string,
  ) {
    super(`rpc ${code}: ${message}`);
  }
}

export interface CallOpts {
  /** 服务端推送的二进制块（PNG/APK 分块） */
  onChunk?: (data: Buffer) => void;
  /** 客户端上传数据（pm.install 的 APK），自动分块 + eod */
  upload?: Buffer;
  timeoutMs?: number;
}

export class DeviceClient {
  private ws: WebSocket;
  private nextId = 0;

  private constructor(ws: WebSocket) {
    this.ws = ws;
  }

  static async connect(t: PairingTarget, timeoutMs = 15_000): Promise<DeviceClient> {
    const ws = new WebSocket(connectUrl(t));
    await waitEvent(ws, "open", timeoutMs, "connect");

    ws.send(JSON.stringify({ op: "auth", password: t.password }));
    const frame = await waitText(ws, timeoutMs, "auth");
    const parsed = JSON.parse(frame) as { ok?: boolean; error?: string };
    if (!parsed.ok) {
      ws.close();
      throw new Error(`auth failed: ${parsed.error ?? "bad password"}`);
    }
    return new DeviceClient(ws);
  }

  /** 执行一次 JSON-RPC 调用并等待终结响应 */
  async call(method: string, params: unknown, opts: CallOpts = {}): Promise<any> {
    const id = ++this.nextId;
    this.ws.send(JSON.stringify({ jsonrpc: "2.0", id, method, params }));

    if (opts.upload) {
      sendChunks(this.ws, id, opts.upload);
      this.ws.send(JSON.stringify({ op: "eod", id }));
    }

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        cleanup();
        reject(new Error(`${method} 超时（${opts.timeoutMs ?? 120_000}ms）`));
      }, opts.timeoutMs ?? 120_000);

      const onMessage = (data: Buffer, isBinary: boolean) => {
        if (isBinary) {
          if (data.length < 4) return;
          if (data.readUInt32BE(0) !== id) return;
          opts.onChunk?.(data.subarray(4));
          return;
        }
        let obj: any;
        try {
          obj = JSON.parse(data.toString());
        } catch {
          return;
        }
        if (obj.op) return; // edge / 控制帧
        if (obj.id !== id) return;
        cleanup();
        if (obj.error) reject(new RpcError(obj.error.code, obj.error.message));
        else resolve(obj.result);
      };

      const onClose = (code: number, reason: Buffer) => {
        cleanup();
        const r = reason.toString();
        reject(new Error(`连接关闭 ${code}${r ? "：" + r : ""}`));
      };

      const cleanup = () => {
        clearTimeout(timer);
        this.ws.off("message", onMessage);
        this.ws.off("close", onClose);
      };

      this.ws.on("message", onMessage);
      this.ws.on("close", onClose);
    });
  }

  close(): void {
    try {
      this.ws.close();
    } catch {
      /* ignore */
    }
  }
}

function sendChunks(ws: WebSocket, id: number, data: Buffer): void {
  const chunkSize = 64 * 1024; // 64KB：Cloudflare WS 入站单帧上限 1 MiB，1MB+4 字节 id 头必超
  const prefix = Buffer.alloc(4);
  prefix.writeUInt32BE(id, 0);
  for (let off = 0; off < data.length; off += chunkSize) {
    const end = Math.min(off + chunkSize, data.length);
    ws.send(Buffer.concat([prefix, data.subarray(off, end)]), { binary: true });
  }
}

function waitEvent(ws: WebSocket, event: "open", timeoutMs: number, what: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error(`${what} 超时（${timeoutMs}ms）`));
    }, timeoutMs);
    const onOpen = () => {
      cleanup();
      resolve();
    };
    const onError = (err: Error) => {
      cleanup();
      reject(err);
    };
    const onClose = (code: number, reason: Buffer) => {
      cleanup();
      const r = reason.toString();
      reject(new Error(`${what} 被拒（${code}${r ? "：" + r : ""}）——手机端会话未运行？`));
    };
    const cleanup = () => {
      clearTimeout(timer);
      ws.off("open", onOpen);
      ws.off("error", onError);
      ws.off("close", onClose);
    };
    ws.once("open", onOpen);
    ws.once("error", onError);
    ws.once("close", onClose);
  });
}

function waitText(ws: WebSocket, timeoutMs: number, what: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error(`${what} 超时`));
    }, timeoutMs);
    const onMessage = (data: Buffer, isBinary: boolean) => {
      if (isBinary) return;
      cleanup();
      resolve(data.toString());
    };
    const onClose = (code: number) => {
      cleanup();
      reject(new Error(`${what} 前连接关闭（${code}）`));
    };
    const cleanup = () => {
      clearTimeout(timer);
      ws.off("message", onMessage);
      ws.off("close", onClose);
    };
    ws.on("message", onMessage);
    ws.once("close", onClose);
  });
}
