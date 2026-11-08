/* Ferry for Chrome: the engine.
 *
 * Runs in the extension's offscreen document, which Chrome keeps open for as
 * long as the browser runs. That is what makes this computer show up on your
 * phone without a Ferry tab open, and lets a transfer carry on while the side
 * panel is closed. Same protocol as the website (public/app.js) and the Android
 * app, so all three talk to each other unchanged:
 *
 *   - signalling: a WebSocket to the server, one room per network (and one per
 *     pairing code); it only introduces devices and relays SDP/ICE;
 *   - files: one ordered data channel "bd", JSON control messages keyed by `t`,
 *     binary frames = 8-byte big-endian float64 offset + body.
 *
 * The UI (panel.js) talks to this over a BroadcastChannel, which carries File
 * objects; the service worker (background.js) does what only it may do here:
 * notifications, downloads and the toolbar badge.
 */
import { SERVER } from './config.js';

/* ------------------------------------------------------------ throughput */

const PREFERRED_CHUNK = 256 * 1024;
// A send whose connection closed under it: the other side reloaded, locked
// its screen, closed Ferry or left the Wi-Fi.
const LOST = 'Connection lost. The other device closed Ferry, locked its screen or left the Wi‑Fi';
const HIGH_WATER = 8 * 1024 * 1024;
const LOW_WATER = 2 * 1024 * 1024;
// Same network only: no STUN, no TURN, host candidates only (see app.js).
const ICE_SERVERS = [];
const CONNECT_DEADLINE_MS = 15_000;
const FRAME_HEADER = 8;
const PAIR_TTL_MS = 15 * 60_000;
const KEEP_TRANSFERS = 40;
// Text (the clipboard) goes as JSON control messages in parts; see app.js.
const TEXT_PART = 8192;
const TEXT_MAX = 1_000_000;
const TEXT_ACK_MS = 20_000;
const TEXT_MAX_PARTS = Math.ceil(TEXT_MAX / (TEXT_PART - 1)) + 1;   // parts split at surrogate pairs
const TEXT_IN_FLIGHT = 4;                    // unfinished incoming texts kept per device
// A sender's chunk is chunkSizeFor() minus the header: 16 KiB-8 to 256 KiB-8.
const MIN_SENT_CHUNK = 1024, MAX_SENT_CHUNK = 1 << 20;

/* The same receive rules as the website's app.js (cleanName, localCandidate):
   what you accept is what is saved, and a device must be on this network. */
function cleanName(n) {
  let s = String(n ?? '').normalize('NFC').replace(/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/gu, '')
    .replace(/[\\/:*?"<>|]/g, '_').replace(/\s+/g, ' ').trim().replace(/^\.+/, '');
  const chars = [...s];
  if (chars.length > 180) {
    const dot = s.lastIndexOf('.');
    const ext = dot > 0 && s.length - dot <= 16 ? [...s.slice(dot)] : [];
    s = chars.slice(0, 180 - ext.length).join('') + ext.join('');
  }
  return s || 'Ferry file';
}
const tagOf = (id) => String(id || '').slice(0, 4).toUpperCase();
const whoIs = (id) => `${peers.get(id)?.name || 'A device'} · ${tagOf(id)}`;
function localAddress(addr) {
  addr = String(addr || '').toLowerCase();
  if (addr.endsWith('.local') || addr.includes(':')) return true;
  const m = addr.match(/^(\d+)\.(\d+)\.\d+\.\d+$/);
  if (!m) return false;
  const [a, b] = [+m[1], +m[2]];
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 169 && b === 254) || a === 127;
}
function localCandidate(line) {
  const f = String(line || '').replace(/^a=/, '').split(' ');
  return f.length >= 8 && /^candidate:/.test(f[0]) && f[7] === 'host' && localAddress(f[4]);
}
const localOnlySdp = (sdp) => String(sdp).split('\r\n').filter((l) => !l.startsWith('a=candidate:') || localCandidate(l)).join('\r\n');

const chunkSizeFor = (pc) =>
  Math.max(16 * 1024, Math.min(PREFERRED_CHUNK, (pc.sctp?.maxMessageSize || 65536) - 1024));

/* ----------------------------------------------------------------- utils */

const fmtBytes = (n) => {
  if (n < 1024) return `${n} B`;
  const u = ['KB', 'MB', 'GB', 'TB'];
  let i = -1;
  do { n /= 1024; i++; } while (n >= 1024 && i < u.length - 1);
  return `${n < 10 ? n.toFixed(1) : Math.round(n)} ${u[i]}`;
};
const fmtRate = (bps) => (bps >= 1e6 ? `${(bps / 1e6).toFixed(1)} MB/s` : `${Math.round(bps / 1e3)} KB/s`);

const ADJ = ['Amber', 'Brisk', 'Copper', 'Dusk', 'Ember', 'Flint', 'Glass', 'Halcyon', 'Indigo', 'Jade', 'Kite', 'Lunar', 'Mint', 'Nimbus', 'Onyx', 'Pewter'];
const NOUN = ['Falcon', 'Otter', 'Heron', 'Lynx', 'Marten', 'Osprey', 'Panther', 'Quail', 'Raven', 'Shrike', 'Tapir', 'Vireo', 'Wren', 'Yak', 'Zebu', 'Ibis'];

const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch {} },
};

function myName() {
  let n = store.get('bd.name');
  if (!n) {
    n = `${ADJ[(Math.random() * ADJ.length) | 0]} ${NOUN[(Math.random() * NOUN.length) | 0]}`;
    store.set('bd.name', n);
  }
  return n;
}
function deviceKind() {
  const ua = navigator.userAgent;
  if (/Macintosh/.test(ua)) return 'mac';
  if (/Windows/.test(ua)) return 'windows';
  return 'desktop';
}

