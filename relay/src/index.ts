

import { DurableObject } from "cloudflare:workers"

export interface Env {
  ROOMS: DurableObjectNamespace<Room>
}

const SERVER_ID = /^[A-Za-z0-9]{8,32}$/
const MAX_VIEWERS = 64
const MAX_VIEWER_MESSAGE = 4096

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url)
    if (url.pathname === "/") return new Response("minevisor relay\n")
    if (url.pathname !== "/publish" && url.pathname !== "/view") return new Response("not found", { status: 404 })
    if (request.headers.get("Upgrade") !== "websocket") return new Response("expected a websocket", { status: 426 })
    const server = url.searchParams.get("server") ?? ""
    if (!SERVER_ID.test(server)) return new Response("bad server id", { status: 400 })
    return env.ROOMS.get(env.ROOMS.idFromName(server)).fetch(request)
  },
} satisfies ExportedHandler<Env>

type Attachment = { role: "pub" } | { role: "view"; id: string; ok: boolean; t: string }

async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text))
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("")
}

function randomId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(6))
  return [...bytes].map((b) => b.toString(36).padStart(2, "0")).join("").slice(0, 10)
}

function errorFrame(id: number, status: number, message: string): ArrayBuffer {
  const header = new TextEncoder().encode(JSON.stringify({ id, status, type: "text/plain", part: 0, parts: 1 }))
  const body = new TextEncoder().encode(message)
  const out = new Uint8Array(4 + header.length + body.length)
  new DataView(out.buffer).setUint32(0, header.length)
  out.set(header, 4)
  out.set(body, 4 + header.length)
  return out.buffer
}

export class Room extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env)
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"))
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url)
    const pair = new WebSocketPair()
    const [client, socket] = [pair[0], pair[1]]

    if (url.pathname === "/publish") {
      const key = url.searchParams.get("key") ?? ""
      if (key.length < 32) return new Response("bad key", { status: 400 })
      const hash = await sha256(key)
      const known = await this.ctx.storage.get<string>("keyHash")
      if (known && known !== hash) return new Response("this server id belongs to another key", { status: 403 })
      if (!known) await this.ctx.storage.put("keyHash", hash)
      for (const old of this.ctx.getWebSockets("pub")) old.close(4000, "replaced")
      this.ctx.acceptWebSocket(socket, ["pub"])
      socket.serializeAttachment({ role: "pub" } satisfies Attachment)
      this.helloAll(socket, true)
      return new Response(null, { status: 101, webSocket: client })
    }

    if (this.viewers().length >= MAX_VIEWERS) return new Response("too many viewers", { status: 503 })
    const id = randomId()
    const token = url.searchParams.get("t") ?? ""
    this.ctx.acceptWebSocket(socket, ["view", id])
    socket.serializeAttachment({ role: "view", id, ok: false, t: token.slice(0, 128) } satisfies Attachment)
    const pub = this.publisher()
    socket.send(JSON.stringify({ type: "status", online: Boolean(pub) }))
    pub?.send(JSON.stringify({ type: "hello", viewer: id, t: token }))
    return new Response(null, { status: 101, webSocket: client })
  }

  private helloAll(pub: WebSocket, announce: boolean): void {
    for (const viewer of this.viewers()) {
      const a = viewer.deserializeAttachment() as Attachment
      if (a.role !== "view") continue
      viewer.serializeAttachment({ ...a, ok: false })
      pub.send(JSON.stringify({ type: "hello", viewer: a.id, t: a.t }))
      if (announce) viewer.send(JSON.stringify({ type: "status", online: true }))
    }
  }

  private publisher(): WebSocket | null {
    return this.ctx.getWebSockets("pub")[0] ?? null
  }

  private viewers(): WebSocket[] {
    return this.ctx.getWebSockets("view")
  }

  private viewer(id: string): WebSocket | null {
    return this.ctx.getWebSockets(id)[0] ?? null
  }

  private sendViewerCount(): void {
    let count = 0
    for (const v of this.viewers()) if ((v.deserializeAttachment() as Attachment & { ok?: boolean }).ok) count++
    this.publisher()?.send(JSON.stringify({ type: "viewers", count }))
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const a = ws.deserializeAttachment() as Attachment
    if (a.role === "pub") return this.fromPublisher(message)
    if (typeof message !== "string" || message.length > MAX_VIEWER_MESSAGE) return
    let msg: { type?: string; id?: number; path?: string; query?: Record<string, string> }
    try {
      msg = JSON.parse(message)
    } catch {
      return
    }
    if (msg.type !== "req" || typeof msg.id !== "number" || typeof msg.path !== "string") return
    const pub = this.publisher()
    if (!pub) return ws.send(errorFrame(msg.id, 503, "the server isn't connected"))
    if (!a.ok) return ws.send(errorFrame(msg.id, 401, "not allowed yet"))
    pub.send(JSON.stringify({ type: "req", viewer: a.id, id: msg.id, path: msg.path, query: msg.query ?? {} }))
  }

  private fromPublisher(message: string | ArrayBuffer): void {
    if (typeof message !== "string") {
      const view = new DataView(message)
      if (message.byteLength < 4) return
      const length = view.getUint32(0)
      if (length > message.byteLength - 4) return
      let header: { viewer?: string }
      try {
        header = JSON.parse(new TextDecoder().decode(new Uint8Array(message, 4, length)))
      } catch {
        return
      }
      if (header.viewer) this.viewer(header.viewer)?.send(message)
      return
    }
    let msg: { type?: string; event?: string; data?: string; to?: string; viewer?: string; ok?: boolean }
    try {
      msg = JSON.parse(message)
    } catch {
      return
    }
    if (msg.type === "event" && msg.event) {
      const out = JSON.stringify({ type: "event", event: msg.event, data: msg.data ?? "{}" })
      if (msg.to) {
        this.viewer(msg.to)?.send(out)
        return
      }
      for (const v of this.viewers()) if ((v.deserializeAttachment() as { ok?: boolean }).ok) v.send(out)
    } else if (msg.type === "reauth") {
      const pub = this.publisher()
      if (pub) this.helloAll(pub, false)
    } else if (msg.type === "auth" && msg.viewer) {
      const v = this.viewer(msg.viewer)
      if (!v) return
      if (!msg.ok) {
        v.send(JSON.stringify({ type: "refused" }))
        v.close(4001, "this link isn't valid anymore")
        return
      }
      const a = v.deserializeAttachment() as Attachment
      if (a.role === "view") v.serializeAttachment({ ...a, ok: true } satisfies Attachment)
      v.send(JSON.stringify({ type: "ready" }))
      this.sendViewerCount()
    }
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    this.gone(ws)
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    this.gone(ws)
  }

  private gone(ws: WebSocket): void {
    const a = ws.deserializeAttachment() as Attachment | null
    if (!a) return
    if (a.role === "pub") {
      if (this.ctx.getWebSockets("pub").some((p) => p !== ws)) return
      for (const v of this.viewers()) v.send(JSON.stringify({ type: "status", online: false }))
      return
    }
    this.publisher()?.send(JSON.stringify({ type: "bye", viewer: a.id }))
    this.sendViewerCount()
  }
}
