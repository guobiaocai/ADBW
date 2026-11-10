/**
 * vibeADB 边缘中继（Durable Object）+ Worker 入口
 *
 * 每个 deviceId 一个 DO 实例（idFromName）。两条 WebSocket 腿：
 *   - device 腿：Android App 出站连接（/device?deviceId=X&sid=Y&epoch=Z）
 *   - client 腿：MCP 服务器 / 调试客户端连接（/connect?deviceId=X）
 * DO 只做"配对管道"：透明转发帧（文本/二进制原样），不解析、不改写业务协议。
 *
 * 【会话栅栏（Session Epoch Fencing）】：
 *   每次 App 启动新会话生成新的 (sid, epoch)。
 *   - epoch > activeEpoch：新会话上线，驱逐旧会话并记录新 epoch
 *   - epoch === activeEpoch && sid === activeSid：当前会话正常重连（如网络抖动）
 *   - 其它（旧 epoch 或不匹配的 sid）：一律 HTTP 409 拒绝！
 *   此机制彻底根除多进程/旧版僵尸互踢问题。
 */

export interface Env {
  RELAY: DurableObjectNamespace;
}

export interface WsLike {
  send(message: string | ArrayBuffer): void;
  close(code?: number, reason?: string): void;
  serializeAttachment(annotation: unknown): void;
  deserializeAttachment(): unknown;
}

type Role = "device" | "client";

const DEVICE_ID_RE = /^[a-f0-9]{16,64}$/i;

export function isValidDeviceId(id: string): boolean {
  return DEVICE_ID_RE.test(id);
}

export class Relay {
  private activeEpoch = 0;
  private activeSid = "";

  constructor(private readonly state: DurableObjectState) {}

  /** Worker 转发进来的升级请求路由 */
  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const deviceId = url.searchParams.get("deviceId") ?? "";
    if (!isValidDeviceId(deviceId)) {
      return json({ error: "invalid deviceId" }, 400);
    }

    if (url.pathname === "/online") {
      return json({ online: this.state.getWebSockets("device").length > 0 });
    }

    if (url.pathname === "/device") {
      const sid = url.searchParams.get("sid") ?? "";
      const epoch = parseInt(url.searchParams.get("epoch") ?? "0", 10);
      if (!sid || !epoch) {
        return json({ error: "stale client: missing sid/epoch, please update the app" }, 409);
      }

      // 会话栅栏判定
      if (epoch > this.activeEpoch) {
        // 新会话接管
        this.activeEpoch = epoch;
        this.activeSid = sid;
      } else if (epoch === this.activeEpoch && sid === this.activeSid) {
        // 同一会话网络重连，允许
      } else {
        // 来自旧进程/旧会话的僵尸连接，直接拒绝
        return json({ error: "stale session: rejected by epoch fence" }, 409);
      }

      const pair = new WebSocketPair();
      this.addDevice(pair[1] as unknown as WsLike, sid, epoch);
      return ws101(pair[0]);
    }

    if (url.pathname === "/connect") {
      if (this.state.getWebSockets("device").length === 0) {
        return json({ error: "device offline (手机端会话未运行)" }, 503);
      }
      const pair = new WebSocketPair();
      this.addClient(pair[1] as unknown as WsLike);
      return ws101(pair[0]);
    }