/**
 * Unlike a browser tab, the extension keeps one token for good: this computer
 * is the same device across Chrome restarts, so a phone that knew it still does.
 */
const TOKEN = (() => {
  const have = store.get('bd.tok');
  if (have) return have;
  const t = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(24))))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  store.set('bd.tok', t);
  return t;
})();

const wsUrl = (extra = {}) => {
  const u = new URL('/ws', SERVER);
  u.protocol = u.protocol === 'https:' ? 'wss:' : 'ws:';
  for (const [k, v] of Object.entries({ name: ME.name, device: ME.device, token: TOKEN, ...extra })) u.searchParams.set(k, v);
  return u.href;
};

/* ---------------------------------------------------- talking to the rest */

const ui = new BroadcastChannel('ferry');
/** Ask the service worker to do something only it can (download, notify, badge). */
const bg = (msg) => chrome.runtime.sendMessage({ target: 'bg', ...msg }).catch(() => null);

/* ------------------------------------------------------------- signalling */

const ME = { id: null, name: myName(), device: deviceKind(), room: null };
const peers = new Map();
const autoPeers = new Map();   // seen in this network's room
const pairPeers = new Map();   // seen in the room for a pairing code
const conns = new Map();
let ws = null;
let wsRetry = 0;
let online = false;

setInterval(() => {
  if (ws?.readyState === 1) ws.send('{"type":"ping"}');
  if (PAIR.ws?.readyState === 1) PAIR.ws.send('{"type":"ping"}');
}, 50_000);

function connect() {
  const sock = new WebSocket(wsUrl());
  ws = sock;
  sock.onopen = () => { wsRetry = 0; };
  sock.onclose = () => {
    if (ws !== sock) return;
    online = false; emit(true);
    wsRetry = Math.min(wsRetry + 1, 6);
    setTimeout(() => { if (ws === sock) connect(); }, 500 * 2 ** wsRetry);
  };
  sock.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.type === 'welcome') {
      ME.id = msg.id; ME.room = msg.room; online = true;
      setPeersFrom(autoPeers, msg.peers);
    } else if (msg.type === 'peers') {
      setPeersFrom(autoPeers, msg.peers);
    } else if (msg.type === 'signal') {
      connFor(msg.from).handleSignal(msg.data);
    }
  };
}
function signal(to, data) {
  const via = !autoPeers.has(to) && pairPeers.has(to) && PAIR.ws?.readyState === 1 ? PAIR.ws : ws;
  return via?.readyState === 1 && via.send(JSON.stringify({ type: 'signal', to, data }));
}

/* Pairing with a code: a second, private room for networks that make two
   devices on the same Wi-Fi look like different networks (see app.js). */
const PAIR = { code: null, hosting: false, started: 0, met: false, ws: null, retry: 0 };

function newPairCode() {
  const n = crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000;
  return String(n).padStart(6, '0');
}

function startPair(code, hosting) {
  stopPair();
  Object.assign(PAIR, { code, hosting, started: Date.now(), met: false, retry: 0 });
  openPairSocket();
  emit(true);
}

function openPairSocket() {
  const code = PAIR.code;
  if (!code) return;
  const sock = new WebSocket(wsUrl({ pair: code }));
  PAIR.ws = sock;
  sock.onopen = () => { PAIR.retry = 0; };
  sock.onclose = () => {
    if (PAIR.ws !== sock || !PAIR.code) return;
    PAIR.ws = null;
    PAIR.retry = Math.min(PAIR.retry + 1, 6);
    setTimeout(() => { if (PAIR.code === code && !PAIR.ws) openPairSocket(); }, 500 * 2 ** PAIR.retry);
  };
  sock.onmessage = (ev) => {
    if (PAIR.ws !== sock) return;
    const msg = JSON.parse(ev.data);
    if (msg.type === 'welcome') {
      ME.id = ME.id || msg.id;
      setPeersFrom(pairPeers, msg.peers);
    } else if (msg.type === 'peers') {
      setPeersFrom(pairPeers, msg.peers);
    } else if (msg.type === 'signal') {
      connFor(msg.from).handleSignal(msg.data);
    }
  };
}

function stopPair() {
  const sock = PAIR.ws;
  PAIR.ws = null;
  PAIR.code = null;
  if (sock) { sock.onclose = sock.onmessage = null; try { sock.close(); } catch {} }
  if (pairPeers.size) setPeersFrom(pairPeers, []);
  emit(true);
}
setInterval(() => {
  if (PAIR.code && !PAIR.met && Date.now() - PAIR.started > PAIR_TTL_MS) stopPair();
}, 10_000);

/* Network switches. A laptop moving networks usually loses the socket and
   rejoins on its own; if the old one survives, the room check catches it. A
   computer rarely moves, so poll gently: every check is a request against the
   Free plan's daily allowance, and this runs on every computer all day. */
let roomCheckBusy = false;
let rejoinedFor = null;
async function checkRoom() {
  if (roomCheckBusy || !ME.room) return;
  roomCheckBusy = true;
  try {
    const r = await fetch(new URL('/api/whoami', SERVER), { cache: 'no-store' });
    const { room } = await r.json();
    if (room === ME.room) rejoinedFor = null;
    else if (room && ME.room && room !== rejoinedFor) {
      console.info('[ferry] network changed; rejoining');
      rejoinedFor = room;
      reconnectWs();
    }
  } catch {} finally { roomCheckBusy = false; }
}
function reconnectWs() {
  const old = ws;
  ws = null;
  if (old) { old.onclose = old.onmessage = null; try { old.close(); } catch {} }
  ME.room = null; online = false;
  wsRetry = 0;
  emit(true);
  connect();
}
function networkMayHaveChanged() {
  checkRoom();
  setTimeout(checkRoom, 2500);
}
addEventListener('online', networkMayHaveChanged);
navigator.connection?.addEventListener?.('change', networkMayHaveChanged);
setInterval(() => { if (!peers.size) checkRoom(); }, 30 * 60_000);

