/* Ferry (formerly BrowserDrop) client.
 *
 * Files move over a WebRTC data channel, directly between the two devices.
 * The server only relays SDP/ICE and, for the iPhone Share Sheet bridge,
 * briefly stores files that arrive over plain HTTP.
 */

/* ------------------------------------------------------------ throughput */

// Biggest message we'd like to push at once. The real limit is negotiated per
// connection (pc.sctp.maxMessageSize) and is often 256 KiB, sometimes 64 KiB,
// so this is only a ceiling -- see chunkSizeFor().
const PREFERRED_CHUNK = 256 * 1024;
// A send whose connection closed under it: the other side reloaded, locked
// its screen, closed Ferry or left the Wi-Fi.
const LOST = 'Connection lost. The other device closed Ferry, locked its screen or left the Wi‑Fi';
// Keep this many bytes in flight. Too low and we stall waiting on the wire;
// too high and a paused transfer holds megabytes of RAM per peer.
const HIGH_WATER = 8 * 1024 * 1024;
const LOW_WATER = 2 * 1024 * 1024;
// Above this, buffering the whole file in memory is a bad idea -- stream it
// straight to disk instead (Chrome/Edge only; Safari has no such API).
const STREAM_TO_DISK_ABOVE = 512 * 1024 * 1024;
// Without that picker (Safari, Firefox), big files go to the site's private
// storage on disk as they arrive, and are handed over as a file at the end.
// Held in memory, a big movie gets an iPhone tab killed mid-transfer.
const SPOOL_ABOVE = 64 * 1024 * 1024;
const canSpool = () => !!navigator.storage?.getDirectory &&
  'createWritable' in (globalThis.FileSystemFileHandle?.prototype ?? {});    // Safari 26+, Firefox, Chrome

// Same network only. No STUN and no TURN: the browser offers just its local
// addresses as ICE candidates, so two devices connect only if they can reach
// each other directly on the local network. That makes "same network" a
// guarantee rather than a missing button -- nothing here can route a transfer
// across the internet, and no third-party server ever sees these devices.
const ICE_SERVERS = [];
// Host candidates on a LAN connect in well under a second. Much longer than
// this means the devices genuinely can't reach each other.
const CONNECT_DEADLINE_MS = 15_000;
const FRAME_HEADER = 8;                      // float64 byte offset before each chunk
// Text (the clipboard) travels as JSON control messages, split into parts so
// no message comes near the smallest SCTP limit (64 KiB): 8K characters is
// 48 KB at worst once UTF-8 encoded.
const TEXT_PART = 8192;
const TEXT_MAX = 1_000_000;                  // characters; longer belongs in a file
const TEXT_MAX_PARTS = Math.ceil(TEXT_MAX / (TEXT_PART - 1)) + 1;   // parts split at surrogate pairs
const TEXT_IN_FLIGHT = 4;                    // unfinished incoming texts kept per device
// A sender's chunk is chunkSizeFor() minus the header: 16 KiB-8 to 256 KiB-8.
const MIN_SENT_CHUNK = 1024, MAX_SENT_CHUNK = 1 << 20;
const TEXT_ACK_MS = 20_000;                  // an older Ferry ignores text and never answers
const QS = new URLSearchParams(location.search);

const chunkSizeFor = (pc) =>
  Math.max(16 * 1024, Math.min(PREFERRED_CHUNK, (pc.sctp?.maxMessageSize || 65536) - 1024));

/* ----------------------------------------------------------------- utils */

const $ = (id) => document.getElementById(id);
const fmtBytes = (n) => {
  if (n < 1024) return `${n} B`;
  const u = ['KB', 'MB', 'GB', 'TB'];
  let i = -1;
  do { n /= 1024; i++; } while (n >= 1024 && i < u.length - 1);
  return `${n < 10 ? n.toFixed(1) : Math.round(n)} ${u[i]}`;
};
const fmtRate = (bps) => (bps >= 1e6 ? `${(bps / 1e6).toFixed(1)} MB/s` : `${Math.round(bps / 1e3)} KB/s`);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

/**
 * A received file name, cleaned once and then used both on screen and on disk,
 * so what you accept is what you get: no control or invisible formatting
 * characters (a right-to-left override can make "x.exe" read as "exe.x"), no
 * folders, and a long name is shortened in the middle, keeping its extension.
 */
function cleanName(n) {
  let s = String(n ?? '').normalize('NFC').replace(/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/gu, '')
    .replace(/[\\/:*?"<>|]/g, '_').replace(/\s+/g, ' ').trim().replace(/^\.+/, '');
  const chars = [...s];
  if (chars.length > 200) {
    const dot = s.lastIndexOf('.');
    const ext = dot > 0 && s.length - dot <= 16 ? [...s.slice(dot)] : [];
    s = chars.slice(0, 200 - ext.length).join('') + ext.join('');
  }
  return s || 'file';
}

/** Short tag from a device's id, so two devices with similar names can be told apart. */
const tagOf = (id) => String(id || '').slice(0, 4).toUpperCase();
/** Who a transfer is from or to, as shown in prompts and rows: name and tag. */
const whoIs = (id) => `${peers.get(id)?.name || 'A device'} · ${tagOf(id)}`;

/**
 * Same network only, enforced on what the other side claims too: we never
 * advertise a public address (no STUN/TURN), and we refuse to connect to one.
 * Remote candidates must be host candidates on a private, link-local or .local
 * address (IPv6 can't be told apart from a LAN address, so it is allowed).
 */
function localCandidate(line) {
  const f = String(line || '').replace(/^a=/, '').split(' ');
  if (f.length < 8 || !/^candidate:/.test(f[0])) return false;
  const addr = f[4], type = f[7];
  if (type !== 'host') return false;
  return localAddress(addr);
}
function localAddress(addr) {
  addr = String(addr || '').toLowerCase();
  if (addr.endsWith('.local') || addr.includes(':')) return true;
  const m = addr.match(/^(\d+)\.(\d+)\.\d+\.\d+$/);
  if (!m) return false;
  const [a, b] = [+m[1], +m[2]];
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 169 && b === 254) || a === 127;
}
const localOnlySdp = (sdp) => String(sdp).split('\r\n').filter((l) => !l.startsWith('a=candidate:') || localCandidate(l)).join('\r\n');

const ADJ = ['Amber', 'Brisk', 'Copper', 'Dusk', 'Ember', 'Flint', 'Glass', 'Halcyon', 'Indigo', 'Jade', 'Kite', 'Lunar', 'Mint', 'Nimbus', 'Onyx', 'Pewter'];
const NOUN = ['Falcon', 'Otter', 'Heron', 'Lynx', 'Marten', 'Osprey', 'Panther', 'Quail', 'Raven', 'Shrike', 'Tapir', 'Vireo', 'Wren', 'Yak', 'Zebu', 'Ibis'];

function myName() {
  let n = localStorage.getItem('bd.name');
  if (!n) {
    n = `${ADJ[(Math.random() * ADJ.length) | 0]} ${NOUN[(Math.random() * NOUN.length) | 0]}`;
    localStorage.setItem('bd.name', n);
  }
  return n;
}
function deviceKind() {
  const ua = navigator.userAgent;
  if (/iPhone/.test(ua)) return 'iphone';
  if (/iPad/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) return 'ipad';
  if (/Macintosh/.test(ua)) return 'mac';
  if (/Android/.test(ua)) return 'android';
  if (/Windows/.test(ua)) return 'windows';
  return 'desktop';
}
// Inline SVG rather than emoji: emoji render differently on every platform and
// read as decoration. These are stroke icons on a 24px grid that take the
// surrounding text colour.
const svg = (body, size = 18) =>
  `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" ` +
  `stroke="currentColor" stroke-width="1.5" stroke-linecap="square">${body}</svg>`;
const GLYPH = {
  phone:  svg('<rect x="5" y="2.5" width="14" height="19" rx="2.5"/><line x1="10" y1="18.3" x2="14" y2="18.3"/>'),
  tablet: svg('<rect x="3.5" y="2.5" width="17" height="19" rx="2"/><line x1="10.5" y1="18.5" x2="13.5" y2="18.5"/>'),
  laptop: svg('<rect x="2.5" y="4.5" width="19" height="13" rx="1.6"/><line x1="1" y1="20.5" x2="23" y2="20.5"/>'),
};
const ICONS = {
  iphone: GLYPH.phone, android: GLYPH.phone, ipad: GLYPH.tablet,
  mac: GLYPH.laptop, windows: GLYPH.laptop, desktop: GLYPH.laptop,
};
// Rendered device art for the radar; the stroke glyphs above stay for small UI.
const ART = { iphone: 'phone', android: 'phone', ipad: 'tablet', mac: 'laptop', windows: 'desktop', desktop: 'desktop' };
const LABEL = { iphone: 'iPhone', android: 'Android', ipad: 'iPad', mac: 'Mac', windows: 'Windows PC', desktop: 'Computer' };
const art = (device) => `<img src="/img/dev-${ART[device] || 'laptop'}.webp" alt="" width="128" height="128" draggable="false">`;
const CLIP_ICON = svg('<rect x="6" y="4.5" width="12" height="16.5" rx="2"/><path d="M9.5 4.5V3h5v1.5"/><path d="M9 10h6M9 13.5h6M9 17h3.5"/>', 14);
const ARROW_UP = svg('<path d="M12 19V5"/><path d="M6 11l6-6 6 6"/>', 14);
const ARROW_DOWN = svg('<path d="M12 5v14"/><path d="M6 13l6 6 6-6"/>', 14);
const X_ICON = svg('<path d="M7 7l10 10M17 7L7 17"/>', 14);

/* ------------------------------------------------------------- signalling */

const ME = { id: null, name: myName(), device: deviceKind(), room: null };
const peers = new Map();       // peerId -> {id, name, device}
const conns = new Map();       // peerId -> Conn
let ws = null;
let wsRetry = 0;

/**
 * A private per-tab token. The server derives this device's public id from it,
 * so after a dropped signalling connection (network blip, server redeploy) the
 * tab rejoins as the SAME device: peers keep their connections to it and a
 * transfer in flight carries on. sessionStorage: survives a reload, not a new tab.
 */
const TAB_TOKEN = (() => {
  try {
    const have = sessionStorage.getItem('bd.tok');
    if (have) return have;
  } catch {}
  const t = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(24))))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  try { sessionStorage.setItem('bd.tok', t); } catch {}
  return t;
})();

