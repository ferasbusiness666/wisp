/**
 * Wisp signaling + TURN credentials on Cloudflare Workers.
 *
 * - `GET /ws?code=XXXXXXXX` upgrades to a WebSocket handled by the Signaling
 *   Durable Object, which keeps the routing table.
 * - `GET /turn-credentials` mints short-lived Cloudflare Realtime TURN
 *   credentials server-side, so no TURN secret ever ships in the app.
 */

export interface Env {
  SIGNALING: DurableObjectNamespace;
  TURN_KEY_ID: string;
  TURN_KEY_API_TOKEN: string;
  TURN_TTL_SECONDS?: string;
}

/** Same alphabet the app generates from: no O/0/I/1. */
const CODE_RE = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$/;

const PUBLIC_STUN = ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'];

/**
 * Free public relay, used when no Cloudflare TURN key is configured.
 *
 * Cloudflare Realtime wants a payment method on file even inside its free
 * tier, so this keeps a relay available at no cost. It is a shared community
 * service with no uptime guarantee, and it is only ever a fallback: STUN is
 * tried first, and media stays end-to-end encrypted by WebRTC either way, so
 * a relay operator can carry the packets but not read them.
 */
const PUBLIC_TURN = [
  {
    urls: [
      'turn:openrelay.metered.ca:80',
      'turn:openrelay.metered.ca:443',
      'turn:openrelay.metered.ca:443?transport=tcp',
    ],
    username: 'openrelayproject',
    credential: 'openrelayproject',
  },
];

/** What the app gets when Cloudflare TURN is not configured or is failing. */
function freeIceServers(): unknown[] {
  return [...PUBLIC_STUN.map((urls) => ({ urls })), ...PUBLIC_TURN];
}

const DEFAULT_TURN_TTL = 86400;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
    },
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    switch (url.pathname) {
      case '/ws': {
        if (request.headers.get('Upgrade') !== 'websocket') {
          return new Response('expected websocket', { status: 426 });
        }
        const code = (url.searchParams.get('code') ?? '').toUpperCase();
        if (!CODE_RE.test(code)) return new Response('bad code', { status: 400 });

        // A single object holds every socket, so routing is a map lookup.
        const id = env.SIGNALING.idFromName('global');
        return env.SIGNALING.get(id).fetch(request);
      }

      case '/turn-credentials':
        return turnCredentials(request, env);

      case '/health':
        return json({ ok: true });

      default:
        return new Response('not found', { status: 404 });
    }
  },
} satisfies ExportedHandler<Env>;

/**
 * Calls Cloudflare Realtime's TURN API with the Worker's secrets and hands the
 * app back an iceServers array it can pass straight to RTCConfiguration.
 */
async function turnCredentials(request: Request, env: Env): Promise<Response> {
  if (request.method !== 'GET' && request.method !== 'POST') {
    return new Response('method not allowed', { status: 405 });
  }

  // Not access control — the code is an identity, not a secret (see README).
  // It only keeps drive-by scanners from minting credentials.
  const code = (new URL(request.url).searchParams.get('code') ?? '').toUpperCase();
  if (!CODE_RE.test(code)) return new Response('bad code', { status: 400 });

  if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN) {
    return json({ iceServers: freeIceServers(), turn: 'public' });
  }

  const ttl = Number(env.TURN_TTL_SECONDS ?? DEFAULT_TURN_TTL) || DEFAULT_TURN_TTL;
  const endpoint =
    `https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate-ice-servers`;

  const response = await fetch(endpoint, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ ttl }),
  });

  if (!response.ok) {
    const detail = await response.text();
    console.error('TURN mint failed', response.status, detail);
    return json({ iceServers: freeIceServers(), turn: 'public' }, 200);
  }

  const body = (await response.json()) as { iceServers?: unknown };
  const minted = body.iceServers;
  const servers = Array.isArray(minted) ? minted : minted ? [minted] : [];

  if (servers.length === 0) return json({ iceServers: freeIceServers(), turn: 'public' });

  return json({
    // Cloudflare's TURN first, public STUN behind it for redundancy.
    iceServers: [...servers, ...PUBLIC_STUN.map((urls) => ({ urls }))],
    ttl,
    turn: 'cloudflare',
  });
}

