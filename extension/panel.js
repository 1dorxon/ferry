/* Ferry side panel: a view of the engine (engine.js), which owns every
 * connection and transfer. Closing the panel stops nothing. */

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmtBytes = (n) => {
  if (n < 1024) return `${n} B`;
  const u = ['KB', 'MB', 'GB', 'TB'];
  let i = -1;
  do { n /= 1024; i++; } while (n >= 1024 && i < u.length - 1);
  return `${n < 10 ? n.toFixed(1) : Math.round(n)} ${u[i]}`;
};
const fmtRate = (bps) => (bps >= 1e6 ? `${(bps / 1e6).toFixed(1)} MB/s` : `${Math.round(bps / 1e3)} KB/s`);
const fmtCode = (c) => (c ? `${c.slice(0, 3)} ${c.slice(3)}` : '––– –––');

const svg = (body, size = 14) =>
  `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">${body}</svg>`;
const ARROW_UP = svg('<path d="M12 19V5"/><path d="M6 11l6-6 6 6"/>');
const ARROW_DOWN = svg('<path d="M12 5v14"/><path d="M6 13l6 6 6-6"/>');
const X_ICON = svg('<path d="M7 7l10 10M17 7L7 17"/>', 13);
const CLIP_ICON = svg('<rect x="6" y="4.5" width="12" height="16.5" rx="2"/><path d="M9.5 4.5V3h5v1.5"/><path d="M9 10h6M9 13.5h6M9 17h3.5"/>', 15);
const TEXT_MAX = 1_000_000;   // as in engine.js
const fmtChars = (n) => (n === 1 ? '1 character' : `${n.toLocaleString()} characters`);
const ART = { iphone: 'phone', android: 'phone', ipad: 'tablet', mac: 'laptop', windows: 'desktop', desktop: 'desktop' };

const engine = new BroadcastChannel('ferry');
const cmd = (m) => engine.postMessage(m);
let S = null;

/* ---- start: make sure the engine runs, then ask it for the state ---- */

engine.onmessage = ({ data }) => {
  if (data.ev === 'state') render(data.state);
  else if (data.ev === 'copied') {
    const b = document.querySelector(`[data-t="${data.tid}"] [data-a="copy"]`);
    if (b) { b.textContent = data.ok ? 'Copied' : 'Couldn\'t copy'; setTimeout(() => { b.textContent = 'Copy'; }, 1600); }
  }
};
const hello = () => { if (!S) { cmd({ cmd: 'hello' }); setTimeout(hello, 800); } };
chrome.runtime.sendMessage({ target: 'bg', type: 'ensure' }).catch(() => {}).finally(hello);

/* ---- render ---- */

function render(state) {
  S = state;
  renderMe();
  renderPeers();
  renderPair();
  renderTransfers();
  if (!$('compose').hidden) renderComposeTo();
  $('speed-tip').hidden = !S.speedTip;
  $('speed-tip-rate').textContent = S.speedTip || '';
}

function renderMe() {
  const { me } = S;
  $('dot').className = `dot ${me.online ? 'on' : 'off'}`;
  $('status-text').textContent = me.online ? 'Visible to phones on this Wi-Fi' : 'Reconnecting…';
  $('me-art').src = `img/dev-${ART[me.device] || 'desktop'}.webp`;
  if ($('me-edit').hidden) $('me-name-text').textContent = me.name;
}