// Keep-alive. Cloudflare drops a WebSocket idle for ~100 s; the server answers
// this exact string at the edge without waking anything, so it costs nothing.
setInterval(() => {
  if (ws?.readyState === 1) ws.send('{"type":"ping"}');
  if (PAIR.ws?.readyState === 1) PAIR.ws.send('{"type":"ping"}');
}, 50_000);

function connect() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const params = new URLSearchParams({ name: ME.name, device: ME.device, token: TAB_TOKEN });
  ws = new WebSocket(`${proto}://${location.host}/ws?${params}`);

  ws.onopen = () => { wsRetry = 0; setConn(true); };
  ws.onclose = () => {
    setConn(false);
    // Back off, but keep trying -- phones drop the socket every time the
    // screen sleeps and should rejoin silently on wake.
    wsRetry = Math.min(wsRetry + 1, 6);
    setTimeout(connect, 500 * 2 ** wsRetry);
  };
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.type === 'welcome') {
      ME.id = msg.id; ME.room = msg.room;
      setPeers(msg.peers); renderMe(); setConn(true);
    } else if (msg.type === 'peers') {
      setPeers(msg.peers);
    } else if (msg.type === 'signal') {
      connFor(msg.from).handleSignal(msg.data);
    }
  };
}
/** Relay to a peer through whichever room we know it from (network room first). */
function signal(to, data) {
  const via = !autoPeers.has(to) && pairPeers.has(to) && PAIR.ws?.readyState === 1 ? PAIR.ws : ws;
  return via?.readyState === 1 && via.send(JSON.stringify({ type: 'signal', to, data }));
}

/* Pairing with a code.
   Rooms come from the public address a device connects from, and some networks
   make two devices on the same Wi-Fi look like different networks: one on IPv4
   and the other on IPv6 (Android can't use stateful DHCPv6, so on many routers
   the PC gets IPv6 and the phone doesn't), a VPN, iCloud Private Relay, a mesh
   router. A shared 6-digit code puts both in a second, private room, alongside
   the network one. Transfers are still same-network only -- the code helps
   devices find each other, never routes a file across the internet. */
const PAIR = { code: null, hosting: false, started: 0, met: false, ws: null, retry: 0 };
const PAIR_TTL_MS = 15 * 60_000;           // an unused code is dropped after this

function newPairCode() {
  const n = crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000;
  return String(n).padStart(6, '0');
}
const fmtCode = (c) => (c ? `${c.slice(0, 3)} ${c.slice(3)}` : '––– –––');

function startPair(code, hosting) {
  stopPair(false);
  Object.assign(PAIR, { code, hosting, started: Date.now(), met: false, retry: 0 });
  try { sessionStorage.setItem('bd.pair', JSON.stringify({ code, hosting, started: PAIR.started })); } catch {}
  openPairSocket();
  renderPair();
}

function openPairSocket() {
  const code = PAIR.code;
  if (!code) return;
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const params = new URLSearchParams({ name: ME.name, device: ME.device, token: TAB_TOKEN, pair: code });
  const sock = new WebSocket(`${proto}://${location.host}/ws?${params}`);
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
      ME.id = ME.id || msg.id;                 // same token, same id as in the network room
      setPeersFrom(pairPeers, msg.peers);
    } else if (msg.type === 'peers') {
      setPeersFrom(pairPeers, msg.peers);
    } else if (msg.type === 'signal') {
      connFor(msg.from).handleSignal(msg.data);
    }
  };
}

function stopPair(render = true) {
  const sock = PAIR.ws;
  PAIR.ws = null;
  PAIR.code = null;
  if (sock) { sock.onclose = sock.onmessage = null; try { sock.close(); } catch {} }
  try { sessionStorage.removeItem('bd.pair'); } catch {}
  if (pairPeers.size) setPeersFrom(pairPeers, []);
  if (render) renderPair();
}

/** Reflect pairing state: the dialog, the "Paired" chip, the prompt. */
function renderPair() {
  const found = [...pairPeers.values()].filter((p) => !autoPeers.has(p.id));
  if (pairPeers.size) PAIR.met = true;
  $('pair-code').textContent = fmtCode(PAIR.code);
  $('pair-status').textContent = !PAIR.code ? ''
    : found.length ? `✓ Found ${found.map((p) => p.name).join(', ')}`
    : pairPeers.size ? '✓ Found — it was already on your screen'
    : PAIR.hosting ? 'Waiting for the other device…' : 'Joining…';
  $('pair-status').classList.toggle('ok', pairPeers.size > 0);
  $('pair-chip').hidden = !PAIR.code;
  $('pair-chip-code').textContent = fmtCode(PAIR.code);
  $('pair-link').hidden = !!PAIR.code;
  updatePairPrompt();
  // Found someone while the dialog is open: say so, then get out of the way.
  if (pairPeers.size && $('dlg-pair').open) setTimeout(() => closeDialog($('dlg-pair')), 1400);
}

function openPairDialog() {
  if (!PAIR.code) startPair(newPairCode(), true);
  $('pair-input').value = '';
  renderPair();
  $('dlg-pair').showModal();
}

// Nobody around for a while (and not already pairing): offer the code.
let lonelySince = performance.now();
let promptDismissed = (() => { try { return sessionStorage.getItem('bd.pairPromptX') === '1'; } catch { return false; } })();
function updatePairPrompt() {
  const lonely = !peers.size && !PAIR.code && !promptDismissed && ws?.readyState === 1 &&
    performance.now() - lonelySince > 12_000;
  $('pair-prompt').hidden = !lonely;
}
setInterval(() => {
  updatePairPrompt();
  if (PAIR.code && !PAIR.met && Date.now() - PAIR.started > PAIR_TTL_MS) stopPair();
}, 2_000);

/* Network switches. The room comes from the public IP the socket connected
   from, and a phone that moves from mobile data to Wi-Fi can keep its old
   socket -- and so its old room -- alive over the mobile link, showing nobody
   until a refresh. So re-check the room whenever the network may have changed,
   and poll gently while no one is visible (when a missed switch is likeliest).
   A different room means: reconnect. The tab token keeps the same peer id. */
let roomCheckBusy = false;
let rejoinedFor = null;
let aloneSince = performance.now();
let nextRoomCheck = 0;
async function checkRoom() {
  if (roomCheckBusy || !ME.room || document.hidden) return;
  roomCheckBusy = true;
  try {
    const r = await fetch('/api/whoami', { cache: 'no-store' });
    const { room } = await r.json();
    if (room === ME.room) rejoinedFor = null;
    // One rejoin per new network: if the fresh socket still lands elsewhere
    // (the two requests left by different links), retrying would only churn.
    else if (room && ME.room && room !== rejoinedFor) {
      console.info('[browserdrop] network changed; rejoining');
      rejoinedFor = room;
      reconnectWs();
    }
  } catch {} finally { roomCheckBusy = false; }
}
function reconnectWs() {
  const old = ws;
  if (old) { old.onclose = old.onmessage = null; try { old.close(); } catch {} }
  ME.room = null;
  setConn(false);
  wsRetry = 0;
  connect();
}
function networkMayHaveChanged() {
  aloneSince = performance.now();
  nextRoomCheck = 0;
  checkRoom();
  setTimeout(checkRoom, 2500);   // routes and DHCP settle a beat after the event
}
addEventListener('online', networkMayHaveChanged);
navigator.connection?.addEventListener?.('change', networkMayHaveChanged);
document.addEventListener('visibilitychange', () => { if (!document.hidden) networkMayHaveChanged(); });
setInterval(() => {
  if (document.hidden) return;
  if (peers.size) { aloneSince = performance.now(); return; }
  const now = performance.now();
  if (now < nextRoomCheck) return;
  nextRoomCheck = now + (now - aloneSince < 120_000 ? 10_000 : 30_000);
  checkRoom();
}, 5_000);

