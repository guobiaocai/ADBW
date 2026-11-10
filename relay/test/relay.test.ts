import { describe, expect, it } from "vitest";
import { Relay, type WsLike } from "../src/index";

class FakeWS implements WsLike {
  sent: (string | ArrayBuffer)[] = [];
  closed: { code?: number; reason?: string } | null = null;
  private attachment: unknown;
  tags: string[] = [];

  send(message: string | ArrayBuffer): void {
    if (this.closed) throw new Error("sending on closed socket");
    this.sent.push(message);
  }

  close(code?: number, reason?: string): void {
    if (this.closed) return;
    this.closed = { code, reason };
  }

  serializeAttachment(annotation: unknown): void {
    this.attachment = annotation;
  }

  deserializeAttachment(): unknown {
    return this.attachment;
  }

  textFrames(): string[] {
    return this.sent.filter((m): m is string => typeof m === "string");
  }
}

if (typeof (globalThis as any).WebSocketPair === "undefined") {
  (globalThis as any).WebSocketPair = class {
    0 = new FakeWS();
    1 = new FakeWS();
  };
}

class FakeState {
  private byTag = new Map<string, FakeWS[]>();

  acceptWebSocket(ws: FakeWS, tags: string[]): void {
    ws.tags = [...tags];
    for (const t of tags) {
      const list = this.byTag.get(t) ?? [];
      list.push(ws);
      this.byTag.set(t, list);
    }
  }

  getWebSockets(tag: string): FakeWS[] {
    const list = (this.byTag.get(tag) ?? []).filter((ws) => !ws.closed);
    this.byTag.set(tag, list);
    return list;
  }
}

function makeRelay() {
  const state = new FakeState();
  return { relay: new Relay(state as unknown as DurableObjectState), state };
}

describe("edge relay session epoch fencing", () => {
  it("rejects device connection with missing sid/epoch", async () => {
    const { relay } = makeRelay();
    const r = await relay.fetch(new Request("https://relay.internal/device?deviceId=a1b2c3d4e5f60718293a4b5c6d7e8f90"));
    expect(r.status).toBe(409);
  });

  it("accepts new session and rejects stale session", async () => {
    const { relay } = makeRelay();
    const devId = "a1b2c3d4e5f60718293a4b5c6d7e8f90";

    // Session 1 (epoch 1000)
    const r1 = await relay.fetch(new Request(`https://relay.internal/device?deviceId=${devId}&sid=s1&epoch=1000`));
    expect(r1.status).toBe(101);

    // Session 2 takes over (epoch 2000)
    const r2 = await relay.fetch(new Request(`https://relay.internal/device?deviceId=${devId}&sid=s2&epoch=2000`));
    expect(r2.status).toBe(101);

    // Reconnection from Session 2 (same epoch & sid) is allowed
    const r2Reconnect = await relay.fetch(new Request(`https://relay.internal/device?deviceId=${devId}&sid=s2&epoch=2000`));
    expect(r2Reconnect.status).toBe(101);

    // Stale Session 1 tries to reconnect -> REJECTED (409)
    const r1Stale = await relay.fetch(new Request(`https://relay.internal/device?deviceId=${devId}&sid=s1&epoch=1000`));
    expect(r1Stale.status).toBe(409);
  });

  it("device connect -> client connect -> paired notification", () => {
    const { relay } = makeRelay();
    const device = new FakeWS();
    const client = new FakeWS();
    relay.addDevice(device, "s1", 100);
    expect(relay.addClient(client)).toBe(true);
    expect(device.textFrames()).toContain(JSON.stringify({ op: "edge", event: "paired" }));
  });

  it("forwards frames both ways, preserving text and binary", () => {
    const { relay } = makeRelay();
    const device = new FakeWS();
    const client = new FakeWS();
    relay.addDevice(device, "s1", 100);
    relay.addClient(client);

    const bin = new ArrayBuffer(8);
    relay.onMessage(device, '{"jsonrpc":"2.0","id":1,"method":"ping"}');
    relay.onMessage(client, bin);
    relay.onMessage(client, '{"op":"eod","id":1}');

    expect(client.textFrames()).toContain('{"jsonrpc":"2.0","id":1,"method":"ping"}');
    expect(device.sent).toContain(bin);
    expect(device.textFrames()).toContain('{"op":"eod","id":1}');
  });
});