function renderPeers() {
  const box = $('peers');
  const seen = new Set();
  for (const p of S.peers) {
    seen.add(p.id);
    let el = box.querySelector(`[data-peer="${CSS.escape(p.id)}"]`);
    if (!el) {
      el = document.createElement('div');
      el.className = 'peer card';
      el.dataset.peer = p.id;
      el.innerHTML = `<span class="peer-av"><span class="peer-ring"></span><img alt=""></span>
        <span class="peer-main"><span class="peer-name"></span><span class="peer-sub"></span></span>
        <span class="peer-btns">
          <button type="button" class="icon-btn" data-text title="Send text or a link">${CLIP_ICON}</button>
          <button type="button" class="btn tint" data-files>Send</button>
        </span>`;
      // The whole row sends files; the clipboard button sends text.
      el.onclick = (e) => (e.target.closest('[data-text]') ? openCompose(p.id) : pickFilesFor(p.id));
      // Drop files straight on a device.
      el.ondragover = (e) => { if (draggingFiles(e)) { e.preventDefault(); el.classList.add('over'); } };
      el.ondragleave = () => el.classList.remove('over');
      el.ondrop = (e) => {
        e.preventDefault(); e.stopPropagation();
        el.classList.remove('over'); hideDrop();
        send(p.id, [...e.dataTransfer.files]);
      };
      box.appendChild(el);
    }
    el.querySelector('img').src = `img/dev-${ART[p.device] || 'laptop'}.webp`;
    el.querySelector('.peer-name').textContent = p.name;
    el.querySelector('.peer-sub').textContent = p.kind + (p.paired ? ' · paired' : '');
    el.querySelector('[data-files]').setAttribute('aria-label', `Send files to ${p.name}`);
    el.querySelector('[data-text]').setAttribute('aria-label', `Send text to ${p.name}`);
    el.classList.toggle('busy', p.progress != null);
    if (p.progress != null) el.querySelector('.peer-ring').style.setProperty('--p', Math.min(1, p.progress).toFixed(4));
  }
  for (const el of [...box.children]) if (!seen.has(el.dataset.peer)) el.remove();
  $('empty').hidden = S.peers.length > 0;
}

let pairOpen = false;
let pairCloseTimer = null;
function renderPair() {
  const { pair } = S;
  $('pair-chip').hidden = !pair.code;
  $('pair-chip-code').textContent = fmtCode(pair.code);
  $('pair-link').hidden = !!pair.code || pairOpen;
  $('pair').hidden = !pairOpen;
  $('pair-code').textContent = fmtCode(pair.code);
  const st = $('pair-status');
  st.textContent = !pair.code ? ''
    : pair.found.length ? `✓ Found ${pair.found.join(', ')}`
    : pair.any ? '✓ Found — it was already in your list'
    : pair.hosting ? 'Waiting for the other device…' : 'Joining…';
  st.classList.toggle('ok', pair.any);
  // Found someone: say so, then get out of the way.
  if (pairOpen && pair.any && !pairCloseTimer) {
    pairCloseTimer = setTimeout(() => { pairCloseTimer = null; pairOpen = false; renderPair(); }, 1400);
  }
}

function renderTransfers() {
  const box = $('transfers');
  $('transfers-wrap').hidden = S.transfers.length === 0;
  const want = S.transfers.map((t) => t.id);
  for (const el of [...box.children]) if (!want.includes(el.dataset.t)) el.remove();
  S.transfers.forEach((t, i) => {
    let el = box.querySelector(`[data-t="${t.id}"]`);
    if (!el) el = newRow(t);
    if (box.children[i] !== el) box.insertBefore(el, box.children[i] || null);
    updateRow(el, t);
  });
}

function newRow(t) {
  const el = document.createElement('div');
  el.className = 'row';
  el.dataset.t = t.id;
  el.innerHTML = `<div class="row-top">
      <span class="row-ic">${t.kind === 'text' ? CLIP_ICON : t.dir === 'out' ? ARROW_UP : ARROW_DOWN}</span>
      <div class="row-main"><div class="row-name" title="${esc(t.name)}">${esc(t.name)}</div><div class="row-sub"></div></div>
      <div class="row-act"></div>
    </div>
    <div class="row-body">
      ${t.kind === 'text' ? `<div class="row-text" title="Show all">${esc(t.text)}${t.text.length < t.size ? '…' : ''}</div>` : ''}
      <div class="row-ask" hidden>
        <button type="button" class="btn plain big" data-a="decline">Decline</button>
        <button type="button" class="btn primary big" data-a="accept">Accept</button>
      </div>
      <div class="track" hidden><div class="fill"></div></div>
      <div class="row-stats" hidden></div>
    </div>`;
  el.onclick = (e) => {
    const box = e.target.closest('.row-text');
    if (box) { box.classList.add('open'); box.removeAttribute('title'); return; }
    const a = e.target.closest('[data-a]')?.dataset.a;
    if (!a) return;
    if (a === 'show') chrome.downloads.show(Number(el.dataset.dl));
    else cmd({ cmd: a, tid: t.id });
  };
  return el;
}