function setPeersFrom(source, list) {
  source.clear();
  for (const p of list) source.set(p.id, p);
  peers.clear();
  for (const p of autoPeers.values()) peers.set(p.id, p);
  for (const p of pairPeers.values()) if (!peers.has(p.id)) peers.set(p.id, p);
  if (pairPeers.size) PAIR.met = true;
  // Keep an established connection whose peer is only mid-reconnect to the
  // signalling server (see app.js).
  for (const id of [...conns.keys()]) {
    if (peers.has(id)) continue;
    const c = conns.get(id);
    if (c.pc.connectionState === 'connected') continue;
    c.close(); conns.delete(id);
  }
  emit(true);
}

/** Split text for the wire, never through the middle of an emoji (a surrogate pair). */
function textParts(s) {
  const out = [];
  for (let i = 0; i < s.length;) {
    let j = Math.min(s.length, i + TEXT_PART);
    if (j < s.length && s.charCodeAt(j) >= 0xdc00 && s.charCodeAt(j) <= 0xdfff) j--;
    out.push(s.slice(i, j));
    i = j;
  }
  return out.length ? out : [''];
}

/* ------------------------------------------------- peer connection (RTC) */

class Conn {
  constructor(peerId) {
    this.peerId = peerId;
    this.polite = ME.id > peerId;
    this.makingOffer = false;
    this.queue = [];
    this.sending = false;
    this.rx = null;
    this.verdicts = new Map();
    this.seq = 0;
    this.sendingT = null;
    this.pendingVerdictId = null;
    this.pendingAccept = null;
    this.replyWaiter = null;
    this.doneIds = new Set();
    this.texts = [];             // text waiting for the channel to open
    this.textOut = new Map();    // text id -> {t, done}, awaiting the peer's textgot
    this.textIn = new Map();     // text id -> parts received so far
    this.pc = new RTCPeerConnection({ iceServers: ICE_SERVERS });

    this.localCands = 0;
    this.remoteCands = 0;
    this.pc.onicecandidate = ({ candidate }) => {
      if (!candidate) return;
      this.localCands++;
      signal(peerId, { candidate });
    };
    this.pc.onnegotiationneeded = async () => {
      try {
        this.makingOffer = true;
        await this.pc.setLocalDescription();
        signal(peerId, { description: this.pc.localDescription });
      } finally { this.makingOffer = false; }
    };
    this.pc.ondatachannel = ({ channel }) => this.bind(channel);
    this.pc.onconnectionstatechange = () => {
      const st = this.pc.connectionState;
      if (st === 'failed' || st === 'closed') {
        this.fallback('Direct connection failed');
        this.failAll('Direct connection failed');
      } else if (st === 'connected') {
        this.detectPath();
      }
    };
  }

  static create(peerId) {
    const c = new Conn(peerId);
    c.bind(c.pc.createDataChannel('bd', { ordered: true }));
    return c;
  }

  bind(channel) {
    this.dc = channel;
    this.dc.binaryType = 'arraybuffer';
    this.dc.bufferedAmountLowThreshold = LOW_WATER;
    this.dc.onopen = () => { this.clearWatchdog(); this.flushTexts(); this.pump(); };
    this.dc.onmessage = (ev) => this.onMessage(ev.data);
    this.dc.onclose = () => this.failAll('Connection closed');
  }

  async handleSignal({ description, candidate }) {
    const pc = this.pc;
    try {
      if (description) {
        const collision = description.type === 'offer' && (this.makingOffer || pc.signalingState !== 'stable');
        if (collision && !this.polite) return;
        if (collision) await pc.setLocalDescription({ type: 'rollback' });
        await pc.setRemoteDescription({ type: description.type, sdp: localOnlySdp(description.sdp) });
        if (description.type === 'offer') {
          await pc.setLocalDescription();
          signal(this.peerId, { description: pc.localDescription });
        }
      } else if (candidate) {
        if (candidate.candidate && !localCandidate(candidate.candidate)) return;   // not this network
        if (candidate.candidate) this.remoteCands++;
        await pc.addIceCandidate(candidate).catch(() => {});
      }
    } catch (e) { console.warn('signal error', e); }
  }

  /* ---- control plane ---- */

  sendCtrl(obj) {
    const ch = this.dc;
    if (ch?.readyState !== 'open') return false;
    try { ch.send(JSON.stringify(obj)); return true; } catch { return false; }
  }

  ask(msg) {
    return new Promise((resolve) => {
      const finish = (m) => { clearTimeout(timer); this.replyWaiter = null; resolve(m); };
      const timer = setTimeout(() => finish(null), 30_000);
      this.replyWaiter = (m) => { if (m.id === msg.id) finish(m); };
      if (!this.sendCtrl(msg)) finish(null);
    });
  }

  /* ---- sending ---- */

  enqueue(files) {
    const who = peers.get(this.peerId)?.name || 'peer';
    for (const f of files) {
      const t = newTransfer({ dir: 'out', name: f.name, size: f.size, peer: who, peerId: this.peerId });
      if (this.dc?.readyState !== 'open') noteTransfer(t, `Connecting to ${who}…`);
      t.cancel = () => this.cancel(t);
      this.queue.push({ file: f, t });
    }
    this.armWatchdog();
    this.pump();
  }