function setConn(ok) {
  $('conn-dot').className = `dot ${ok ? 'on' : 'off'}`;
  const text = !ok ? 'Reconnecting…' : ME.room ? 'Visible to this network' : 'Connecting…';
  $('me-sub').textContent = text;
  $('me-sub').parentElement.title = text;    // phones show only the dot
}
const autoPeers = new Map();   // seen in this network's room
const pairPeers = new Map();   // seen in the room for a pairing code
function setPeers(list) { setPeersFrom(autoPeers, list); }
function setPeersFrom(source, list) {
  source.clear();
  for (const p of list) source.set(p.id, p);
  // One radar: everyone from either room (a device can be in both).
  peers.clear();
  for (const p of autoPeers.values()) peers.set(p.id, p);
  for (const p of pairPeers.values()) if (!peers.has(p.id)) peers.set(p.id, p);
  if (peers.size) lonelySince = Infinity;
  else if (lonelySince === Infinity) lonelySince = performance.now();
  // A peer missing from the list is usually just mid-reconnect to the signalling
  // server. An established peer-to-peer connection does not need that server, so
  // keep it -- killing it here aborted transfers whenever signalling blipped.
  // A peer that really left is caught by the connection itself failing.
  for (const id of [...conns.keys()]) {
    if (peers.has(id)) continue;
    const c = conns.get(id);
    if (c.pc.connectionState === 'connected') continue;
    c.close(); conns.delete(id);
  }
  renderPeers();
  renderPair();
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
    this.polite = ME.id > peerId;   // perfect-negotiation roles, stable on both ends
    this.makingOffer = false;
    this.queue = [];
    this.sending = false;
    this.rx = null;
    this.verdicts = new Map();   // file id -> resolver awaiting accept/decline
    this.seq = 0;
    this.sendingT = null;        // the outgoing transfer currently in flight
    this.pendingVerdictId = null;
    this.pendingAccept = null;   // resolver for a prompt awaiting the user
    this.replyWaiter = null;     // sender: awaiting the receiver's need/got
    this.doneIds = new Set();    // receiver: transfers completed on this connection
    this.texts = [];             // text waiting for the channel to open
    this.textOut = new Map();    // text id -> {t, timer}, awaiting the peer's textgot
    this.textIn = new Map();     // text id -> parts received so far
    this.pc = new RTCPeerConnection({ iceServers: ICE_SERVERS });

    this.candTypes = new Set();
    this.localCands = 0;          // diagnostics for a failed connection, see fallback()
    this.remoteCands = 0;
    this.pc.onicecandidate = ({ candidate }) => {
      if (!candidate) return;
      this.localCands++;
      if (candidate.type) this.candTypes.add(candidate.type);   // host / srflx / relay
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
        if (collision && !this.polite) return;       // impolite peer ignores the glare
        if (collision) await pc.setLocalDescription({ type: 'rollback' });
        await pc.setRemoteDescription({ type: description.type, sdp: localOnlySdp(description.sdp) });
        if (description.type === 'offer') {
          await pc.setLocalDescription();
          signal(this.peerId, { description: pc.localDescription });
        }
      } else if (candidate) {
        if (candidate.candidate && !localCandidate(candidate.candidate)) return;   // see localCandidate
        if (candidate.candidate) this.remoteCands++;
        await pc.addIceCandidate(candidate).catch(() => {});
      }
    } catch (e) { console.warn('signal error', e); }
  }

  /* ---- control plane ---- */

  /** Send a control message to the peer. */
  sendCtrl(obj) {
    const ch = this.dc;
    if (ch?.readyState !== 'open') return false;
    try { ch.send(JSON.stringify(obj)); return true; } catch { return false; }
  }

  /** Send a control message and wait for the matching need/got reply. */
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

  /**
   * A queued file with no open data channel would otherwise sit forever: pump()
   * returns silently and only dc.onopen retries. If the peer connection cannot
   * be established (different networks with no traversable path, or an access
   * point with client isolation), say so and send via the relay instead.
   */
  armWatchdog() {
    if (this.watchdog || this.dc?.readyState === 'open') return;
    const deadline = Date.now() + CONNECT_DEADLINE_MS;
    const tick = () => {
      this.watchdog = null;
      if (this.dc?.readyState === 'open') return;
      const st = this.pc.connectionState;
      const ice = this.pc.iceConnectionState;
      // Show real progress rather than a frozen bar, so a slow-but-working
      // negotiation is distinguishable from a dead one.
      for (const { t } of [...this.queue, ...this.texts]) noteTransfer(t, `Connecting… (${ice})`);
      if (st === 'failed' || ice === 'failed') return void this.fallback('Direct connection failed');
      if (Date.now() > deadline) return void this.fallback('Direct connection timed out');
      this.watchdog = setTimeout(tick, 1000);
    };
    this.watchdog = setTimeout(tick, 1000);
  }

  /**
   * Cancel a transfer, whether it is still queued or already streaming, and tell
   * the far side so it stops writing and discards the partial file.
   */
  cancel(t) {
    const i = this.queue.findIndex((j) => j.t === t);
    if (i >= 0) { this.queue.splice(i, 1); failTransfer(t, 'Cancelled'); return; }
    if (this.sendingT === t) {
      t.cancelled = true;                       // the send loop notices next chunk
      this.sendCtrl({ t: 'cancel' });
      // If we are parked waiting for the peer to accept, that await must be
      // released too -- otherwise Cancel does nothing until the 120s timeout.
      this.verdicts.get(this.pendingVerdictId)?.(false);
    }
  }

  /**
   * Give up, visibly. Files only ever move sender-to-receiver, so when no peer
   * connection can be formed there is nothing to fall back to -- and silently
   * routing the file through a server would be exactly the thing this app does
   * not do.
   */
  fallback(reason) {
    this.clearWatchdog();
    console.warn(`[browserdrop] ${reason}.`,
      'ice=', this.pc.iceConnectionState,
      'conn=', this.pc.connectionState,
      'local candidate types=', [...this.candTypes].join(',') || '(none)');
    // Shown in the row: how many local addresses each side offered. "0" on
    // either side, or both non-zero yet no connection, point at different fixes.
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

    // Claim it now, not once bytes start moving: between the announcement and
    // the peer's answer this is still the in-flight transfer, and Cancel has to
    // reach it there.
    this.sendingT = t;
    t.path = this.path || (await this.detectPath());
    dc.send(JSON.stringify({ t: 'file', id, name: file.name, size: file.size, type: file.type }));

    // Nothing leaves this machine until the far side agrees to receive it.
    noteTransfer(t, `Waiting for ${who} to accept…`);
    this.pendingVerdictId = id;
    const accepted = await this.awaitVerdict(id);
    this.pendingVerdictId = null;
    if (t.cancelled) throw new Error('Cancelled');
    if (!accepted) throw new Error(`${who} declined`);

    // One channel, chunks framed with their offset, paced by backpressure. On a
    // local network a single data channel already saturates the link.
    const chunk = chunkSizeFor(this.pc) - FRAME_HEADER;
    const meter = newMeter();
    let sent = 0;
    while (sent < file.size) {
      if (t.cancelled) throw new Error('Cancelled');
      if (dc.readyState !== 'open') throw new Error('Connection lost');
      // Backpressure: SCTP will happily let us queue the entire file into RAM
      // and then stall. Wait for the buffer to drain instead.
      if (dc.bufferedAmount > HIGH_WATER) await drained(dc);
      const body = new Uint8Array(await file.slice(sent, sent + chunk).arrayBuffer());
      const frame = new Uint8Array(FRAME_HEADER + body.byteLength);
      new DataView(frame.buffer).setFloat64(0, sent);
      frame.set(body, FRAME_HEADER);
      dc.send(frame.buffer);
      sent += body.byteLength;
      updateTransfer(t, sent, meter(sent));
    }
    // Let the tail drain, then have the receiver confirm it has every byte, so
    // "Sent" means delivered rather than merely queued.
    while (dc.bufferedAmount > 0 && dc.readyState === 'open') await new Promise((r) => setTimeout(r, 40));
    const reply = await this.ask({ t: 'sent', id, size: file.size, chunk });
    if (t.cancelled) throw new Error('Cancelled');
    if (reply?.t !== 'got') throw new Error('The other device did not confirm receipt');
    this.sendingT = null;
    finishTransfer(t, 'Sent');
  }

  /**
   * Work out how the connection is actually routed. This is the single most
   * useful number when a transfer is unexpectedly slow: "local network" should
   * be fast, "relayed" never will be, and the difference is invisible otherwise.
   */
  async detectPath() {
    try {
      const stats = await this.pc.getStats();
      for (const r of stats.values()) {
        if (r.type !== 'candidate-pair' || r.state !== 'succeeded') continue;
        if (r.nominated === false) continue;
        const l = stats.get(r.localCandidateId), m = stats.get(r.remoteCandidateId);
        const lt = l?.candidateType, rt = m?.candidateType;
        // The far end turned out to be a public address (learnt from its checks,
        // not from anything it signalled): not this network, so hang up.
        const far = m?.address ?? m?.ip;
        if (far && !localAddress(far)) {
          console.warn('[browserdrop] closing a connection to a non-local address');
          this.pc.close();
          this.failAll('That device is not on this network');
          return null;
        }
        this.path = lt === 'host' && rt === 'host' ? 'direct, local network' : 'direct';
        // RTT decides the ceiling: an SCTP data channel can only have one flow
        // -control window in flight per round trip, so throughput is bounded by
        // window/RTT regardless of how much bandwidth either side has.
        this.rtt = r.currentRoundTripTime ? Math.round(r.currentRoundTripTime * 1000) : null;
        this.maxMsg = this.pc.sctp?.maxMessageSize ?? null;
        if (this.rtt) this.path += ` · ${this.rtt}ms`;
        for (const { t } of this.queue) t.path = this.path;
        console.info(`[browserdrop] path=${this.path} pair=${lt}/${rt} proto=${l?.protocol}`,
          `rtt=${this.rtt}ms outgoingBitrate=${r.availableOutgoingBitrate ?? '?'}`,
          `sctpMaxMessage=${this.maxMsg}`);
        return this.path;
      }
    } catch { /* getStats unsupported or connection gone */ }
    return null;
  }

  /* ---- text: the clipboard ----
     sender   -> {t:"text", id, part, parts, text}   one message per part
     receiver -> {t:"textgot", id}                   once every part is in
     No Accept step: text lands in the list and nothing happens until someone
     taps Copy. An older Ferry ignores "text", so no answer means "update it". */

  enqueueText(text) {
    const who = peers.get(this.peerId)?.name || 'peer';
    const t = newTextRow({ dir: 'out', text, peer: who, peerId: this.peerId });
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
    // Text needs no Accept, so what a peer can make us hold is kept small: parts
    // no longer than a sender makes them, a few texts in flight at once, and
    // unfinished ones dropped after a while.
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
    const t = newTextRow({ dir: 'in', text: a.parts.join(''), peer: whoIs(this.peerId), peerId: this.peerId });
    finishText(t, 'Received');
  }

  onTextGot({ id }) {
    const o = this.textOut.get(id);
    if (!o) return;
    o.done();
    finishText(o.t, 'Sent');
  }

  /** Resolves true on accept, false on decline or if the peer never answers. */
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

  /**
   * One framed chunk. Each carries its byte offset and is placed by it: written
   * at that position on disk, or sorted into place before the Blob is built.
   */
  onData(buf) {
    const rx = this.rx;
    if (!rx || buf.byteLength <= FRAME_HEADER) return;
    const off = new DataView(buf, 0, FRAME_HEADER).getFloat64(0);
    const body = new Uint8Array(buf, FRAME_HEADER);
    // Every byte must land inside the file the user accepted.
    if (!Number.isSafeInteger(off) || off < 0 || off + body.byteLength > rx.size) {
      this.sendCtrl({ t: 'cancel' });
      return void this.discardRx('The other device sent more than it offered');
    }
    if (rx.seen.has(off)) return;              // a resend of something that did arrive
    rx.seen.add(off);
    rx.received += body.byteLength;
    if (rx.writer) {
      rx.wq = rx.wq.then(() => rx.writer.write({ type: 'write', position: off, data: body })).catch((e) => {
        rx.failed = e;                           // endReceive checks this once the writes settle
        if (this.rx !== rx) return;
        this.sendCtrl({ t: 'cancel' });
        this.discardRx(e?.name === 'QuotaExceededError' ? 'Not enough free space on this device' : `Could not save: ${e.message}`);
      });
    }
    else rx.parts.push([off, body]);
    updateTransfer(rx.t, rx.received, rx.meter(rx.received));
    if (rx.received >= rx.size && !rx.ending) { rx.ending = true; this.endReceive(); }
  }

  /** Sender finished a pass: tell it what is still missing, or that all arrived. */
  onSent({ id, size, chunk }) {
    const rx = this.rx;
    if (rx && rx.id === id) {
      // Only the accepted size, in chunks the size senders really use.
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

  /** The far side cancelled: stop whichever direction is in flight. */
  remoteCancel() {
    if (this.sendingT) this.sendingT.cancelled = true;
    this.verdicts.get(this.pendingVerdictId)?.(false);
    // Dismiss an Accept/Decline prompt the user never got round to answering.
    if (this.pendingAccept) { this.pendingAccept(false); this.pendingAccept = null; }
    if (this.rx) this.discardRx('Cancelled by the other device');
  }

  /** Abandon an in-progress receive and release anything it was holding. */
  discardRx(reason) {
    const rx = this.rx;
    if (!rx) return;
    this.rx = null;
    rx.parts.length = 0;                       // release buffered chunks
    rx.writer?.abort?.().catch(() => {});      // and the partial file on disk
    removeEntry(rx.entry);
    failTransfer(rx.t, reason);
  }

  async beginReceive({ id, name, size, type }) {
    // One offer at a time per device, with a sane id and size; anything else is
    // refused before it reaches the screen.
    if (typeof id !== 'string' || id.length > 64 || !Number.isSafeInteger(size) || size < 0 ||
        this.pendingAccept || this.rx) {
      if (typeof id === 'string' && id.length <= 64) this.sendCtrl({ t: 'reject', id });
      return;
    }
    name = cleanName(name);
    const who = whoIs(this.peerId);
    const t = newTransfer({ dir: 'in', name, size, peer: who, peerId: this.peerId });

    // Room membership is grouping, not permission -- a shared public IP can put
    // a stranger in your room. So every incoming file needs a deliberate yes.
    const ask = askAccept(t, who, size);
    this.pendingAccept = ask.cancel;
    const allowed = await ask.promise;
    const wasCancelled = this.pendingAccept === null;   // cleared by remoteCancel
    this.pendingAccept = null;
    if (!allowed) {
      if (!wasCancelled) this.sendCtrl({ t: 'reject', id });
      failTransfer(t, wasCancelled ? 'Cancelled by the other device' : 'Declined');
      return;
    }

    t.path = this.path || (await this.detectPath());
    const rx = { id, name, size, type, received: 0, seen: new Set(), parts: [], writer: null,
                 wq: Promise.resolve(), t, meter: newMeter() };
    // Large files would blow out memory on the receiving tab, so stream them to
    // disk where the browser allows it. The Accept click is the user gesture
    // that lets us open the save picker at all.
    if (size > STREAM_TO_DISK_ABOVE && window.showSaveFilePicker) {
      try {
        const handle = await window.showSaveFilePicker({ suggestedName: name });
        rx.writer = await handle.createWritable();
      } catch { /* user cancelled the picker; fall back to memory */ }
    }
    if (!rx.writer && size > SPOOL_ABOVE && canSpool()) {
      try {
        const root = await navigator.storage.getDirectory();
        rx.entry = `rx-${crypto.randomUUID()}`;
        rx.handle = await root.getFileHandle(rx.entry, { create: true });
        rx.writer = await rx.handle.createWritable();
      } catch { rx.writer = null; removeEntry(rx.entry); rx.entry = null; }   // memory, as before
    }
    // Cancelling a receive tells the sender to stop, so neither side keeps
    // pushing bytes nobody wants.
    t.cancel = () => {
      this.sendCtrl({ t: 'cancel' });
      this.discardRx('Cancelled');
    };
    this.rx = rx;
    this.sendCtrl({ t: 'accept', id });   // only now may bytes flow
    // An empty file has no chunks to trigger completion; finish it now.
    if (size === 0) { rx.ending = true; this.endReceive(); }
  }

  async endReceive() {
    const rx = this.rx;
    this.rx = null;
    this.doneIds.add(rx.id);
    this.sendCtrl({ t: 'got', id: rx.id });
    if (rx.writer) {
      await rx.wq;                               // positioned writes still queued
      if (rx.failed) {
        rx.writer.abort?.().catch(() => {});
        removeEntry(rx.entry);
        const e = rx.failed;
        return void failTransfer(rx.t, e?.name === 'QuotaExceededError' ? 'Not enough free space on this device' : `Could not save: ${e.message}`);
      }
      try {
        await rx.writer.close();
      } catch (e) {
        removeEntry(rx.entry);
        return void failTransfer(rx.t, `Could not save: ${e.message}`);
      }
      // Spooled to private storage: offer it like any received file.
      if (rx.handle) offerDownload(rx.t, await rx.handle.getFile(), rx.name);
      else finishTransfer(rx.t, 'Saved');
    } else {
      rx.parts.sort((a, b) => a[0] - b[0]);
      // No type from the sender: the name you accepted, extension included, is
      // what decides how the file is saved and opened.
      const blob = new Blob(rx.parts.map((p) => p[1]));
      rx.parts.length = 0;
      offerDownload(rx.t, blob, rx.name);
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

/** Drop a connection that died while idle, so the next attempt starts fresh. */
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

/** Wait until a channel's send buffer drains below its low-water mark. */
function drained(ch) {
  if (ch.bufferedAmount <= ch.bufferedAmountLowThreshold || ch.readyState !== 'open') return Promise.resolve();
  return new Promise((res) => {
    const done = () => { ch.removeEventListener('bufferedamountlow', done); ch.removeEventListener('close', done); res(); };
    ch.addEventListener('bufferedamountlow', done);
    ch.addEventListener('close', done);
  });
}

if (QS.has('debug')) window.__bd = { conns, get ws() { return ws; }, speedTip: (size, secs) => maybeSpeedTip(size, secs) };   // used by the e2e tests

/** Exponentially-smoothed throughput, so the readout doesn't flicker. */
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

/* -------------------------------------------------------------------- UI */

function renderMe() {
  $('me-icon').innerHTML = art(ME.device);
  $('me-name').textContent = ME.name;
  let named = false;
  try { named = !!localStorage.getItem('bd.named'); } catch {}
  $('me-cap').innerHTML = named ? 'This device' : 'This device<span class="cap-hint"> · tap name to rename</span>';
}

/* A browser cannot read the device's own name ("Sam's iPhone"): no API
   exposes it, deliberately, because it would identify the person to every
   site. So the random name is a placeholder and you can set your own. */
function renameMe() {
  const el = $('me-name');
  if (el.querySelector('input')) return;
  const input = document.createElement('input');
  input.className = 'name-edit';
  input.value = ME.name;
  input.maxLength = 32;
  input.enterKeyHint = 'done';
  input.autocomplete = 'off';
  input.setAttribute('aria-label', 'Device name');
  el.replaceChildren(input);
  input.focus();
  input.select();
  let done = false;
  const commit = (save) => {
    if (done) return;
    done = true;
    const v = input.value.replace(/\s+/g, ' ').trim().slice(0, 32);
    if (save && v && v !== ME.name) {
      ME.name = v;
      try { localStorage.setItem('bd.name', v); localStorage.setItem('bd.named', '1'); } catch {}
      if (ws?.readyState === 1) ws.send(JSON.stringify({ type: 'rename', name: v }));
    } else if (save) {
      try { localStorage.setItem('bd.named', '1'); } catch {}
    }
    renderMe();
  };
  input.onkeydown = (e) => {
    if (e.key === 'Enter') { e.preventDefault(); commit(true); }
    else if (e.key === 'Escape') commit(false);
  };
  input.onblur = () => commit(true);
}
$('me-name').onclick = renameMe;
$('me-name').onkeydown = (e) => { if ((e.key === 'Enter' || e.key === ' ') && e.target === $('me-name')) { e.preventDefault(); renameMe(); } };

function renderPeers() {
  const box = $('peers');
  const list = [...peers.values()];
  // Keyed by device: the ones already here stay (and glide to their new place
  // on the orbit), newcomers pop in, and the ones that left dissolve.
  const was = new Map([...box.querySelectorAll('.peer:not(.leaving)')].map((el) => [el.dataset.peer, el]));
  list.forEach((p, i) => {
    let el = was.get(p.id);
    was.delete(p.id);
    if (!el) {
      el = document.createElement('div');
      el.className = 'peer';
      el.dataset.peer = p.id;
      el.tabIndex = 0;
      el.setAttribute('role', 'button');
      el.innerHTML = `<span class="peer-av"><span class="peer-ring"></span>
        <span class="peer-icon"></span></span>
        <span class="peer-name"></span>
        <span class="peer-sub"></span>`;
      el.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); el.click(); } };
      // Files shared in from Android go to the tapped device; otherwise pick some.
      const id = p.id;
      el.onclick = () => (shared.length ? sendShared(id) : pickFilesFor((files) => connForSending(id).enqueue(files)));
      box.appendChild(el);
      if (peersShown) requestAnimationFrame(ping);
    }
    el.setAttribute('aria-label', `Send to ${p.name}`);
    // Devices sit evenly on an orbit around you; CSS turns --i/--n into an angle.
    el.style.setProperty('--i', i);
    el.style.setProperty('--n', list.length);
    // In the upper half the name goes above the icon, so it never lands on your orb.
    el.classList.toggle('above', Math.sin((i * 360 / list.length - 60) * Math.PI / 180) < -0.25);
    if (el.dataset.device !== p.device) { el.dataset.device = p.device; el.querySelector('.peer-icon').innerHTML = art(p.device); }
    el.querySelector('.peer-name').textContent = p.name;
    el.querySelector('.peer-sub').textContent = `${LABEL[p.device] || 'Device'} · ${tagOf(p.id)}${!autoPeers.has(p.id) && pairPeers.has(p.id) ? ' · paired' : ''}`;
    const live = ringFor.get(p.id);
    if (live) setRing(p.id, live);
  });
  for (const el of was.values()) {
    el.classList.add('leaving');
    setTimeout(() => el.remove(), calm.matches ? 0 : 520);
  }
  peersShown = true;
  $('peers-empty').hidden = list.length > 0;
  $('clip-line').hidden = list.length === 0;
  if ($('dlg-text').open) renderTextTo();
  $('stage').classList.toggle('populated', list.length > 0);
  // Past a handful, an orbit gets cramped; fall back to a tidy grid.
  $('stage').classList.toggle('crowded', list.length > 7);
}

let pendingPick = null;
function pickFilesFor(fn) { pendingPick = fn; $('file-input').click(); }
$('file-input').onchange = (e) => {
  const files = [...e.target.files];
  e.target.value = '';
  const fn = pendingPick;
  pendingPick = null;
  if (!files.length || !fn) return;
  try {
    fn(files);
  } catch (err) {
    // A throw here used to mean the file picker closed and nothing happened,
    // with no row and no message. Always leave a visible trace.
    console.error('[browserdrop] could not start transfer', err);
    for (const f of files) {
      failTransfer(newTransfer({ dir: 'out', name: f.name, size: f.size, peer: '—' }),
                   `Could not start: ${err.message}`);
    }
  }
};

/* ---- motion ----
   The moments CSS can't place on its own: a ring off the orb when a device
   appears, sparks arcing between you and a device while bytes move, a burst
   where a transfer lands. All of it stands down under reduced motion. */

const calm = matchMedia('(prefers-reduced-motion: reduce)');
let peersShown = false;          // the first batch of devices arrives without fanfare
const centreOf = (el) => { const r = el.getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; };
const orbEl = () => document.querySelector('.me-orb');
const peerAv = (id) => id && document.querySelector(`.peer[data-peer="${CSS.escape(id)}"]:not(.leaving) .peer-av`);
function fx(cls, x, y) {
  const el = document.createElement('i');
  el.className = cls;
  el.style.left = `${x}px`; el.style.top = `${y}px`;
  document.body.append(el);
  return el;
}
function ping() {
  if (calm.matches || !orbEl()) return;
  const el = fx('ping', ...centreOf(orbEl()));
  el.onanimationend = () => el.remove();
}
function bump(el) {
  if (!el || calm.matches) return;
  el.classList.remove('catch'); void el.offsetWidth; el.classList.add('catch');
  el.addEventListener('animationend', () => el.classList.remove('catch'), { once: true });
}
/** A short train of sparks along an arc: out to the device, or in to you. */
function sparks(peerId, dir) {
  const av = peerAv(peerId), orb = orbEl();
  if (calm.matches || !av || !orb || document.hidden) return;
  let [x0, y0] = centreOf(orb), [x1, y1] = centreOf(av);
  if (dir === 'in') [x0, y0, x1, y1] = [x1, y1, x0, y0];
  const bend = 0.22, cx = (x0 + x1) / 2 - (y1 - y0) * bend, cy = (y0 + y1) / 2 + (x1 - x0) * bend;
  const frames = [];
  for (let k = 0; k <= 16; k++) {
    const u = k / 16, v = 1 - u;
    const x = v * v * x0 + 2 * v * u * cx + u * u * x1 - x0, y = v * v * y0 + 2 * v * u * cy + u * u * y1 - y0;
    frames.push({ transform: `translate(${x}px, ${y}px) scale(${0.5 + Math.sin(u * Math.PI) * 0.7})`, opacity: Math.min(1, Math.sin(u * Math.PI) * 2.2) });
  }
  // A head and a fading tail of smaller sparks a few frames behind it.
  for (let k = 0; k < 6; k++) {
    const el = fx('spark', x0, y0);
    el.style.opacity = 0;
    if (k) el.style.scale = 1 - k * 0.14;
    el.animate(frames, { duration: 1000, delay: k * 45, easing: 'cubic-bezier(.45, 0, .25, 1)', fill: 'both' })
      .finished.then(() => el.remove(), () => el.remove());
  }
  setTimeout(() => bump(dir === 'in' ? document.querySelector('.me') : av?.closest('.peer')), 900);
}
/** Where a transfer lands: a ring of green sparks bursts out. */
function burst(peerId, dir) {
  const at = dir === 'in' ? orbEl() : peerAv(peerId);
  if (calm.matches || !at || document.hidden) return;
  const [x, y] = centreOf(at);
  bump(dir === 'in' ? document.querySelector('.me') : at.closest('.peer'));
  for (let k = 0; k < 16; k++) {
    const a = (k / 16) * Math.PI * 2 + Math.random() * 0.3, d = 58 + Math.random() * 40;
    const el = fx('spark ok', x, y);
    el.animate([
      { transform: 'translate(0, 0) scale(1.2)', opacity: 1 },
      { transform: `translate(${Math.cos(a) * d * 0.8}px, ${Math.sin(a) * d * 0.8}px) scale(.7)`, opacity: 1, offset: 0.45 },
      { transform: `translate(${Math.cos(a) * d}px, ${Math.sin(a) * d}px) scale(.15)`, opacity: 0 },
    ], { duration: 1000 + Math.random() * 300, easing: 'cubic-bezier(.2, .7, .3, 1)', fill: 'both' })
      .finished.then(() => el.remove(), () => el.remove());
  }
}
const TICK = '<svg class="tick" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path pathLength="1" d="M5 12.5l4.5 4.5L19 7.5"/></svg>';
/** Close a dialog after it has sunk away (dialog.closing in style.css). */
function closeDialog(dlg) {
  if (!dlg.open || dlg.classList.contains('closing')) return;
  if (calm.matches) { dlg.close(); return; }
  dlg.classList.add('closing');
  setTimeout(() => { dlg.classList.remove('closing'); dlg.close(); }, 230);
}
/** A finished row: its icon becomes a tick that draws itself, and the device bursts. */
function landed(t) {
  t.el.querySelector('.row-ic').innerHTML = TICK;
  burst(t.peerId, t.dir);
}

// The pointer's place, -1..1 each way, for the orb's lean and the aurora's drift.
if (matchMedia('(pointer: fine)').matches) {
  let raf = 0;
  addEventListener('pointermove', (e) => {
    if (raf || calm.matches) return;
    raf = requestAnimationFrame(() => {
      raf = 0;
      const r = document.documentElement.style;
      r.setProperty('--mx', ((e.clientX / innerWidth) * 2 - 1).toFixed(3));
      r.setProperty('--my', ((e.clientY / innerHeight) * 2 - 1).toFixed(3));
    });
  }, { passive: true });
}
setTimeout(() => document.documentElement.classList.remove('entering'), 1800);

/* ---- transfer rows ---- */

let transferSeq = 0;
function newTransfer({ dir, name, size, peer, peerId }) {
  $('transfers-wrap').hidden = false;
  const id = `t${++transferSeq}`;
  const el = document.createElement('div');
  el.className = `row ${dir}`;
  el.id = id;
  $('idle').hidden = true;
  // Layout: icon | name over status | one action slot (cancel, then save or
  // retry). Everything below lines up with the name, never with the icon.
  el.innerHTML = `<div class="row-top">
      <span class="row-ic">${dir === 'out' ? ARROW_UP : ARROW_DOWN}</span>
      <div class="row-main">
        <div class="row-name" title="${esc(name)}">${esc(name)}</div>
        <div class="row-sub">${fmtBytes(size)} · ${dir === 'out' ? 'to' : 'from'} ${esc(peer || 'peer')}</div>
      </div>
      <div class="row-act"><button type="button" class="row-x" aria-label="Cancel" title="Cancel">${X_ICON}</button></div>
    </div>
    <div class="row-body">
      <div class="row-ask" hidden></div>
      <div class="track"><div class="fill"></div></div>
      <div class="row-stats" hidden></div>
      <div class="readout" hidden></div>
    </div>`;
  $('transfers').prepend(el);
  const t = { id, size, el, peerId, dir, peer: peer || 'peer', started: performance.now(), cancelled: false };
  // `cancel` is attached by whoever owns the transfer; until then the button
  // just stops the row rather than pretending to halt something.
  el.querySelector('.row-x').onclick = () => (t.cancel ? t.cancel() : failTransfer(t, 'Cancelled'));
  return t;
}
/** A row for text: the text itself below the name, and Copy in the action slot. */
function newTextRow({ dir, text, peer, peerId }) {
  const first = text.trim().split('\n')[0].slice(0, 120) || 'Text';
  const t = newTransfer({ dir, name: first, size: text.length, peer, peerId });
  t.text = text;
  t.el.classList.add('text');
  t.el.querySelector('.row-ic').innerHTML = CLIP_ICON;
  noteTransfer(t, `${fmtChars(text.length)} · ${dir === 'out' ? 'to' : 'from'} ${peer || 'peer'}`);
  const box = document.createElement('div');
  box.className = 'row-text';
  box.textContent = text.length > 20_000 ? `${text.slice(0, 20_000)}…` : text;
  box.title = 'Show all';
  box.onclick = () => { box.classList.add('open'); box.removeAttribute('title'); };
  t.el.querySelector('.row-body').prepend(box);
  return t;
}
const fmtChars = (n) => (n === 1 ? '1 character' : `${n.toLocaleString()} characters`);

function finishText(t, verb) {
  endRow(t);
  // Text is there in an instant: one flight of sparks, then the landing.
  sparks(t.peerId, t.dir);
  setTimeout(() => burst(t.peerId, t.dir), calm.matches ? 0 : 950);
  t.el.querySelector('.row-ic').innerHTML = TICK;
  t.el.classList.add('done');
  const sub = t.el.querySelector('.row-sub');
  sub.className = 'row-sub ok';
  sub.textContent = `${verb} · ${fmtChars(t.text.length)} · ${t.dir === 'out' ? 'to' : 'from'} ${t.peer}`;
  const copy = document.createElement('button');
  copy.type = 'button'; copy.className = 'mini'; copy.textContent = 'Copy';
  copy.onclick = async () => {
    const ok = await copyText(t.text);
    copy.textContent = ok ? 'Copied' : 'Couldn\'t copy';
    setTimeout(() => { copy.textContent = 'Copy'; }, 1600);
  };
  rowAction(t, copy);
  if (t.dir === 'in') requestAnimationFrame(() => t.el.scrollIntoView({ block: 'nearest', behavior: 'smooth' }));
}

async function copyText(text) {
  try { await navigator.clipboard.writeText(text); return true; } catch {}
  // Older Safari, or a page without clipboard access: the classic way.
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.setAttribute('readonly', '');
  ta.style.cssText = 'position:fixed;top:0;left:0;opacity:0';
  document.body.append(ta);
  ta.select();
  let ok = false;
  try { ok = document.execCommand('copy'); } catch {}
  ta.remove();
  return ok;
}

/* ---- the clipboard: compose and send text ---- */

let textTo = null;             // the device picked in the text sheet
function openTextDialog(prefill = '') {
  const input = $('text-input');
  if (prefill) input.value = prefill;
  renderTextTo();
  updateTextCount();
  if (!$('dlg-text').open) $('dlg-text').showModal();
  input.focus();
  if (prefill) input.setSelectionRange(input.value.length, input.value.length);
}
function renderTextTo() {
  const list = [...peers.values()];
  if (!list.some((p) => p.id === textTo)) textTo = list.length === 1 ? list[0].id : null;
  const box = $('text-to');
  box.replaceChildren();
  if (!list.length) {
    const none = document.createElement('span');
    none.className = 'none';
    none.textContent = 'No devices nearby yet. Open Ferry on the other device.';
    box.append(none);
  }
  for (const p of list) {
    const b = document.createElement('button');
    b.type = 'button';
    b.setAttribute('role', 'radio');
    b.setAttribute('aria-checked', String(p.id === textTo));
    b.className = p.id === textTo ? 'mini' : 'ghost mini';
    b.textContent = p.name;
    b.onclick = () => { textTo = p.id; renderTextTo(); updateTextCount(); };
    box.append(b);
  }
  updateTextCount();
}
function updateTextCount() {
  const n = $('text-input').value.length;
  const over = n > TEXT_MAX;
  $('text-count').textContent = over ? `Too long (${fmtChars(n)}) — send it as a file` : n ? fmtChars(n) : '';
  $('text-count').className = over ? 'err' : '';
  $('text-send').disabled = !n || over || !textTo;
}
$('btn-text').onclick = () => openTextDialog();
$('text-input').oninput = updateTextCount;
$('text-paste').onclick = async () => {
  try {
    const got = await navigator.clipboard.readText();
    if (got) {
      const el = $('text-input');
      el.setRangeText(got, el.selectionStart, el.selectionEnd, 'end');
      updateTextCount();
    }
  } catch {
    // No clipboard access (refused, or the browser doesn't offer it): the
    // keyboard's own paste works everywhere.
    $('text-count').textContent = 'Long-press the box (or Ctrl+V) and choose Paste';
  }
  $('text-input').focus();
};
$('text-form').onsubmit = (e) => {
  e.preventDefault();
  const text = $('text-input').value;
  if (!text || text.length > TEXT_MAX || !textTo || !peers.has(textTo)) return;
  connForSending(textTo).enqueueText(text);
  $('text-input').value = '';
  closeDialog($('dlg-text'));
};
// Paste anywhere on the page (outside a text field) to send what you copied.
addEventListener('paste', (e) => {
  if (e.target.closest?.('input, textarea, [contenteditable]') || document.querySelector('dialog[open]')) return;
  const text = e.clipboardData?.getData('text/plain');
  if (!text || !peers.size) return;
  e.preventDefault();
  openTextDialog(text);
});

/** Ask before receiving: two equal buttons under the name, scrolled into view. */
function askAccept(t, who, size) {
  let done;
  const promise = new Promise((resolve) => {
    noteTransfer(t, `${who || 'A device'} wants to send ${fmtBytes(size)}`);
    t.el.classList.add('asking');            // the buttons below are the choice; hide the ×
    const box = t.el.querySelector('.row-ask');
    const no = document.createElement('button');
    no.type = 'button'; no.className = 'ghost'; no.textContent = 'Decline';
    const yes = document.createElement('button');
    yes.type = 'button'; yes.textContent = 'Accept';
    box.replaceChildren(no, yes);
    box.hidden = false;
    done = (v) => { box.hidden = true; box.replaceChildren(); t.el.classList.remove('asking'); resolve(v); };
    yes.onclick = () => done(true);
    no.onclick = () => done(false);
    // On a phone the list can sit below the fold; a prompt nobody sees is a hang.
    requestAnimationFrame(() => t.el.scrollIntoView({ block: 'nearest', behavior: 'smooth' }));
  });
  // `cancel` lets the sender's withdrawal tear the prompt down remotely.
  return { promise, cancel: () => done(false) };
}

function noteTransfer(t, text) { t.el.querySelector('.row-sub').textContent = text; }

/* Progress ring on the peer's avatar, like AirDrop. One ring per peer: if two
   transfers with the same device overlap, the latest update wins. */
const ringFor = new Map();     // peerId -> fraction
function setRing(peerId, p) {
  if (!peerId) return;
  if (p == null) ringFor.delete(peerId); else ringFor.set(peerId, p);
  $('stage').classList.toggle('moving', ringFor.size > 0);
  updateTab();
  const el = document.querySelector(`.peer[data-peer="${CSS.escape(peerId)}"]`);
  if (!el) return;
  el.classList.toggle('busy', p != null);
  if (p != null) el.querySelector('.peer-ring')?.style.setProperty('--p', Math.min(1, p).toFixed(4));
}

/* Live tab icon: while anything is moving, the favicon becomes a progress ring
   and the title leads with the percentage, so a background tab still shows how
   far along it is. Finishing flashes the ring green, then the icon returns. */
const TAB = { link: $('fav'), href: $('fav').href, title: document.title, img: new Image(), pct: -1, flash: null };
TAB.img.src = '/icons/fav-64.png';
function drawTab(frac, color) {
  const c = document.createElement('canvas');
  c.width = c.height = 64;
  const g = c.getContext('2d');
  if (TAB.img.complete && TAB.img.naturalWidth) g.drawImage(TAB.img, 13, 13, 38, 38);
  g.lineWidth = 7;
  g.lineCap = 'round';
  g.strokeStyle = 'rgba(128,128,128,.35)';
  g.beginPath(); g.arc(32, 32, 28, 0, Math.PI * 2); g.stroke();
  g.strokeStyle = color;
  g.beginPath(); g.arc(32, 32, 28, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * Math.max(.02, frac)); g.stroke();
  TAB.link.href = c.toDataURL('image/png');
}
/* Keep the screen on while bytes are moving. Phones suspend a page soon after
   the screen locks (iOS within seconds), which stalls the transfer; a screen
   wake lock stops the auto-lock. It can't help once you switch apps -- only a
   native app can keep working in the background. */
let wake = null;
let wakeAsking = false;          // progress ticks arrive faster than the request resolves
async function holdWake(on) {
  try {
    if (on && !wake && !wakeAsking && !document.hidden && navigator.wakeLock) {
      wakeAsking = true;
      const w = await navigator.wakeLock.request('screen');
      wakeAsking = false;
      if (!ringFor.size) { w.release(); return; }   // finished while we were asking
      wake = w;
      w.addEventListener('release', () => { if (wake === w) wake = null; });
    } else if (!on && wake) {
      const w = wake; wake = null; await w.release();
    }
  } catch { wake = null; wakeAsking = false; }   // denied (battery saver, iframe): carry on
}
// The system drops the lock when the page is hidden; take it back on return.
document.addEventListener('visibilitychange', () => { if (!document.hidden && ringFor.size) holdWake(true); });

function updateTab() {
  const live = [...ringFor.values()];
  holdWake(live.length > 0);
  if (!live.length) {
    if (TAB.pct >= 0 && !TAB.flash) { TAB.link.href = TAB.href; document.title = TAB.title; }
    TAB.pct = -1;
    return;
  }
  const frac = live.reduce((a, b) => a + b, 0) / live.length;
  const pct = Math.floor(frac * 100);
  if (pct === TAB.pct) return;                // at most ~100 redraws per transfer
  TAB.pct = pct;
  clearTimeout(TAB.flash); TAB.flash = null;
  drawTab(frac, '#0a84ff');
  document.title = `${pct}% · Ferry`;
}
function flashTabDone() {
  clearTimeout(TAB.flash);
  drawTab(1, '#30d158');
  document.title = `Done · Ferry`;
  TAB.flash = setTimeout(() => {
    TAB.flash = null;
    if (!ringFor.size) { TAB.link.href = TAB.href; document.title = TAB.title; }
    else { TAB.pct = -1; updateTab(); }
  }, 2500);
}

function updateTransfer(t, done, rate) {
  t.el.classList.add('active');
  const now = performance.now();
  if (!(now - t.sparked < 1400)) { t.sparked = now; sparks(t.peerId, t.dir); }
  setRing(t.peerId, done / t.size);
  t.el.querySelector('.fill').style.width = `${(done / t.size) * 100}%`;
  const eta = rate > 0 ? fmtEta((t.size - done) / rate) : '—';
  // Wide screens get the four-cell readout; phones get the same numbers on one line.
  const ro = t.el.querySelector('.readout');
  ro.hidden = false;
  ro.innerHTML =
    cell('Rate', rate ? fmtRate(rate) : '—', true) +
    cell('Moved', fmtBytes(done)) +
    cell('Total', fmtBytes(t.size)) +
    cell('Remaining', eta);
  const st = t.el.querySelector('.row-stats');
  st.hidden = false;
  st.innerHTML = `<span>${esc(fmtBytes(done))} of ${esc(fmtBytes(t.size))}</span>` +
    `<span class="hot">${esc(rate ? fmtRate(rate) : '—')}</span><span>${esc(eta)} left</span>`;
  const sub = t.el.querySelector('.row-sub');
  sub.textContent = `${Math.round((done / t.size) * 100)}% · ${t.dir === 'out' ? 'to' : 'from'} ${t.peer}`;
  if (t.path) sub.title = t.path;
}
function endRow(t) {
  if (ringFor.get(t.peerId) != null) setRing(t.peerId, null);
  t.el.querySelector('.row-x')?.remove();
  t.el.classList.remove('active', 'asking');
  t.el.querySelector('.readout').hidden = true;     // live numbers are only for in-flight
  t.el.querySelector('.row-stats').hidden = true;
}
/** Put a pill (Save / Try again) in the row's action slot. */
function rowAction(t, node) { t.el.querySelector('.row-act').replaceChildren(node); }

/** One readout cell; the unit is split off so it can be set smaller. */
function cell(label, value, hot) {
  const [v, u] = String(value).split(' ');
  return `<div class="cell"><div class="celll">${esc(label)}</div>` +
         `<div class="cellv${hot ? ' hot' : ''}">${esc(v)}` +
         `${u ? `<span class="cellu">${esc(u)}</span>` : ''}</div></div>`;
}
const fmtEta = (s) =>
  !isFinite(s) || s < 0 ? '—'
  : s >= 3600 ? `${Math.floor(s / 3600)}:${String(Math.floor(s / 60) % 60).padStart(2, '0')}:00`
  : `${Math.floor(s / 60)}:${String(Math.round(s % 60)).padStart(2, '0')}`;

/* Slow transfers are nearly always 2.4 GHz Wi-Fi (about 5 MB/s at best in
   practice); 5 GHz is typically 3-5x faster. A browser can't see the Wi-Fi
   band, so judge by the speed of a transfer big enough to measure. */
const SPEED_TIP_KEY = 'bd.speedTip';
function maybeSpeedTip(size, secs) {
  const rate = size / secs;
  if (size < 20e6 || rate >= 8e6) return;
  try { if (Date.now() - Number(localStorage.getItem(SPEED_TIP_KEY) || 0) < 7 * 864e5) return; } catch {}
  $('speed-tip-rate').textContent = fmtRate(rate);
  $('speed-tip').hidden = false;
}

function finishTransfer(t, verb) {
  endRow(t);
  landed(t);
  const secs = (performance.now() - t.started) / 1000;
  maybeSpeedTip(t.size, secs);
  flashTabDone();
  t.el.classList.add('done');
  t.el.querySelector('.fill').style.width = '100%';
  const sub = t.el.querySelector('.row-sub');
  sub.className = 'row-sub ok';
  sub.textContent = `${verb} · ${fmtBytes(t.size)} in ${fmtSecs(secs)}`;
  sub.title = fmtRate(t.size / secs);
}
function failTransfer(t, reason, retry) {
  if (t.el.classList.contains('done')) return;
  endRow(t);
  t.el.classList.add('failed');
  const sub = t.el.querySelector('.row-sub');
  sub.className = 'row-sub err';
  sub.textContent = reason;
  sub.title = reason;
  if (!retry) return;
  const again = document.createElement('button');
  again.type = 'button'; again.className = 'mini'; again.textContent = 'Try again';
  again.onclick = () => { t.el.remove(); retry(); };
  rowAction(t, again);
}
function offerDownload(t, blob, name) {
  endRow(t);
  landed(t);
  const secs = (performance.now() - t.started) / 1000;
  const url = URL.createObjectURL(blob);
  maybeSpeedTip(t.size, secs);
  flashTabDone();
  t.el.classList.add('done');
  t.el.querySelector('.fill').style.width = '100%';
  const sub = t.el.querySelector('.row-sub');
  sub.className = 'row-sub ok';
  sub.textContent = `Received · ${fmtBytes(t.size)} in ${fmtSecs(secs)}`;
  sub.title = fmtRate(t.size / secs);
  const a = document.createElement('a');
  a.href = url; a.download = name; a.className = 'pill mini dl'; a.textContent = 'Save';
  rowAction(t, a);
  a.click();   // most browsers save immediately; the button stays for the rest.
}
const fmtSecs = (s) => (s < 60 ? `${s.toFixed(1)}s` : fmtEta(s));

/* Private storage for spooled receives (see SPOOL_ABOVE). A finished file stays
   until a later visit, so its Save button keeps working; then it's cleared.
   Only files untouched for an hour, so another open Ferry tab keeps its own. */
async function removeEntry(entry) {
  if (!entry) return;
  try { await (await navigator.storage.getDirectory()).removeEntry(entry); } catch {}
}
async function sweepStorage() {
  try {
    const root = await navigator.storage.getDirectory();
    for await (const [name, h] of root.entries()) {
      if (!name.startsWith('rx-')) continue;
      const age = Date.now() - (await h.getFile().catch(() => ({ lastModified: 0 }))).lastModified;
      if (age > 3_600_000) await root.removeEntry(name).catch(() => {});
    }
  } catch {}
}
if (canSpool()) sweepStorage();

/* ---- drag & drop ---- */

// Counting dragenter/dragleave pairs does not work: once the overlay is shown
// it sits under the cursor and fires its own enter/leave events, so the counter
// never settles back to zero and the overlay sticks. Instead, lean on the fact
// that dragover fires repeatedly while a drag is over the window -- show on
// dragover, and hide when they stop arriving. That is self-healing: any missed
// event just means the overlay clears a beat later.
let dragTimer = null;
const hideDrop = () => { clearTimeout(dragTimer); dragTimer = null; $('drop-overlay').hidden = true; };
const showDrop = () => {
  $('drop-overlay').hidden = false;
  clearTimeout(dragTimer);
  // Firefox only fires dragover ~every 350ms when the pointer is still, so the
  // window has to be comfortably longer than that.
  dragTimer = setTimeout(hideDrop, 600);
};
const draggingFiles = (e) => [...(e.dataTransfer?.types ?? [])].includes('Files');

addEventListener('dragover', (e) => {
  e.preventDefault();               // required, or the browser opens the file
  if (draggingFiles(e)) showDrop();
});
addEventListener('dragend', hideDrop);
addEventListener('drop', (e) => {
  e.preventDefault();
  hideDrop();
  const files = [...e.dataTransfer.files];
  if (!files.length) return;
  const list = [...peers.values()];
  // One peer is the common case -- don't make the user aim.
  if (list.length === 1) connForSending(list[0].id).enqueue(files);
  else if (list.length === 0) alert('No device to send to. Open this page on the other device first.');
  else alert('Several devices are here — tap the one you want to send to.');
});

/* ---- dialogs ---- */

$('btn-shortcut').onclick = () => $('dlg-shortcut').showModal();

// Close any dialog from several routes, so no single control is a single point
// of failure: the header x, the footer button, a click on the backdrop, and the
// native Escape key. The footer button alone was fragile -- .dlg-body scrolls at
// 78vh, so on a short screen it can sit below the fold.
for (const dlg of document.querySelectorAll('dialog')) {
  dlg.addEventListener('click', (e) => {
    // A click whose target is the dialog itself came from the backdrop or the
    // dialog's own padding, never from the content wrapped inside it.
    if (e.target === dlg || e.target.closest?.('[data-close]')) closeDialog(dlg);
  });
  dlg.addEventListener('cancel', (e) => { e.preventDefault(); closeDialog(dlg); });
}

/* ---- installed app: service worker + Android share sheet ---- */

// Files handed over by the Android share sheet, waiting for a device to be tapped.
let shared = [];
const SHARE_CACHE = 'bd-share';
const INCOMING_CACHE = 'bd-share-incoming';   // just arrived through /share, not yet confirmed

/**
 * The service worker parks shared files in Cache Storage and redirects here with
 * `?share=N`. Pull them out as File objects; they stay cached until sent or
 * discarded, so a reload in between does not lose them.
 */
async function loadShared() {
  const n = QS.get('share');
  if (n === null) return;
  history.replaceState(null, '', '/');
  if (n === 'missed') return showShareBanner('Share did not reach the app — open Ferry once, then share again.', true);
  if (!('caches' in window)) return;
  // Any website can submit a form to /share, so what arrives there is only a
  // proposal: it replaces nothing until the user says these are their files.
  const incoming = await readShareCache(INCOMING_CACHE);
  await caches.delete(INCOMING_CACHE).catch(() => {});
  if (incoming.length && confirm(`Send these with Ferry?\n\n${incoming.map((f) => cleanName(f.name)).join('\n')}\n\nOnly continue if you just shared them to Ferry yourself.`)) {
    await caches.delete(SHARE_CACHE).catch(() => {});
    const cache = await caches.open(SHARE_CACHE);
    let i = 0;
    for (const f of incoming) {
      await cache.put(`/__shared/${i++}`, new Response(f, {
        headers: { 'content-type': f.type || 'application/octet-stream', 'x-name': encodeURIComponent(f.name) },
      }));
    }
  }
  shared = await readShareCache(SHARE_CACHE);
  renderShared();
}

async function readShareCache(name) {
  const cache = await caches.open(name);
  const files = [];
  for (const req of await cache.keys()) {
    const res = await cache.match(req);
    if (!res) continue;
    const blob = await res.blob();
    const fname = decodeURIComponent(res.headers.get('x-name') || 'shared-file');
    files.push(new File([blob], fname, { type: blob.type || res.headers.get('content-type') || '' }));
  }
  return files;
}

function renderShared() {
  if (!shared.length) { $('share-banner').hidden = true; return; }
  const total = shared.reduce((n, f) => n + f.size, 0);
  showShareBanner(shared.length === 1
    ? `${shared[0].name} · ${fmtBytes(total)} ready to send`
    : `${shared.length} files · ${fmtBytes(total)} ready to send`);
}

function showShareBanner(title, isError = false) {
  $('share-title').textContent = title;
  $('share-sub').textContent = isError ? 'Nothing was sent' : 'Tap a device to send';
  $('share-sub').className = `share-sub${isError ? ' err' : ''}`;
  $('share-banner').hidden = false;
}

async function clearShared() {
  shared = [];
  renderShared();
  try { await caches.delete(SHARE_CACHE); } catch {}
}

function sendShared(peerId) {
  const files = shared;
  connForSending(peerId).enqueue(files);
  clearShared();
}

$('share-clear').onclick = clearShared;

if ('serviceWorker' in navigator) {
  // Versioned URL: Cloudflare caches .js at the edge for hours, so an
  // unversioned worker could stay stale long after a deploy.
  const v = document.querySelector('meta[name="bd-version"]')?.content || '';
  navigator.serviceWorker.register(`/sw.js${v ? `?v=${v}` : ''}`).catch((e) => console.warn('[browserdrop] service worker:', e));
}

try { localStorage.removeItem('bd.room'); } catch {}   // code rooms no longer exist
loadShared();
$('host').textContent = location.host;

for (const b of document.querySelectorAll('[data-pair]')) b.onclick = openPairDialog;
$('pair-stop').onclick = () => stopPair();

/* Light / dark / automatic. The inline script in <head> applies the saved
   choice before the first paint; this keeps the switch and the browser's
   toolbar colour (theme-color) in step with it. */
const THEME_BG = { light: '#f6f5f2', dark: '#0b0b0c' };
function setTheme(t, save = true) {
  if (save) { try { localStorage.setItem('bd.theme', t); } catch {} }
  if (t === 'light' || t === 'dark') document.documentElement.dataset.theme = t;
  else delete document.documentElement.dataset.theme;
  for (const m of document.querySelectorAll('meta[name="theme-color"]')) {
    m.content = THEME_BG[t] || (m.media.includes('dark') ? THEME_BG.dark : THEME_BG.light);
  }
  const btns = [...$('theme').querySelectorAll('button')];
  for (const b of btns) b.setAttribute('aria-pressed', String(b.dataset.theme === t));
  $('theme').style.setProperty('--k', btns.findIndex((b) => b.dataset.theme === t));
}
// The new look sweeps out from the button that was pressed, as a growing circle.
$('theme').onclick = (e) => {
  const b = e.target.closest('button[data-theme]');
  if (!b) return;
  if (!document.startViewTransition || calm.matches) { setTheme(b.dataset.theme); return; }
  const [x, y] = centreOf(b);
  const r = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));
  document.startViewTransition(() => setTheme(b.dataset.theme)).ready.then(() => {
    document.documentElement.animate(
      { clipPath: [`circle(0 at ${x}px ${y}px)`, `circle(${r}px at ${x}px ${y}px)`] },
      { duration: 700, easing: 'cubic-bezier(.4, 0, .2, 1)', pseudoElement: '::view-transition-new(root)' });
  }).catch(() => {});
};
setTheme((() => { try { return localStorage.getItem('bd.theme') || 'auto'; } catch { return 'auto'; } })(), false);
$('speed-tip-ok').onclick = () => {
  $('speed-tip').hidden = true;
  try { localStorage.setItem(SPEED_TIP_KEY, String(Date.now())); } catch {}
};
// One screen, no scrolling: everything but the radar is measured, and the radar
// takes the height that's left (--below, used by --stage in style.css). Its
// floor keeps it usable; a long transfer list still scrolls.
// Side by side (wide screens), only the nav and the page's padding count.
{
  const wrap = document.querySelector('.wrap'), nav = document.querySelector('.nav');
  const fit = () => {
    const cs = getComputedStyle(wrap);
    const below = getComputedStyle($('side')).display === 'contents'
      ? nav.offsetHeight + wrap.offsetHeight - $('stage').offsetHeight
      : nav.offsetHeight + parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom);
    document.documentElement.style.setProperty('--below', `${below}px`);
  };
  new ResizeObserver(fit).observe(wrap);
}