    return json({ error: "not found" }, 404);
  }

  private roleOf(ws: WsLike): Role | undefined {
    const a = ws.deserializeAttachment() as { role?: Role } | undefined;
    return a?.role;
  }

  private peers(role: Role): WsLike[] {
    const other = role === "device" ? "client" : "device";
    return this.state.getWebSockets(other) as unknown as WsLike[];
  }

  private notifySelf(role: Role, event: string): void {
    for (const ws of this.state.getWebSockets(role) as unknown as WsLike[]) {
      try {
        ws.send(JSON.stringify({ op: "edge", event }));
      } catch {
        /* ignore */
      }
    }
  }

  /** 手机出站连接（device 腿）。新合法会话接管时替换旧 socket */
  addDevice(ws: WsLike, sid: string, epoch: number): void {
    for (const old of this.state.getWebSockets("device") as unknown as WsLike[]) {
      try {
        old.close(4005, "replaced by newer session");
      } catch {
        /* ignore */
      }
    }
    this.state.acceptWebSocket(ws as unknown as WebSocket, ["device"]);
    ws.serializeAttachment({ role: "device", sid, epoch });
  }

  /** client 腿 */
  addClient(ws: WsLike): boolean {
    if (this.state.getWebSockets("device").length === 0) {
      try {
        ws.close(4004, "device offline (session not running?)");
      } catch {
        /* ignore */
      }
      return false;
    }
    for (const old of this.state.getWebSockets("client") as unknown as WsLike[]) {
      try {
        old.close(4006, "replaced by newer client connection");
      } catch {
        /* ignore */
      }
    }
    this.state.acceptWebSocket(ws as unknown as WebSocket, ["client"]);
    ws.serializeAttachment({ role: "client" });
    this.notifySelf("device", "paired");
    return true;
  }

  /** Hibernation API 运行时回调：收到帧 → 转发 */
  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    this.onMessage(ws as unknown as WsLike, message);
  }

  /** Hibernation API 运行时回调：连接关闭 → 通知对端 */
  async webSocketClose(ws: WebSocket, code: number, reason: string, wasClean: boolean): Promise<void> {
    this.onClose(ws as unknown as WsLike);
  }

  async webSocketError(ws: WebSocket, err: unknown): Promise<void> {
    /* ignore */
  }

  /** 帧转发：device<->client，文本/二进制原样透传 */
  onMessage(ws: WsLike, message: string | ArrayBuffer): void {
    const role = this.roleOf(ws);
    if (!role) {
      try {
        ws.close(4001, "unregistered socket");
      } catch {
        /* ignore */
      }
      return;
    }
    for (const peer of this.peers(role)) {
      try {
        peer.send(message);
      } catch {
        /* ignore */
      }
    }
  }

  onClose(ws: WsLike): void {
    const role = this.roleOf(ws);
    if (role === "device") {
      this.notifySelf("client", "device_gone");
      for (const c of this.peers("device")) {
        try {
          c.close(4004, "device disconnected");
        } catch {
          /* ignore */
        }
      }
    } else if (role === "client") {
      this.notifySelf("device", "client_gone");
    }
  }
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function ws101(client: WebSocket): Response {
  try {
    return new Response(null, { status: 101, webSocket: client } as any);
  } catch {
    // Node.js test environment mock (undici fetch restricts status to 200..599)
    return { status: 101, webSocket: client } as unknown as Response;
  }
}

/** Worker 入口 */
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    if (path === "/health") {
      return json({ ok: true, service: "adbw-relay" });
    }

    let deviceId = "";
    let role: "device" | "client" | "online" | null = null;
    if (path === "/device" && request.method === "GET") {
      role = "device";
      deviceId = request.headers.get("x-device-id") || (url.searchParams.get("deviceId") ?? "");
    } else if (path === "/connect" && request.method === "GET") {
      role = "client";
      deviceId = url.searchParams.get("deviceId") ?? "";
    } else if (path === "/online" && request.method === "GET") {
      role = "online";
      deviceId = url.searchParams.get("deviceId") ?? "";
    }

    if (!role) {
      return json({ error: "not found" }, 404);
    }
    if (!isValidDeviceId(deviceId)) {
      return json({ error: "invalid deviceId (expect 16-64 hex chars)" }, 400);
    }

    const id = env.RELAY.idFromName(deviceId.toLowerCase());
    const stub = env.RELAY.get(id);

    // 归一化后转发给 DO（DO 内部路由：/device、/connect、/online）
    const doPath = role === "device" ? "/device" : role === "client" ? "/connect" : "/online";
    const relayUrl = new URL(`https://relay.internal${doPath}`);
    for (const [k, v] of url.searchParams.entries()) {
      relayUrl.searchParams.set(k, v);
    }
    relayUrl.searchParams.set("deviceId", deviceId.toLowerCase());
    return stub.fetch(new Request(relayUrl.toString(), request));
  },
};