  armWatchdog() {
    if (this.watchdog || this.dc?.readyState === 'open') return;
    const deadline = Date.now() + CONNECT_DEADLINE_MS;
    const tick = () => {
      this.watchdog = null;
      if (this.dc?.readyState === 'open') return;
      const st = this.pc.connectionState;
      const ice = this.pc.iceConnectionState;
      for (const { t } of [...this.queue, ...this.texts]) noteTransfer(t, `Connecting… (${ice})`);
      if (st === 'failed' || ice === 'failed') return void this.fallback('Direct connection failed');
      if (Date.now() > deadline) return void this.fallback('Direct connection timed out');
      this.watchdog = setTimeout(tick, 1000);
    };
    this.watchdog = setTimeout(tick, 1000);
  }

  cancel(t) {
    const i = this.queue.findIndex((j) => j.t === t);
    if (i >= 0) { this.queue.splice(i, 1); failTransfer(t, 'Cancelled'); return; }
    if (this.sendingT === t) {
      t.cancelled = true;
      this.sendCtrl({ t: 'cancel' });
      this.verdicts.get(this.pendingVerdictId)?.(false);
    }
  }

  /** Give up, visibly: there is no server route to fall back to, by design. */
  fallback(reason) {
    this.clearWatchdog();
    const diag = ` [${this.pc.iceConnectionState} · here ${this.localCands} · there ${this.remoteCands}]`;
    for (const { file, t } of this.queue.splice(0)) {
      failTransfer(t, `${reason} — both devices must be on the same network${diag}`, () => {
        connForSending(this.peerId).enqueue([file]);
      });
    }
    for (const { text, t } of this.texts.splice(0)) {
      failTransfer(t, `${reason} — both devices must be on the same network${diag}`, () => {
        connForSending(this.peerId).enqueueText(text);
      });
    }
  }

  /* ---- text: the clipboard (protocol notes in app.js) ---- */

  enqueueText(text) {
    const who = peers.get(this.peerId)?.name || 'peer';
    const t = newTextTransfer({ dir: 'out', text, peer: who, peerId: this.peerId });
    const job = { text, t };
    t.cancel = () => { this.texts = this.texts.filter((j) => j !== job); failTransfer(t, 'Cancelled'); };
    this.texts.push(job);
    if (this.dc?.readyState !== 'open') noteTransfer(t, `Connecting to ${who}…`);
    this.armWatchdog();
    this.flushTexts();
  }

  flushTexts() {
    if (this.dc?.readyState !== 'open') return;
    for (const job of this.texts.splice(0)) this.sendTextNow(job);
  }

  sendTextNow({ text, t }) {
    const who = peers.get(this.peerId)?.name || 'the other device';
    const id = `x${++this.seq}`;
    const parts = textParts(text);
    if (!parts.every((p, i) => this.sendCtrl({ t: 'text', id, part: i, parts: parts.length, text: p }))) {
      return void failTransfer(t, 'Connection lost', () => connForSending(this.peerId).enqueueText(text));
    }
    noteTransfer(t, `Sending to ${who}…`);
    const done = () => { clearTimeout(timer); this.textOut.delete(id); };
    const timer = setTimeout(() => {
      done();
      failTransfer(t, `${who} didn't answer — Ferry there may need an update`);
    }, TEXT_ACK_MS);
    this.textOut.set(id, { t, done });
    t.cancel = () => { done(); failTransfer(t, 'Cancelled'); };
  }

  onTextPart({ id, part, parts, text }) {
    // No Accept for text, so keep what a peer can make us hold small.
    if (typeof id !== 'string' || id.length > 64 || typeof text !== 'string' || text.length > TEXT_PART ||
        !Number.isInteger(parts) || parts < 1 || parts > TEXT_MAX_PARTS ||
        !Number.isInteger(part) || part < 0 || part >= parts) return;
    const now = Date.now();
    for (const [k, v] of this.textIn) if (now - v.at > TEXT_ACK_MS) this.textIn.delete(k);
    let a = this.textIn.get(id);
    if (!a) {
      if (this.textIn.size >= TEXT_IN_FLIGHT) this.textIn.delete(this.textIn.keys().next().value);
      a = { parts: new Array(parts), got: 0, len: 0, at: now };
      this.textIn.set(id, a);
    }
    if (a.parts.length !== parts || a.parts[part] !== undefined) return;
    a.parts[part] = text;
    a.got++;
    a.len += text.length;
    if (a.len > TEXT_MAX * 2) { this.textIn.delete(id); return; }
    if (a.got < parts) return;
    this.textIn.delete(id);
    this.sendCtrl({ t: 'textgot', id });
    const who = whoIs(this.peerId);
    const t = newTextTransfer({ dir: 'in', text: a.parts.join(''), peer: who, peerId: this.peerId });
    finishText(t, 'Received');
    bg({ type: 'text', tid: t.id, who: who || 'A device', preview: t.text.slice(0, 300) });
  }

  onTextGot({ id }) {
    const o = this.textOut.get(id);
    if (!o) return;
    o.done();
    finishText(o.t, 'Sent');
  }

  clearWatchdog() {
    if (this.watchdog) { clearTimeout(this.watchdog); this.watchdog = null; }
  }

  async pump() {
    if (this.sending || this.dc?.readyState !== 'open') return;
    const job = this.queue.shift();
    if (!job) return;
    this.sending = true;
    try {
      await this.sendOne(job.file, job.t);
    } catch (e) {
      // The channel can close while a send is waiting (for the buffer to drain,
      // or for the next piece of the file); the browser's own error for that
      // means nothing to anyone, so say what happened and offer a retry.
      if (job.t.lost || this.dc?.readyState !== 'open') {
        failTransfer(job.t, LOST, () => connForSending(this.peerId).enqueue([job.file]));
      } else failTransfer(job.t, e.message || 'Transfer failed');
    } finally {
      this.sending = false;
      this.sendingT = null;
      this.pump();
    }
  }

