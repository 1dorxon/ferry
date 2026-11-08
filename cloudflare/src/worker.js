/* BrowserDrop on Cloudflare Workers.
 *
 * The Worker serves the page and routes each signalling WebSocket to the Durable
 * Object for the caller's network: one object per room, so every device behind
 * the same router meets in the same place by construction.
 *
 * Billing guards (see wrangler.jsonc): Free-plan features only, hibernating
 * WebSockets, keep-alive pings answered without waking the object, and hard
 * per-room and per-connection caps so abuse cannot burn the daily quota.
 */
import { DurableObject } from 'cloudflare:workers';
import { ASSET_VERSION } from './version.js';

const MAX_PEERS_PER_ROOM = 24;          // a household or office, not a crowd
const MAX_MSGS_PER_MINUTE = 120;        // one transfer's signalling is ~20
const MAX_MSG_BYTES = 64 * 1024;        // an SDP is a few KB
const STALE_MS = 3 * 60_000;            // no keep-alive for this long = gone
const EVICT_EVERY_MS = 60_000;          // a full room makes space at most this often
// A pairing code is a short secret, so its room is a short-lived rendezvous for
// a few devices, and guessing is limited per network (see pairGate).
const PAIR_WINDOW_MS = 15 * 60_000;     // new devices may join a code for this long
const PAIR_MAX_DEVICES = 3;             // two devices, plus a reopened tab
const PAIR_JOINS_PER_WINDOW = 20;       // pairing joins per network per window
const PAIR_GATE_WINDOW_MS = 10 * 60_000;
const DEVICES = new Set(['iphone', 'android', 'ipad', 'mac', 'windows', 'desktop']);
const PING = '{"type":"ping"}';         // must match the client byte-for-byte
const PONG = '{"type":"pong"}';

const enc = new TextEncoder();
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');

/** Room = HMAC of the caller's public IP, identical to the Node server's. */
/**
 * The part of a client address that identifies its network, which is what
 * "same network" has to mean for rooms:
 *  - IPv4: the whole address. Everyone behind one router shares it (NAT).
 *  - IPv6: the /64 prefix. There's no NAT, so every device -- and every
 *    temporary privacy address -- is different, but a home network, a phone
 *    hotspot (RFC 7278 shares the phone's /64 with its clients) or an office
 *    LAN is one /64. Hashing the full address put each IPv6 device alone.
 */
function networkOf(ip) {
  if (!ip || !ip.includes(':')) return ip || 'unknown';
  const mapped = ip.match(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/i);
  if (mapped) return mapped[1];                       // IPv4-mapped: treat as IPv4
  const hextets = (part) => {
    if (!part) return [];
    const out = [];
    for (const p of part.split(':')) {
      if (p.includes('.')) {                          // embedded IPv4 tail
        const [a, b, c, d] = p.split('.').map(Number);
        out.push(((a << 8) | b).toString(16), ((c << 8) | d).toString(16));
      } else out.push(p);
    }
    return out;
  };
  const [head, tail] = ip.split('%')[0].split('::');
  const h = hextets(head);
  const t = tail === undefined ? null : hextets(tail);
  const all = t === null ? h : [...h, ...Array(Math.max(0, 8 - h.length - t.length)).fill('0'), ...t];
  return all.slice(0, 4).map((g) => parseInt(g || '0', 16).toString(16)).join(':') + '::/64';
}