interface Attachment {
  code: string;
  /** Display name this device published, shown to others in the directory. */
  name: string;
  watching: string[];
}

type Inbound =
  | { type: 'watch'; codes?: unknown }
  | { type: 'identify'; name?: unknown }
  | { type: 'directory' }
  | { type: 'offer' | 'answer'; to?: unknown; sdp?: unknown }
  | { type: 'ice'; to?: unknown; mid?: unknown; index?: unknown; candidate?: unknown }
  | { type: 'bye'; to?: unknown; reason?: unknown }
  | { type: 'ice-restart'; to?: unknown }
  | { type: 'add-me'; to?: unknown };

/**
 * Routing table for every connected device.
 *
 * Uses the WebSocket Hibernation API: sockets stay open while the object is
 * evicted from memory, so an idle pair of phones costs nothing and there is no
 * cold start when a call finally comes in. Because memory does not survive
 * hibernation, all per-socket state lives in the socket's attachment and the
 * tag it was accepted with.
 */
export class Signaling implements DurableObject {
  constructor(private readonly ctx: DurableObjectState, private readonly env: Env) {
    // Answer app-level keepalives without waking the object up.
    this.ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const code = (url.searchParams.get('code') ?? '').toUpperCase();
    if (!CODE_RE.test(code)) return new Response('bad code', { status: 400 });

    // A reconnect from the same device replaces its old socket rather than
    // leaving a zombie that presence would still count as online.
    for (const stale of this.ctx.getWebSockets(code)) {
      try {
        stale.close(1000, 'replaced');
      } catch {
        // Already gone; nothing to do.
      }
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    // The tag is how offer/answer/ice find this device later.
    this.ctx.acceptWebSocket(server, [code]);
    server.serializeAttachment({ code, name: '', watching: [] } satisfies Attachment);
    server.send(JSON.stringify({ type: 'hello', code }));

    this.broadcastPresence(code, true);
    this.broadcastDirectory();

    return new Response(null, { status: 101, webSocket: client });
  }

  webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): void {
    if (typeof raw !== 'string') return;

    let message: Inbound;
    try {
      message = JSON.parse(raw) as Inbound;
    } catch {
      return;
    }

    const self = this.attachment(ws);
    if (!self) return;

    switch (message.type) {
      case 'watch': {
        const codes = Array.isArray(message.codes)
          ? message.codes
              .filter((c): c is string => typeof c === 'string')
              .map((c) => c.toUpperCase())
              .filter((c) => CODE_RE.test(c))
              .slice(0, 100)
          : [];
        ws.serializeAttachment(
          { code: self.code, name: self.name, watching: codes } satisfies Attachment
        );

        const states: Record<string, boolean> = {};
        for (const code of codes) states[code] = this.isOnline(code);
        ws.send(JSON.stringify({ type: 'presence', states }));
        return;
      }

      case 'identify': {
        // A device publishing the name others should see for it.
        const raw = typeof message.name === 'string' ? message.name : '';
        const name = raw.trim().slice(0, 40);
        ws.serializeAttachment(
          { code: self.code, name, watching: self.watching } satisfies Attachment
        );
        this.broadcastDirectory();
        return;
      }

      case 'directory': {
        ws.send(JSON.stringify({ type: 'directory', people: this.buildDirectory() }));
        return;
      }

      case 'offer':
      case 'answer': {
        const to = this.target(message.to);
        if (!to) return;
        this.forward(ws, self, to, {
          type: message.type,
          from: self.code,
          sdp: typeof message.sdp === 'string' ? message.sdp : '',
        });
        return;
      }

      case 'ice': {
        const to = this.target(message.to);
        if (!to) return;
        this.forward(ws, self, to, {
          type: 'ice',
          from: self.code,
          mid: typeof message.mid === 'string' ? message.mid : null,
          index: typeof message.index === 'number' ? message.index : 0,
          candidate: typeof message.candidate === 'string' ? message.candidate : '',
        });
        return;
      }

      case 'bye': {
        const to = this.target(message.to);
        if (!to) return;
        this.forward(ws, self, to, {
          type: 'bye',
          from: self.code,
          reason: typeof message.reason === 'string' ? message.reason : null,
        });
        return;
      }

      case 'ice-restart': {
        const to = this.target(message.to);
        if (!to) return;
        this.forward(ws, self, to, { type: 'ice-restart', from: self.code });
        return;
      }

      case 'add-me': {
        // Saving someone should be mutual: one side adding the other is enough.
        // The name comes from our own attachment, so a device cannot claim
        // someone else's identity here.
        const to = this.target(message.to);
        if (!to) return;
        this.forward(ws, self, to, {
          type: 'add-me',
          from: self.code,
          name: self.name,
        });
        return;
      }

      default:
        ws.send(JSON.stringify({ type: 'error', message: 'unknown message type' }));
    }
  }