  async sendOne(file, t) {
    const dc = this.dc;
    const id = `f${++this.seq}`;
    const who = peers.get(this.peerId)?.name || 'the other device';

    this.sendingT = t;
    t.path = this.path || (await this.detectPath());
    dc.send(JSON.stringify({ t: 'file', id, name: file.name, size: file.size, type: file.type }));

    noteTransfer(t, `Waiting for ${who} to accept…`);
    this.pendingVerdictId = id;
    const accepted = await this.awaitVerdict(id);
    this.pendingVerdictId = null;
    if (t.cancelled) throw new Error('Cancelled');
    if (!accepted) throw new Error(`${who} declined`);

    const chunk = chunkSizeFor(this.pc) - FRAME_HEADER;
    const meter = newMeter();
    let sent = 0;
    while (sent < file.size) {
      if (t.cancelled) throw new Error('Cancelled');
      if (dc.readyState !== 'open') throw new Error('Connection lost');
      if (dc.bufferedAmount > HIGH_WATER) await drained(dc);
      const body = new Uint8Array(await file.slice(sent, sent + chunk).arrayBuffer());
      const frame = new Uint8Array(FRAME_HEADER + body.byteLength);
      new DataView(frame.buffer).setFloat64(0, sent);
      frame.set(body, FRAME_HEADER);
      dc.send(frame.buffer);
      sent += body.byteLength;
      updateTransfer(t, sent, meter(sent));
    }
    while (dc.bufferedAmount > 0 && dc.readyState === 'open') await new Promise((r) => setTimeout(r, 40));
    const reply = await this.ask({ t: 'sent', id, size: file.size, chunk });
    if (t.cancelled) throw new Error('Cancelled');
    if (reply?.t !== 'got') throw new Error('The other device did not confirm receipt');
    this.sendingT = null;
    finishTransfer(t, 'Sent');
  }

  async detectPath() {
    try {
      const stats = await this.pc.getStats();
      for (const r of stats.values()) {
        if (r.type !== 'candidate-pair' || r.state !== 'succeeded') continue;
        if (r.nominated === false) continue;
        const l = stats.get(r.localCandidateId), m = stats.get(r.remoteCandidateId);
        const far = m?.address ?? m?.ip;
        if (far && !localAddress(far)) {        // a public address: not this network
          this.pc.close();
          this.failAll('That device is not on this network');
          return null;
        }
        this.path = l?.candidateType === 'host' && m?.candidateType === 'host' ? 'direct, local network' : 'direct';
        const rtt = r.currentRoundTripTime ? Math.round(r.currentRoundTripTime * 1000) : null;
        if (rtt) this.path += ` · ${rtt}ms`;
        for (const { t } of this.queue) t.path = this.path;
        return this.path;
      }
    } catch {}
    return null;
  }

  awaitVerdict(id) {
    return new Promise((resolve) => {
      const finish = (v) => { clearTimeout(timer); this.verdicts.delete(id); resolve(v); };
      const timer = setTimeout(() => finish(false), 120_000);
      this.verdicts.set(id, finish);
    });
  }

  /* ---- receiving ---- */

  async onMessage(data) {
    if (typeof data === 'string') {
      const msg = JSON.parse(data);
      if (msg.t === 'file') await this.beginReceive(msg);
      else if (msg.t === 'accept' || msg.t === 'reject') this.verdicts.get(msg.id)?.(msg.t === 'accept');
      else if (msg.t === 'need' || msg.t === 'got') this.replyWaiter?.(msg);
      else if (msg.t === 'sent') this.onSent(msg);
      else if (msg.t === 'cancel') this.remoteCancel();
      else if (msg.t === 'text') this.onTextPart(msg);
      else if (msg.t === 'textgot') this.onTextGot(msg);
      return;
    }
    this.onData(data);
  }

  /** One framed chunk, written at its offset in the file on disk. */
  onData(buf) {
    const rx = this.rx;
    if (!rx || buf.byteLength <= FRAME_HEADER) return;
    const off = new DataView(buf, 0, FRAME_HEADER).getFloat64(0);
    const body = new Uint8Array(buf, FRAME_HEADER);
    if (!Number.isSafeInteger(off) || off < 0 || off + body.byteLength > rx.size) {   // outside the accepted file
      this.sendCtrl({ t: 'cancel' });
      return void this.discardRx('The other device sent more than it offered');
    }
    if (rx.seen.has(off)) return;
    rx.seen.add(off);
    rx.received += body.byteLength;
    rx.wq = rx.wq.then(() => rx.writer.write({ type: 'write', position: off, data: body }))
      .catch((e) => this.writeFailed(rx, e));
    updateTransfer(rx.t, rx.received, rx.meter(rx.received));
    if (rx.received >= rx.size && !rx.ending) { rx.ending = true; this.endReceive(); }
  }

  /** The disk said no (full, or the file was removed): stop both ends. */
  writeFailed(rx, e) {
    if (rx.failed) return;
    rx.failed = true;
    if (this.rx !== rx) return;
    this.sendCtrl({ t: 'cancel' });
    this.discardRx(`Could not save: ${e?.message || 'disk error'}`);
  }

  onSent({ id, size, chunk }) {
    const rx = this.rx;
    if (rx && rx.id === id) {
      if (size !== rx.size || !Number.isSafeInteger(chunk) || chunk < MIN_SENT_CHUNK || chunk > MAX_SENT_CHUNK) {
        this.sendCtrl({ t: 'cancel' });
        return void this.discardRx('The other device sent a bad transfer');
      }
      const missing = [];
      for (let o = 0; o < rx.size && missing.length < 4096; o += chunk) if (!rx.seen.has(o)) missing.push(o);
      if (missing.length) { this.sendCtrl({ t: 'need', id, offsets: missing }); return; }
    }
    if (this.doneIds.has(id)) this.sendCtrl({ t: 'got', id });
  }

