/** 配对串解析（PROTOCOL.md §1）：vibeadb://<relay-host>/<deviceId>#<password> 或 vibeadb://et/<host>:<port>#<password> */

export interface PairingTarget {
  /** relay = Cloudflare DO 中继；easytier = EasyTier 组网直连（ws://host:port） */
  kind: "relay" | "easytier";
  /** 中继主机名；测试环境允许带 ws:// 前缀 */
  relayHost: string;
  deviceId: string;
  /** easytier 模式：手机 WebSocket 监听地址 */
  host: string;
  port: number;
  password: string;
}

export function parsePairing(raw: string): PairingTarget {
  let s = raw.trim();
  if (!s.startsWith("vibeadb://")) {
    throw new Error("无效配对串：缺少 vibeadb:// 前缀");
  }
  s = s.slice("vibeadb://".length);
  const hashIdx = s.indexOf("#");
  if (hashIdx < 0) {
    throw new Error("无效配对串：缺少 #password 部分");
  }
  const body = s.slice(0, hashIdx);
  const password = s.slice(hashIdx + 1);
  if (!body || !password) {
    throw new Error("无效配对串");
  }

  // 允许 host 带协议前缀（ws:// / wss://，测试/本地环境用）
  let rest = body;
  let prefixLen = 0;
  const schemeIdx = rest.indexOf("://");
  if (schemeIdx >= 0) {
    prefixLen = schemeIdx + 3;
    rest = rest.slice(prefixLen);
  }
  const slashIdx = rest.lastIndexOf("/");
  if (slashIdx < 0) {
    throw new Error("无效配对串：v2 需要 <relay-host>/<deviceId> 形式");
  }
  const relayHost = body.slice(0, prefixLen + slashIdx);
  const deviceId = rest.slice(slashIdx + 1);
  if (!relayHost || !deviceId) {
    throw new Error("无效配对串：relay-host 或 deviceId 为空");
  }

  // EasyTier：vibeadb://et/<host>:<port>#<password>
  if (relayHost === "et") {
    const colon = deviceId.lastIndexOf(":");
    if (colon <= 0) {
      throw new Error("无效 EasyTier 配对串：应为 et/<host>:<port>");
    }
    const host = deviceId.slice(0, colon);
    const port = Number(deviceId.slice(colon + 1));
    if (!host || !Number.isInteger(port) || port <= 0 || port > 65535) {
      throw new Error("无效 EasyTier 配对串：host 或端口非法");
    }
    return { kind: "easytier", relayHost: "et", deviceId, host, port, password };
  }

  return { kind: "relay", relayHost, deviceId, host: "", port: 0, password };
}

/** 构造 client 腿的 WebSocket URL */
export function connectUrl(t: PairingTarget): string {
  if (t.kind === "easytier") {
    return `ws://${t.host}:${t.port}`;
  }
  const base = /^(ws|wss):\/\//.test(t.relayHost) ? t.relayHost : `wss://${t.relayHost}`;
  return `${base}/connect?deviceId=${encodeURIComponent(t.deviceId)}`;
}
