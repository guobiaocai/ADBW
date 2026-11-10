import { describe, expect, it } from "vitest";
import { WebSocketServer, type WebSocket } from "ws";
import { DeviceClient } from "../src/client.js";
import { connectUrl, parsePairing } from "../src/pairing.js";
import http from "node:http";

describe("parsePairing", () => {
  it("parses relay form", () => {
    const t = parsePairing("vibeadb://box.workers.dev/abcd1234ef567890#pw123");
    expect(t.relayHost).toBe("box.workers.dev");
    expect(t.deviceId).toBe("abcd1234ef567890");
    expect(t.password).toBe("pw123");
  });

  it("parses host with ws scheme (tests)", () => {
    const t = parsePairing("vibeadb://ws://127.0.0.1:9000/dev1#pw");
    expect(t.relayHost).toBe("ws://127.0.0.1:9000");
    expect(t.deviceId).toBe("dev1");
  });

  it("rejects v1 direct form (no deviceId)", () => {
    expect(() => parsePairing("vibeadb://host#pw")).toThrow();
  });

  it("builds connect url", () => {
    const t = parsePairing("vibeadb://box.workers.dev/dev1#pw");
    expect(connectUrl(t)).toBe("wss://box.workers.dev/connect?deviceId=dev1");
    expect(t.kind).toBe("relay");
  });

  it("parses easytier form and builds ws url", () => {
    const t = parsePairing("vibeadb://et/10.126.126.10:27183#pw");
    expect(t.kind).toBe("easytier");
    expect(t.host).toBe("10.126.126.10");
    expect(t.port).toBe(27183);
    expect(t.password).toBe("pw");
    expect(connectUrl(t)).toBe("ws://10.126.126.10:27183");
  });

  it("rejects easytier without port", () => {
    expect(() => parsePairing("vibeadb://et/10.126.126.10#pw")).toThrow();
  });
});

/** 假"中继+设备"合一服务：校验 auth，然后处理一个 JSON-RPC 请求 */
function fakeRelayDevice(mode: "chunks" | "install"): Promise<{ server: http.Server; url: string }> {
  return new Promise((resolve) => {
    const wss = new WebSocketServer({ noServer: true });
    const httpServer = http.createServer((req, res) => {
      if (!req.url?.startsWith("/connect?deviceId=dev1")) {
        res.writeHead(503, { "content-type": "application/json" });
        res.end(JSON.stringify({ error: "device offline" }));
        return;
      }
      wss.handleUpgrade(req, req.socket, Buffer.alloc(0), (ws: WebSocket) => {
        // --- auth（手机侧语义）---
        ws.once("message", (data) => {
          const auth = JSON.parse(data.toString());
          if (auth.password !== "pw") {
            ws.send(JSON.stringify({ op: "auth", ok: false, error: "bad password" }));
            ws.close(4001, "auth failed");
            return;
          }
          ws.send(JSON.stringify({ op: "auth", ok: true }));

          ws.once("message", (raw, isBinary) => {
            if (isBinary) return;
            const req = JSON.parse(raw.toString());
            const idb = Buffer.alloc(4);
            idb.writeUInt32BE(req.id, 0);
            const reply = (result: any) => {
              ws.send(
                JSON.stringify({ jsonrpc: "2.0", id: req.id, result }),
              );
            };
            if (mode === "chunks") {
              ws.send(Buffer.concat([idb, Buffer.from("hello ")]), { binary: true });
              ws.send(Buffer.concat([idb, Buffer.from("world")]), { binary: true });
              reply({ exitCode: 0 });
            } else {
              // install：收满 size 字节 + eod（req.params 已是对象）
              const size = req.params?.size ?? 0;
              const got: Buffer[] = [];
              let total = 0;
              const onMsg = (d: Buffer, bin: boolean) => {
                if (bin && total < size) {
                  got.push(d.subarray(4));
                  total += d.length - 4;
                  return;
                }
                if (!bin) {
                  const e = JSON.parse(d.toString());
                  if (e.op !== "eod") return;
                  ws.off("message", onMsg);
                  reply({ exitCode: 0, output: `Success ${total}` });
                }
              };
              ws.on("message", onMsg);
            }
          });
        });
      });
    });
    httpServer.listen(0, "127.0.0.1", () => {
      const addr = httpServer.address();
      const port = typeof addr === "object" && addr ? addr.port : 0;
      resolve({ server: httpServer, url: `ws://127.0.0.1:${port}` });
    });
  });
}

describe("DeviceClient", () => {
  it("auth + chunked response roundtrip", async () => {
    const { server, url } = await fakeRelayDevice("chunks");
    const target = parsePairing(`vibeadb://${url}/dev1#pw`);
    const c = await DeviceClient.connect(target);
    const chunks: Buffer[] = [];
    const r = await c.call("shell", { command: "echo" }, {
      onChunk: (b) => chunks.push(b),
    });
    expect(Buffer.concat(chunks).toString()).toBe("hello world");
    expect(r.exitCode).toBe(0);
    c.close();
    server.close();
  }, 15_000);

  it("auth + upload (pm.install) with eod", async () => {
    const { server, url } = await fakeRelayDevice("install");
    const target = parsePairing(`vibeadb://${url}/dev1#pw`);
    const c = await DeviceClient.connect(target);
    const data = Buffer.alloc(200 * 1024, 0xab);
    const r = await c.call("pm.install", { size: data.length }, { upload: data });
    expect(r.exitCode).toBe(0);
    expect(r.output).toBe(`Success ${data.length}`);
    c.close();
    server.close();
  }, 15_000);

  it("bad password fails", async () => {
    const { server, url } = await fakeRelayDevice("chunks");
    const target = parsePairing(`vibeadb://${url}/dev1#wrong`);
    await expect(DeviceClient.connect(target)).rejects.toThrow(/auth failed/);
    server.close();
  }, 15_000);
});