  remoteCancel() {
    if (this.sendingT) this.sendingT.cancelled = true;
    this.verdicts.get(this.pendingVerdictId)?.(false);
    if (this.pendingAccept) { this.pendingAccept(false); this.pendingAccept = null; }
    if (this.rx) this.discardRx('Cancelled by the other device');
  }

  discardRx(reason) {
    const rx = this.rx;
    if (!rx) return;
    this.rx = null;
    rx.writer?.abort?.().catch(() => {});
    removeEntry(rx.entry);
    failTransfer(rx.t, reason);
  }

  async beginReceive({ id, name, size, type }) {
    // One offer at a time per device, with a sane id and size.
    if (typeof id !== 'string' || id.length > 64 || !Number.isSafeInteger(size) || size < 0 ||
        this.pendingAccept || this.rx) {
      if (typeof id === 'string' && id.length <= 64) this.sendCtrl({ t: 'reject', id });
      return;
    }
    name = cleanName(name);
    const who = whoIs(this.peerId);
    const t = newTransfer({ dir: 'in', name, size, peer: who, peerId: this.peerId });

    // Room membership is grouping, not permission: every file needs a yes,
    // given here in the panel or on the notification.
    const ask = askAccept(t, who, size);
    this.pendingAccept = ask.cancel;
    const allowed = await ask.promise;
    const wasCancelled = this.pendingAccept === null;
    this.pendingAccept = null;
    if (!allowed) {
      if (!wasCancelled) this.sendCtrl({ t: 'reject', id });
      failTransfer(t, wasCancelled ? 'Cancelled by the other device' : 'Declined');
      return;
    }

    t.path = this.path || (await this.detectPath());
    // Every file goes to disk as it arrives (the extension's private storage),
    // never into memory, so size is limited by disk space alone. Once complete
    // it is handed to Chrome's downloads, which copies it into Downloads.
    const rx = { id, name, size, type, received: 0, seen: new Set(), writer: null, handle: null,
                 entry: `rx-${crypto.randomUUID()}`, wq: Promise.resolve(), t, meter: newMeter() };
    try {
      const root = await navigator.storage.getDirectory();
      rx.handle = await root.getFileHandle(rx.entry, { create: true });
      rx.writer = await rx.handle.createWritable();
    } catch (e) {
      this.sendCtrl({ t: 'reject', id });
      failTransfer(t, `Could not save: ${e.message}`);
      return;
    }
    t.cancel = () => {
      this.sendCtrl({ t: 'cancel' });
      this.discardRx('Cancelled');
    };
    this.rx = rx;
    this.sendCtrl({ t: 'accept', id });
    if (size === 0) { rx.ending = true; this.endReceive(); }
  }

  async endReceive() {
    const rx = this.rx;
    this.rx = null;
    this.doneIds.add(rx.id);
    this.sendCtrl({ t: 'got', id: rx.id });
    try {
      await rx.wq;
      if (rx.failed) return;
      await rx.writer.close();
      await saveToDownloads(rx.t, await rx.handle.getFile(), rx.name, rx.entry);
    } catch (e) {
      removeEntry(rx.entry);
      failTransfer(rx.t, `Could not save: ${e.message}`);
    }
  }

  failAll(reason) {
    for (const { t } of [...this.queue, ...this.texts]) failTransfer(t, reason);
    this.queue.length = 0;
    this.texts.length = 0;
    for (const { t, done } of [...this.textOut.values()]) { done(); failTransfer(t, reason); }
    this.textIn.clear();
    if (this.sendingT) { this.sendingT.cancelled = this.sendingT.lost = true; this.sendingT = null; }
    this.discardRx(reason);
  }

  close() { this.clearWatchdog(); this.failAll('Peer left'); try { this.pc.close(); } catch {} }
}

function pruneDead(peerId) {
  const c = conns.get(peerId);
  // A dead connection, or one whose channel closed under it (the other side
  // reloaded or was closed): start afresh rather than queue onto it.
  const dead = ['failed', 'closed'].includes(c?.pc.connectionState) || ['closing', 'closed'].includes(c?.dc?.readyState);
  if (c && dead && !c.rx && !c.sendingT && !c.textOut.size) {
    try { c.pc.close(); } catch {}
    conns.delete(peerId);
  }
}
function connFor(peerId) {
  pruneDead(peerId);
  if (!conns.has(peerId)) conns.set(peerId, new Conn(peerId));
  return conns.get(peerId);
}
function connForSending(peerId) {
  pruneDead(peerId);
  if (!conns.has(peerId)) conns.set(peerId, Conn.create(peerId));
  return conns.get(peerId);
}

function drained(ch) {
  if (ch.bufferedAmount <= ch.bufferedAmountLowThreshold || ch.readyState !== 'open') return Promise.resolve();
  return new Promise((res) => {
    const done = () => { ch.removeEventListener('bufferedamountlow', done); ch.removeEventListener('close', done); res(); };
    ch.addEventListener('bufferedamountlow', done);
    ch.addEventListener('close', done);
  });
}

function newMeter() {
  let last = performance.now(), lastBytes = 0, ewma = 0;
  return (bytes) => {
    const now = performance.now();
    const dt = now - last;
    if (dt < 250) return ewma;
    const inst = ((bytes - lastBytes) * 1000) / dt;
    ewma = ewma ? ewma * 0.7 + inst * 0.3 : inst;
    last = now; lastBytes = bytes;
    return ewma;
  };
}

/* ------------------------------------------------- saving received files */

const downloads = new Map();   // Chrome download id -> { url, entry }

