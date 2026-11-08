/* Ferry for Chrome: the service worker.
 *
 * Chrome stops this worker whenever it's idle, so it holds nothing. It keeps
 * the engine (an offscreen document, engine.js) open, opens the side panel from
 * the toolbar button, and does the three jobs the engine can't do itself:
 * notifications, saving to Downloads, and the toolbar badge.
 */

const ENGINE = 'engine.html';

let creating = null;
async function ensureEngine() {
  if (await chrome.offscreen.hasDocument?.()) return;
  creating ??= chrome.offscreen.createDocument({
    url: ENGINE,
    reasons: ['WEB_RTC', 'CLIPBOARD'],
    justification: 'Keeps the direct device-to-device connections that carry files and text, so this computer stays visible to your phones and transfers continue while the panel is closed; copies text a phone sent when you press Copy.',
  }).catch((e) => { if (!String(e).includes('single offscreen')) console.warn('[ferry] engine:', e); })
    .finally(() => { creating = null; });
  await creating;
}

chrome.runtime.onInstalled.addListener(() => {
  chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});
  chrome.alarms.create('engine', { periodInMinutes: 5 });
  ensureEngine();
});
chrome.runtime.onStartup.addListener(ensureEngine);
// A safety net: if the engine ever died, it's back within five minutes.
chrome.alarms.onAlarm.addListener(ensureEngine);
ensureEngine();

const toEngine = (msg) => chrome.runtime.sendMessage({ target: 'engine', ...msg }).catch(() => {});

/** Chrome rejects names Windows can't store; keep what we can of the original. */
function safeName(name) {
  // Invisible formatting characters go (a right-to-left override can disguise
  // an extension), and a long name loses its middle, never its extension.
  let n = String(name || '').normalize('NFC').replace(/[\p{Cf}\p{Zl}\p{Zp}]/gu, '')
    .replace(/[\x00-\x1f<>:"/\\|?*~]/g, '_').replace(/^[.\s]+|[.\s]+$/g, '');
  if ([...n].length > 180) {
    const dot = n.lastIndexOf('.');
    const ext = dot > 0 && n.length - dot <= 16 ? [...n.slice(dot)] : [];
    n = [...n].slice(0, 180 - ext.length).join('') + ext.join('');
  }
  if (/^(con|prn|aux|nul|com\d|lpt\d)(\.|$)/i.test(n)) n = `_${n}`;
  return n || 'Ferry file';
}

async function download(url, name) {
  const opts = { url, conflictAction: 'uniquify', saveAs: false };
  try {
    return { downloadId: await chrome.downloads.download({ ...opts, filename: safeName(name) }) };
  } catch {
    try { return { downloadId: await chrome.downloads.download({ ...opts, filename: 'Ferry file' }) }; }
    catch (e) { return { error: String(e.message || e) }; }
  }
}

chrome.runtime.onMessage.addListener((m, _sender, reply) => {
  if (m?.target !== 'bg') return;
  switch (m.type) {
    case 'ensure': ensureEngine().then(() => reply(true)); return true;
    case 'download': download(m.url, m.name).then(reply); return true;
    case 'badge':
      chrome.action.setBadgeText({ text: m.text });
      if (m.text) chrome.action.setBadgeBackgroundColor({ color: m.color });
      break;
    case 'ask':
      // Accept/Decline right on the notification, so nobody has to find the panel.
      chrome.notifications.create(`ask:${m.tid}`, {
        type: 'basic', iconUrl: 'icons/icon-128.png', priority: 2, requireInteraction: true,
        title: `${m.who} wants to send you a file`,
        message: `${m.name} · ${m.size}`,
        buttons: [{ title: 'Accept' }, { title: 'Decline' }],
      });
      break;
    case 'unask': chrome.notifications.clear(`ask:${m.tid}`); break;
    case 'text':
      // Text from a phone: Copy right on the notification.
      chrome.notifications.create(`text:${m.tid}`, {
        type: 'basic', iconUrl: 'icons/icon-128.png', priority: 2,
        title: `${m.who} sent you text`,
        message: m.preview.replace(/\s+/g, ' ').slice(0, 200),
        buttons: [{ title: 'Copy' }],
      });
      break;
  }
});

chrome.notifications.onButtonClicked.addListener((id, button) => {
  if (id.startsWith('text:')) { toEngine({ type: 'copy', tid: id.slice(5) }); chrome.notifications.clear(id); return; }
  if (!id.startsWith('ask:')) return;
  toEngine({ type: 'answer', tid: id.slice(4), accept: button === 0 });
  chrome.notifications.clear(id);
});
// Clicking the notification itself opens the panel, where the same choice waits.
chrome.notifications.onClicked.addListener((id) => {
  if (!id.startsWith('ask:') && !id.startsWith('text:')) return;
  chrome.windows.getLastFocused().then((w) => chrome.sidePanel.open({ windowId: w.id })).catch(() => {});
});

chrome.downloads.onChanged.addListener((d) => {
  const st = d.state?.current;
  if (st === 'complete' || st === 'interrupted') toEngine({ type: 'downloaded', downloadId: d.id, ok: st === 'complete' });
});