function updateRow(el, t) {
  const live = t.state === 'active';
  const ended = t.state === 'done' || t.state === 'failed';
  el.className = `row ${t.state}${t.kind === 'text' ? ' text' : ''}`;
  const sub = el.querySelector('.row-sub');
  sub.textContent = live
    ? `${Math.round((t.done / (t.size || 1)) * 100)}% · ${t.dir === 'out' ? 'to' : 'from'} ${t.peer}`
    : t.note || `${fmtBytes(t.size)} · ${t.dir === 'out' ? 'to' : 'from'} ${t.peer}`;
  sub.title = t.title || t.note;
  el.querySelector('.row-ask').hidden = t.state !== 'ask';
  el.querySelector('.track').hidden = !live;
  el.querySelector('.fill').style.width = `${(t.done / (t.size || 1)) * 100}%`;
  const st = el.querySelector('.row-stats');
  st.hidden = !live;
  if (live) {
    st.innerHTML = `<span>${esc(fmtBytes(t.done))} of ${esc(fmtBytes(t.size))}</span>` +
      `<span class="hot">${esc(t.rate ? fmtRate(t.rate) : '—')}</span><span>${esc(t.eta)} left</span>`;
  }
  // One action slot: cancel while it runs, then Show / Try again.
  const act = ended
    ? t.kind === 'text' && t.state === 'done' ? 'copy'
    : t.downloadId != null && t.state === 'done' ? 'show' : t.canRetry ? 'retry' : ''
    : t.state === 'ask' ? '' : 'cancel';
  const slot = el.querySelector('.row-act');
  if (slot.dataset.act !== act) {
    slot.dataset.act = act;
    slot.innerHTML = act === 'cancel' ? `<button type="button" class="icon-btn" data-a="cancel" aria-label="Cancel" title="Cancel">${X_ICON}</button>`
      : act === 'show' ? '<button type="button" class="btn plain" data-a="show">Show</button>'
      : act === 'copy' ? '<button type="button" class="btn primary" data-a="copy">Copy</button>'
      : act === 'retry' ? '<button type="button" class="btn primary" data-a="retry">Try again</button>'
      : '';
  }
  if (t.downloadId != null) el.dataset.dl = t.downloadId;
}

/* ---- sending ---- */

let pickFor = null;
function pickFilesFor(peerId) { pickFor = peerId; $('file-input').click(); }
$('file-input').onchange = (e) => {
  const files = [...e.target.files];
  e.target.value = '';
  if (pickFor && files.length) send(pickFor, files);
  pickFor = null;
};
// File objects survive the trip to the engine (structured clone), and stay
// readable there after this panel closes.
function send(peerId, files) { if (files.length) cmd({ cmd: 'send', peerId, files }); }

/* ---- the clipboard: compose and send text ---- */

let composeTo = null;
function openCompose(peerId, prefill = '') {
  if (peerId) composeTo = peerId;
  const input = $('compose-text');
  if (prefill) input.value = prefill;
  $('compose').hidden = false;
  renderComposeTo();
  input.focus();
  $('compose').scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}