  webSocketClose(ws: WebSocket): void {
    this.onGone(ws);
  }

  webSocketError(ws: WebSocket): void {
    this.onGone(ws);
  }

  private onGone(ws: WebSocket): void {
    const self = this.attachment(ws);
    if (!self) return;
    // getWebSockets() still includes the socket being closed, so only report
    // offline when this was the device's last one.
    const remaining = this.ctx.getWebSockets(self.code).filter((s) => s !== ws);
    if (remaining.length === 0) this.broadcastPresence(self.code, false);
    this.broadcastDirectory(ws);
  }

  private attachment(ws: WebSocket): Attachment | null {
    const raw = ws.deserializeAttachment() as Attachment | null;
    if (!raw || typeof raw.code !== 'string') return null;
    return {
      code: raw.code,
      name: typeof raw.name === 'string' ? raw.name : '',
      watching: Array.isArray(raw.watching) ? raw.watching : [],
    };
  }

  private target(value: unknown): string | null {
    if (typeof value !== 'string') return null;
    const code = value.toUpperCase();
    return CODE_RE.test(code) ? code : null;
  }

  private isOnline(code: string): boolean {
    return this.ctx.getWebSockets(code).length > 0;
  }

  /** Sends to every socket registered under `to`, or reports it unavailable. */
  private forward(from: WebSocket, self: Attachment, to: string, payload: object): void {
    const sockets = this.ctx.getWebSockets(to);
    if (sockets.length === 0) {
      from.send(JSON.stringify({ type: 'unavailable', to, reason: 'offline' }));
      return;
    }
    const body = JSON.stringify(payload);
    for (const socket of sockets) {
      try {
        socket.send(body);
      } catch {
        // The socket is on its way out; its close handler will fix presence.
      }
    }
  }

  /**
   * Everyone currently connected, so a device can offer them as people to add
   * instead of making someone type a code. One entry per code even if a device
   * has more than one socket open.
   */
  private buildDirectory(exclude?: WebSocket): Array<{ code: string; name: string }> {
    const byCode = new Map<string, string>();
    for (const socket of this.ctx.getWebSockets()) {
      if (socket === exclude) continue;
      const who = this.attachment(socket);
      if (!who) continue;
      // A later socket with a real name wins over an earlier blank one.
      if (!byCode.has(who.code) || (who.name && !byCode.get(who.code))) {
        byCode.set(who.code, who.name);
      }
    }
    return [...byCode].map(([code, name]) => ({ code, name }));
  }

  /** Pushed on every join, leave and rename, so lists stay live. */
  private broadcastDirectory(exclude?: WebSocket): void {
    const body = JSON.stringify({ type: 'directory', people: this.buildDirectory(exclude) });
    for (const socket of this.ctx.getWebSockets()) {
      if (socket === exclude) continue;
      try {
        socket.send(body);
      } catch {
        // On its way out; its close handler will refresh the directory.
      }
    }
  }

  private broadcastPresence(code: string, online: boolean): void {
    const body = JSON.stringify({ type: 'presence', code, online });
    for (const socket of this.ctx.getWebSockets()) {
      const other = this.attachment(socket);
      if (!other || other.code === code) continue;
      if (!other.watching.includes(code)) continue;
      try {
        socket.send(body);
      } catch {
        // Ignore: a dead socket cannot care about presence.
      }
    }
  }
}