async function saveToDownloads(t, file, name, entry) {
  // The stored copy is named rx-<uuid>, so it carries no type, and Chrome would
  // sniff one from the first bytes and "fix" the extension to match: an MP3
  // (which opens with an ID3 text tag) came out as .txt. Raw bytes are never
  // renamed. slice() only relabels; nothing is copied.
  const url = URL.createObjectURL(file.slice(0, file.size, 'application/octet-stream'));
  const res = await bg({ type: 'download', url, name });
  if (!res?.downloadId) {
    URL.revokeObjectURL(url);
    throw new Error(res?.error || 'Chrome refused the download');
  }
  downloads.set(res.downloadId, { url, entry });
  t.downloadId = res.downloadId;
  finishTransfer(t, 'Saved to Downloads');
}

/** Chrome has copied the file out (or given up): drop our copy. */
function downloadSettled(downloadId, ok) {
  const d = downloads.get(downloadId);
  if (!d) return;
  downloads.delete(downloadId);
  URL.revokeObjectURL(d.url);
  removeEntry(d.entry);
  if (!ok) {
    const t = transfers.find((x) => x.downloadId === downloadId);
    if (t) { t.state = 'failed'; t.note = 'Chrome could not save the file'; emit(true); }
  }
}

async function removeEntry(entry) {
  try { await (await navigator.storage.getDirectory()).removeEntry(entry); } catch {}
}

/** Leftovers from a previous run (Chrome closed mid-transfer). */
async function sweepStorage() {
  try {
    const root = await navigator.storage.getDirectory();
    for await (const name of root.keys()) if (name.startsWith('rx-')) await root.removeEntry(name).catch(() => {});
  } catch {}
}

/* ------------------------------------------------------- transfer model */

const transfers = [];          // newest first
let transferSeq = 0;
let speedTip = null;

function newTransfer({ dir, name, size, peer, peerId }) {
  const t = { id: `t${++transferSeq}`, dir, name, size, peer: peer || 'peer', peerId,
              state: 'wait', note: '', done: 0, rate: 0, started: performance.now(), cancelled: false };
  transfers.unshift(t);
  // Old finished rows go first; anything still moving stays.
  for (let i = transfers.length - 1; transfers.length > KEEP_TRANSFERS && i >= 0; i--) {
    if (['done', 'failed'].includes(transfers[i].state)) transfers.splice(i, 1);
  }
  emit(true);
  return t;
}

function newTextTransfer({ dir, text, peer, peerId }) {
  const first = text.trim().split('\n')[0].slice(0, 120) || 'Text';
  const t = newTransfer({ dir, name: first, size: text.length, peer, peerId });
  t.kind = 'text';
  t.text = text;
  t.note = `${fmtChars(text.length)} · ${dir === 'out' ? 'to' : 'from'} ${t.peer}`;
  emit(true);
  return t;
}
const fmtChars = (n) => (n === 1 ? '1 character' : `${n.toLocaleString()} characters`);

function finishText(t, verb) {
  t.state = 'done';
  t.note = `${verb} · ${fmtChars(t.text.length)} · ${t.dir === 'out' ? 'to' : 'from'} ${t.peer}`;
  emit(true);
}

/** Copy to the system clipboard. The offscreen document may (reason CLIPBOARD). */
function copyText(text) {
  const ta = document.createElement('textarea');
  ta.value = text;
  document.body.append(ta);
  ta.select();
  let ok = false;
  try { ok = document.execCommand('copy'); } catch {}
  ta.remove();
  return ok;
}

function askAccept(t, who, size) {
  let done;
  const promise = new Promise((resolve) => {
    t.state = 'ask';
    t.note = `${who || 'A device'} wants to send ${fmtBytes(size)}`;
    done = (v) => {
      if (!t.answer) return;
      t.answer = null;
      t.state = 'wait';
      bg({ type: 'unask', tid: t.id });
      emit(true);
      resolve(v);
    };
    t.answer = done;
    bg({ type: 'ask', tid: t.id, who: who || 'A device', name: t.name, size: fmtBytes(size) });
    emit(true);
  });
  return { promise, cancel: () => done(false) };
}

function noteTransfer(t, text) { t.note = text; emit(true); }

function updateTransfer(t, done, rate) {
  t.state = 'active';
  t.done = done;
  t.rate = rate;
  emit();
}

function finishTransfer(t, verb) {
  const secs = (performance.now() - t.started) / 1000;
  t.state = 'done';
  t.done = t.size;
  t.note = `${verb} · ${fmtBytes(t.size)} in ${fmtSecs(secs)}`;
  t.title = fmtRate(t.size / secs);
  t.file = null;
  maybeSpeedTip(t.size, secs);
  emit(true);
}

function failTransfer(t, reason, retry) {
  if (t.state === 'done') return;
  if (t.answer) t.answer(false);
  t.state = 'failed';
  t.note = reason;
  t.retry = retry || null;
  emit(true);
}

const fmtEta = (s) =>
  !isFinite(s) || s < 0 ? '—'
  : s >= 3600 ? `${Math.floor(s / 3600)}:${String(Math.floor(s / 60) % 60).padStart(2, '0')}:00`
  : `${Math.floor(s / 60)}:${String(Math.round(s % 60)).padStart(2, '0')}`;
const fmtSecs = (s) => (s < 60 ? `${s.toFixed(1)}s` : fmtEta(s));

/* 2.4 GHz Wi-Fi is the usual cause of a slow transfer; see app.js. */
const SPEED_TIP_KEY = 'bd.speedTip';
function maybeSpeedTip(size, secs) {
  const rate = size / secs;
  if (size < 20e6 || rate >= 8e6) return;
  if (Date.now() - Number(store.get(SPEED_TIP_KEY) || 0) < 7 * 864e5) return;
  speedTip = fmtRate(rate);
}