function renderComposeTo() {
  const list = S?.peers || [];
  if (!list.some((p) => p.id === composeTo)) composeTo = list.length === 1 ? list[0].id : null;
  const box = $('compose-to');
  box.replaceChildren();
  if (!list.length) box.innerHTML = '<span class="none">No devices nearby yet.</span>';
  for (const p of list) {
    const b = document.createElement('button');
    b.type = 'button';
    b.setAttribute('role', 'radio');
    b.setAttribute('aria-checked', String(p.id === composeTo));
    b.className = `btn ${p.id === composeTo ? 'primary' : 'plain'}`;
    b.textContent = p.name;
    b.onclick = () => { composeTo = p.id; renderComposeTo(); };
    box.append(b);
  }
  updateComposeCount();
}
function updateComposeCount() {
  const n = $('compose-text').value.length;
  const over = n > TEXT_MAX;
  $('compose-count').textContent = over ? 'Too long — send it as a file' : n ? fmtChars(n) : '';
  $('compose-count').className = `mono${over ? ' err' : ''}`;
  $('compose-send').disabled = !n || over || !composeTo;
}
$('compose-text').oninput = updateComposeCount;
$('compose-close').onclick = () => { $('compose').hidden = true; };
$('compose-form').onsubmit = (e) => {
  e.preventDefault();
  const text = $('compose-text').value;
  if (!text || text.length > TEXT_MAX || !composeTo) return;
  cmd({ cmd: 'sendText', peerId: composeTo, text });
  $('compose-text').value = '';
  $('compose').hidden = true;
};
// Ctrl+V anywhere in the panel (outside a text field) starts a text send.
addEventListener('paste', (e) => {
  if (e.target.closest?.('input, textarea')) return;
  const text = e.clipboardData?.getData('text/plain');
  if (!text || !S?.peers.length) return;
  e.preventDefault();
  openCompose(null, text);
});

/* ---- drag & drop: on a device, or anywhere when there is only one ---- */

let dragTimer = null;
const draggingFiles = (e) => [...(e.dataTransfer?.types ?? [])].includes('Files');
const hideDrop = () => { clearTimeout(dragTimer); $('drop').hidden = true; };
addEventListener('dragover', (e) => {
  e.preventDefault();
  if (!draggingFiles(e)) return;
  const n = S?.peers.length || 0;
  $('drop-text').textContent = n === 1 ? `Drop to send to ${S.peers[0].name}`
    : n ? 'Drop on a device to send' : 'No device to send to yet';
  $('drop').hidden = false;
  clearTimeout(dragTimer);
  dragTimer = setTimeout(hideDrop, 600);
});
addEventListener('drop', (e) => {
  e.preventDefault();
  hideDrop();
  if (S?.peers.length === 1) send(S.peers[0].id, [...e.dataTransfer.files]);
});

/* ---- rename ---- */

$('me-name').onclick = () => {
  const input = $('me-edit');
  input.value = S?.me.name || '';
  $('me-name').hidden = true;
  input.hidden = false;
  input.focus();
  input.select();
};
function endRename(save) {
  const input = $('me-edit');
  if (input.hidden) return;
  input.hidden = true;
  $('me-name').hidden = false;
  if (save && input.value.trim()) { $('me-name-text').textContent = input.value.trim(); cmd({ cmd: 'rename', name: input.value }); }
}
$('me-edit').onkeydown = (e) => {
  if (e.key === 'Enter') { e.preventDefault(); endRename(true); }
  else if (e.key === 'Escape') endRename(false);
};
$('me-edit').onblur = () => endRename(true);

/* ---- pairing ---- */

$('pair-link').onclick = () => { pairOpen = true; cmd({ cmd: 'pair' }); $('pair-input').value = ''; if (S) renderPair(); };
$('pair-close').onclick = () => { pairOpen = false; renderPair(); };
$('pair-stop').onclick = () => { pairOpen = false; cmd({ cmd: 'stopPair' }); };
$('pair-join').onsubmit = (e) => {
  e.preventDefault();
  const code = $('pair-input').value.replace(/\D/g, '');
  if (code.length !== 6) { $('pair-status').textContent = 'Enter the 6-digit code shown on the other device.'; return; }
  cmd({ cmd: 'pair', code });
};
$('pair-input').oninput = (e) => {
  const d = e.target.value.replace(/\D/g, '').slice(0, 6);
  e.target.value = d.length > 3 ? `${d.slice(0, 3)} ${d.slice(3)}` : d;
};

$('clear').onclick = () => cmd({ cmd: 'clear' });

/* ---- theme: light / dark / automatic (theme.js applies it before first paint) ---- */

function renderTheme() {
  for (const b of $('theme').querySelectorAll('button')) b.setAttribute('aria-pressed', String(b.dataset.theme === ferryTheme.get()));
}
$('theme').onclick = (e) => {
  const b = e.target.closest('button[data-theme]');
  if (b) { ferryTheme.set(b.dataset.theme); renderTheme(); }
};
renderTheme();
$('speed-tip-ok').onclick = () => cmd({ cmd: 'speedTipOk' });