/** The room for a pairing code: keyed like network rooms, so codes never appear in room ids. */
async function pairRoomFor(code, env) {
  const key = await crypto.subtle.importKey('raw', enc.encode(env.ROOM_SALT || 'browserdrop-default-salt'),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return 'pair-' + hex(await crypto.subtle.sign('HMAC', key, enc.encode(`pair:${code}`))).slice(0, 12);
}

/**
 * The client's address. Local testing only: `wrangler dev --var ALLOW_TEST_IP:1`
 * lets a `testip` cookie stand in for it, so e2e tests can put two browsers on
 * "different networks". Production never sets ALLOW_TEST_IP, so it's ignored there.
 */
function clientAddress(request, env) {
  if (env.ALLOW_TEST_IP === '1') {
    const m = (request.headers.get('Cookie') || '').match(/(?:^|;\s*)testip=([0-9a-fA-F:.]+)/);
    if (m) return m[1];
  }
  return request.headers.get('CF-Connecting-IP') || 'unknown';
}

async function roomFor(request, env) {
  const ip = networkOf(clientAddress(request, env));
  const key = await crypto.subtle.importKey('raw', enc.encode(env.ROOM_SALT || 'browserdrop-default-salt'),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return 'ip-' + hex(await crypto.subtle.sign('HMAC', key, enc.encode(ip))).slice(0, 12);
}

/**
 * A device's public id is derived from a private per-tab token, so a reconnect
 * (network blip, or a redeploy) comes back as the same device and transfers in
 * flight survive. Others only ever see the derived id, never the token, so it
 * cannot be used to impersonate anyone.
 */
async function idFromToken(token) {
  return hex(await crypto.subtle.digest('SHA-256', enc.encode(`bd-peer:${token}`))).slice(0, 32);
}

/**
 * A client-supplied name as others will see it: no directory parts, no control
 * or invisible formatting characters (bidi overrides, zero-width), whitespace
 * collapsed, at most 60 characters (never splitting one).
 */
function sanitiseName(name) {
  if (typeof name !== 'string') return null;
  const base = name.split(/[\\/]/).pop() ?? '';
  const clean = base.normalize('NFKC').replace(/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/gu, '').replace(/\s+/g, ' ').trim();
  return [...clean].slice(0, 60).join('') || null;
}

/**
 * Web pages allowed to open signalling sockets. The room is picked from the
 * caller's IP, which any page in a visitor's browser shares, so a foreign page
 * must not get in: only Ferry's own page (whichever host served it), extra
 * origins named in ALLOWED_ORIGINS (the Chrome extension; a trailing * matches a
 * prefix, for local tests), and clients that send no Origin at all (the
 * Android app). A page can't omit Origin on a WebSocket, so it can't pass as one.
 */
function originAllowed(request, url, env) {
  const origin = request.headers.get('Origin');
  if (origin === null || origin === url.origin) return true;
  return (env.ALLOWED_ORIGINS || '').split(',').map((s) => s.trim()).filter(Boolean)
    .some((a) => (a.endsWith('*') ? origin.startsWith(a.slice(0, -1)) : origin === a));
}

/** Headers on every page response: HTTPS only, not framed, no type sniffing. */
const PAGE_HEADERS = {
  'strict-transport-security': 'max-age=31536000',
  'x-content-type-options': 'nosniff',
  'x-frame-options': 'DENY',
  'content-security-policy': "frame-ancestors 'none'",
  'referrer-policy': 'strict-origin-when-cross-origin',
};
const isLocal = (host) => host === 'localhost' || host === '127.0.0.1' || host === '[::1]';

const HOST = 'ferry.luitlabs.in';
const OLD_HOST = 'ferry.dorxon.com';

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } });

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // Plain http is answered only with a move to https (the app and its token
    // must never travel in clear). Local test servers are http by nature.
    if (url.protocol === 'http:' && !isLocal(url.hostname)) {
      url.protocol = 'https:';
      return Response.redirect(url.toString(), 301);
    }

    if (url.pathname === '/ws') {
      if (env.DISABLED === '1') return new Response('signalling disabled', { status: 503 });
      if (request.headers.get('Upgrade') !== 'websocket') return new Response('expected websocket', { status: 426 });
      if (!originAllowed(request, url, env)) return new Response('forbidden origin', { status: 403 });
      // ?pair=<6 digits>: the private room for a pairing code (see PAIR in
      // app.js), joined alongside the network room over a second socket.
      const pair = url.searchParams.get('pair');
      if (pair !== null && !/^\d{6}$/.test(pair)) return new Response('bad pairing code', { status: 400 });
      if (pair) {
        // Each pairing join counts against the caller's network, so a code
        // can't be found by trying them all.
        const net = await roomFor(request, env);
        const gate = await env.ROOMS.get(env.ROOMS.idFromName(net)).fetch('https://room/pair-gate', { method: 'POST' });
        if (gate.status === 429) return new Response('too many pairing attempts', { status: 429 });
      }
      const room = pair ? await pairRoomFor(pair, env) : await roomFor(request, env);
      const fwd = new URL(request.url);
      fwd.searchParams.set('room', room);
      return env.ROOMS.get(env.ROOMS.idFromName(room)).fetch(new Request(fwd, request));
    }

    if (url.pathname === '/api/whoami') {
      const room = await roomFor(request, env);
      // Address family (never the address) so two devices can compare rooms.
      const net = (request.headers.get('CF-Connecting-IP') || '').includes(':') ? 'IPv6' : 'IPv4';
      return json({ room, autoRoom: room, net });
    }
    if (url.pathname.startsWith('/api/')) return json({ error: 'not found' }, 404);

    // Android share target without the service worker installed yet: answer at
    // once without reading the upload; the page explains what happened.
    if (url.pathname === '/share') {
      return new Response(null, { status: 303, headers: { location: '/?share=missed', 'cache-control': 'no-store' } });
    }

    // The page itself: inject the asset version so JS/CSS/worker URLs change on
    // every deploy, and never let the HTML be cached.
    if (url.pathname === '/' || url.pathname === '/index.html') {
      // Ferry lives at ferry.luitlabs.in. The old address forwards its visitors
      // there, but keeps serving signalling (/ws, /api) for apps and extensions
      // built before the move, so they still meet everyone in the same rooms.
      if (url.hostname === OLD_HOST && !url.searchParams.has('share')) {
        return Response.redirect(`https://${HOST}/${url.search}`, 301);
      }
      const res = await env.ASSETS.fetch(new Request(new URL('/', url), request));
      if (!res.ok) return res;
      const html = (await res.text())
        .replace(/(href|src)="\/(app\.js|style\.css)"/g, (_, a, f) => `${a}="/${f}?v=${ASSET_VERSION}"`)
        .replace('<meta name="bd-version" content="">', `<meta name="bd-version" content="${ASSET_VERSION}">`);
      return new Response(html, { headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', ...PAGE_HEADERS } });
    }

    return env.ASSETS.fetch(request);
  },
};