/* ------------------------------------------------------- state to the UI */

const LABEL = { iphone: 'iPhone', android: 'Android', ipad: 'iPad', mac: 'Mac', windows: 'Windows PC', desktop: 'Computer' };

function snapshot() {
  const busy = new Map();
  for (const t of transfers) if (t.state === 'active' && t.peerId) busy.set(t.peerId, t.done / (t.size || 1));
  return {
    me: { name: ME.name, device: ME.device, online },
    peers: [...peers.values()].map((p) => ({
      id: p.id, name: p.name, device: p.device, kind: `${LABEL[p.device] || 'Device'} · ${tagOf(p.id)}`,
      paired: !autoPeers.has(p.id) && pairPeers.has(p.id), progress: busy.get(p.id) ?? null,
    })),
    transfers: transfers.map((t) => ({
      id: t.id, dir: t.dir, name: t.name, size: t.size, peer: t.peer, state: t.state, note: t.note,
      kind: t.kind || 'file', text: t.text ? t.text.slice(0, 2000) : null,
      title: t.title || t.path || '', done: t.done, rate: t.rate,
      eta: t.rate > 0 ? fmtEta((t.size - t.done) / t.rate) : '—',
      canRetry: !!t.retry, downloadId: t.downloadId ?? null,
    })),
    pair: {
      code: PAIR.code, hosting: PAIR.hosting,
      found: [...pairPeers.values()].filter((p) => !autoPeers.has(p.id)).map((p) => p.name),
      any: pairPeers.size > 0,
    },
    speedTip,
  };
}

/* Progress arrives per chunk (hundreds a second); the UI needs ~8 a second.
   Rate-limited by the clock rather than a timer, because timers in a hidden
   document are slowed to once a second. */
let lastEmit = 0;
let emitLater = null;
let lastBadge = '';
function emit(now = false) {
  const t = performance.now();
  if (!now && t - lastEmit < 120) {
    emitLater ??= setTimeout(() => { emitLater = null; emit(true); }, 150);
    return;
  }
  clearTimeout(emitLater); emitLater = null;
  lastEmit = t;
  ui.postMessage({ ev: 'state', state: snapshot() });
  badge();
}

/** The toolbar icon: "!" when a file is waiting for a yes, else overall progress. */
function badge() {
  const live = transfers.filter((t) => t.state === 'active');
  const text = transfers.some((t) => t.state === 'ask') ? '!'
    : live.length ? `${Math.floor((live.reduce((a, t) => a + t.done / (t.size || 1), 0) / live.length) * 100)}%`
    : '';
  if (text === lastBadge) return;
  lastBadge = text;
  bg({ type: 'badge', text, color: text === '!' ? '#ff9f0a' : '#0a84ff' });
}

/* ------------------------------------------------------------- commands */

ui.onmessage = ({ data: m }) => {
  const t = m.tid && transfers.find((x) => x.id === m.tid);
  switch (m.cmd) {
    case 'hello': emit(true); break;
    case 'send':
      if (peers.has(m.peerId) && m.files?.length) connForSending(m.peerId).enqueue(m.files);
      break;
    case 'sendText':
      if (peers.has(m.peerId) && typeof m.text === 'string' && m.text && m.text.length <= TEXT_MAX) connForSending(m.peerId).enqueueText(m.text);
      break;
    case 'copy': if (t?.text != null) ui.postMessage({ ev: 'copied', tid: t.id, ok: copyText(t.text) }); break;
    case 'accept': t?.answer?.(true); break;
    case 'decline': t?.answer?.(false); break;
    case 'cancel': if (t && !['done', 'failed'].includes(t.state)) (t.cancel ? t.cancel() : failTransfer(t, 'Cancelled')); break;
    case 'retry':
      if (t?.retry) { const again = t.retry; transfers.splice(transfers.indexOf(t), 1); again(); }
      break;
    case 'clear':
      for (let i = transfers.length - 1; i >= 0; i--) if (['done', 'failed'].includes(transfers[i].state)) transfers.splice(i, 1);
      emit(true);
      break;
    case 'rename': {
      const v = String(m.name || '').replace(/\s+/g, ' ').trim().slice(0, 32);
      if (v && v !== ME.name) {
        ME.name = v;
        store.set('bd.name', v);
        if (ws?.readyState === 1) ws.send(JSON.stringify({ type: 'rename', name: v }));
        if (PAIR.ws?.readyState === 1) PAIR.ws.send(JSON.stringify({ type: 'rename', name: v }));
      }
      store.set('bd.named', '1');
      emit(true);
      break;
    }
    case 'pair': {
      const code = m.code ? String(m.code).replace(/\D/g, '') : null;
      if (code && code.length === 6) { if (code !== PAIR.code) startPair(code, false); }
      else if (!PAIR.code) startPair(newPairCode(), true);
      break;
    }
    case 'stopPair': stopPair(); break;
    case 'speedTipOk': speedTip = null; store.set(SPEED_TIP_KEY, String(Date.now())); emit(true); break;
  }
};

// From the service worker: a notification button, or a finished download.
chrome.runtime.onMessage.addListener((m) => {
  if (m?.target !== 'engine') return;
  if (m.type === 'answer') transfers.find((x) => x.id === m.tid)?.answer?.(m.accept);
  else if (m.type === 'downloaded') downloadSettled(m.downloadId, m.ok);
  else if (m.type === 'copy') {
    const t = transfers.find((x) => x.id === m.tid);
    if (t?.text != null) copyText(t.text);
  }
});

if (new URLSearchParams(location.search).has('debug')) globalThis.__bd = { conns, transfers };

sweepStorage();
connect();