// Ferry for Chrome: offered on computers running a Chromium browser, until dismissed.
const CHROME_STORE = 'https://chromewebstore.google.com/detail/ferry-send-files-nearby/aigfljpajgonlpcmpgdppnpnbdggcakn';
for (const a of document.querySelectorAll('[data-chrome-store]')) a.href = CHROME_STORE;
{
  const chromium = navigator.userAgentData?.brands?.some((b) => b.brand === 'Chromium') ?? /Chrome\//.test(navigator.userAgent);
  const computer = ['mac', 'windows', 'desktop'].includes(deviceKind());
  let dismissed = false;
  try { dismissed = !!localStorage.getItem('bd.extCardX'); } catch {}
  $('ext-card').hidden = !(chromium && computer && !dismissed);
  $('ext-card-x').onclick = () => {
    $('ext-card').hidden = true;
    try { localStorage.setItem('bd.extCardX', '1'); } catch {}
  };
}
$('pair-prompt-x').onclick = () => {
  promptDismissed = true;
  try { sessionStorage.setItem('bd.pairPromptX', '1'); } catch {}
  updatePairPrompt();
};
$('pair-join').onsubmit = (e) => {
  e.preventDefault();
  const code = $('pair-input').value.replace(/\D/g, '');
  if (code.length !== 6) { $('pair-status').textContent = 'Enter the 6-digit code shown on the other device.'; return; }
  if (code !== PAIR.code) startPair(code, false);
};
$('pair-input').oninput = (e) => {
  const d = e.target.value.replace(/\D/g, '').slice(0, 6);
  e.target.value = d.length > 3 ? `${d.slice(0, 3)} ${d.slice(3)}` : d;
};
// A reload keeps an active pairing (same tab, same code).
try {
  const saved = JSON.parse(sessionStorage.getItem('bd.pair') || 'null');
  if (saved?.code && Date.now() - saved.started < PAIR_TTL_MS) {
    Object.assign(PAIR, { code: saved.code, hosting: saved.hosting, started: saved.started });
    openPairSocket();
  }
} catch {}
renderMe();
renderPeers();
connect();