/**
 * One room. Uses the WebSocket Hibernation API: between messages the object is
 * evicted from memory and costs nothing; each socket's peer details live in its
 * serialized attachment rather than in object state.
 */
export class Room extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    // Keep-alive pings are answered by Cloudflare's edge without waking us.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair(PING, PONG));
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === '/pair-gate') return this.pairGate();
    const token = url.searchParams.get('token') || '';
    const id = /^[A-Za-z0-9_-]{16,128}$/.test(token) ? await idFromToken(token) : crypto.randomUUID();

    // Same tab reconnecting: retire its previous socket first.
    for (const ws of this.ctx.getWebSockets()) {
      if (ws.deserializeAttachment()?.id === id) { try { ws.close(4000, 'replaced'); } catch {} }
    }
    const here = this.live();
    if (here.length >= MAX_PEERS_PER_ROOM && !(await this.makeSpace(here))) {
      return new Response('room full', { status: 429 });
    }
    const room = url.searchParams.get('room') || '';
    if (room.startsWith('pair-')) {
      const refused = await this.admitToPairing(id);
      if (refused) return refused;
    }

    const device = url.searchParams.get('device');
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({
      id,
      name: this.uniqueName(sanitiseName(url.searchParams.get('name')) || 'Unnamed device', id),
      device: DEVICES.has(device) ? device : 'desktop',
      room: url.searchParams.get('room'),
      since: Date.now(),                 // connect time, for staleness before the first ping
      window: Date.now(), count: 0,      // rate-limit window
    });
    server.send(JSON.stringify({ type: 'welcome', id, room: url.searchParams.get('room'), peers: this.peers(server) }));
    this.broadcastPeers(server);
    return new Response(null, { status: 101, webSocket: client });
  }

  /**
   * A full room lets a newcomer in by closing the connection that has been there
   * longest, at most once a minute. Holding every slot then takes constant
   * reconnecting rather than a pile of idle sockets, and a crowded network
   * rotates devices through instead of shutting the latest out for good. The
   * closed device keeps any transfer in progress (that runs device to device)
   * and rejoins on its own.
   */
  async makeSpace(here) {
    const now = Date.now();
    if (now - ((await this.ctx.storage.get('evicted')) || 0) < EVICT_EVERY_MS) return false;
    const since = (ws) => ws.deserializeAttachment()?.since ?? now;
    const oldest = here.reduce((a, b) => (since(b) < since(a) ? b : a));
    await this.ctx.storage.put('evicted', now);
    try { oldest.close(4002, 'room full'); } catch {}
    return true;
  }

  /**
   * A pairing room admits the devices that joined in its first 15 minutes, and
   * at most PAIR_MAX_DEVICES of them; those can always come back (network blips,
   * reloads). Once everyone has left, the code starts afresh for whoever uses
   * it next.
   */
  async admitToPairing(id) {
    const now = Date.now();
    let p = (await this.ctx.storage.get('pair')) || null;
    if (!p || this.live().length === 0 && !p.members.includes(id)) p = { born: now, members: [] };
    if (!p.members.includes(id)) {
      if (now - p.born > PAIR_WINDOW_MS) return new Response('code expired', { status: 410 });
      if (p.members.length >= PAIR_MAX_DEVICES) return new Response('code in use', { status: 429 });
      p.members.push(id);
    }
    await this.ctx.storage.put('pair', p);
    return null;
  }

  /** Called on a network's own room: may this network make one more pairing join? */
  async pairGate() {
    const now = Date.now();
    const g = (await this.ctx.storage.get('gate')) || { start: now, count: 0 };
    if (now - g.start > PAIR_GATE_WINDOW_MS) { g.start = now; g.count = 0; }
    g.count++;
    await this.ctx.storage.put('gate', g);
    return new Response(null, { status: g.count > PAIR_JOINS_PER_WINDOW ? 429 : 204 });
  }

  /**
   * Names in a room are unique, so one device can't pass itself off as another
   * by copying its name. A clash gets a number: "Asha's Pixel (2)".
   */
  uniqueName(name, selfId) {
    const fold = (s) => s.normalize('NFKC').toLocaleLowerCase();
    const taken = new Set(this.live().map((w) => w.deserializeAttachment())
      .filter((p) => p && p.id !== selfId).map((p) => fold(p.name)));
    if (!taken.has(fold(name))) return name;
    const stem = [...name].slice(0, 54).join('');
    for (let i = 2; i < 100; i++) if (!taken.has(fold(`${stem} (${i})`))) return `${stem} (${i})`;
    return `${stem} (${selfId.slice(0, 4)})`;
  }

  /** Open sockets, minus any that stopped sending keep-alives (half-open). */
  live(except) {
    const now = Date.now();
    return this.ctx.getWebSockets().filter((ws) => {
      if (ws === except || ws.readyState !== WebSocket.OPEN) return false;
      const lastPing = this.ctx.getWebSocketAutoResponseTimestamp(ws)?.getTime();
      const lastSeen = lastPing ?? ws.deserializeAttachment()?.since ?? now;
      if (now - lastSeen > STALE_MS) {
        try { ws.close(4001, 'stale'); } catch {}
        return false;
      }
      return true;
    });
  }

  peers(self) {
    const mine = self.deserializeAttachment()?.id;
    return this.live(self)
      .map((ws) => ws.deserializeAttachment())
      .filter((p) => p && p.id !== mine)
      .map(({ id, name, device }) => ({ id, name, device }));
  }

  broadcastPeers(skip) {
    for (const ws of this.live()) {
      if (ws === skip) continue;
      try { ws.send(JSON.stringify({ type: 'peers', peers: this.peers(ws) })); } catch {}
    }
  }

  async webSocketMessage(ws, raw) {
    const me = ws.deserializeAttachment();
    if (!me) return;
    const size = typeof raw === 'string' ? raw.length : raw.byteLength;
    // Per-connection rate limit: a client this chatty is broken or abusive.
    const now = Date.now();
    if (now - me.window > 60_000) { me.window = now; me.count = 0; }
    me.count++;
    ws.serializeAttachment(me);
    if (me.count > MAX_MSGS_PER_MINUTE || size > MAX_MSG_BYTES) {
      try { ws.close(4008, 'rate limited'); } catch {}
      return;
    }
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }
    if (msg.type === 'signal') {
      const target = this.live().find((s) => s.deserializeAttachment()?.id === msg.to);
      if (target) target.send(JSON.stringify({ type: 'signal', from: me.id, data: msg.data }));
    } else if (msg.type === 'rename') {
      const name = sanitiseName(msg.name);
      if (name) me.name = this.uniqueName(name, me.id);
      ws.serializeAttachment(me);
      this.broadcastPeers();
    }
  }

  async webSocketClose(ws, code) {
    try { ws.close(code === 1005 ? 1000 : code); } catch {}
    this.broadcastPeers(ws);
  }

  async webSocketError(ws) {
    this.broadcastPeers(ws);
  }
}
