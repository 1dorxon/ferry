/* Ferry service worker.
 *
 * Its one job is the Android share sheet. When someone shares files to the app,
 * Android POSTs them to /share as multipart form data. This worker intercepts
 * that request on the device, parks the files in Cache Storage, and redirects to
 * the page, which asks the user to confirm them before offering to send them. The POST never reaches
 * the server: shared files stay on the phone until they go peer-to-peer.
 *
 * Nothing else is intercepted -- the app needs the live signalling server, so
 * there is no offline mode to pretend to.
 */
// Files land here first and are only proposed to the user: any website can
// submit a form to /share, so arriving here proves nothing (see loadShared).
const INCOMING_CACHE = 'bd-share-incoming';

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'POST' || url.pathname !== '/share') return;
  // A form on another site that keeps its referrer policy says where it is from:
  // turn it away without touching anything.
  const origin = e.request.headers.get('origin');
  if (origin && origin !== 'null' && origin !== self.location.origin) {
    e.respondWith(Response.redirect('/', 303));
    return;
  }
  e.respondWith((async () => {
    const form = await e.request.formData();
    const files = form.getAll('files').filter((f) => typeof f === 'object' && f.size >= 0);
    await caches.delete(INCOMING_CACHE);
    const cache = await caches.open(INCOMING_CACHE);
    let i = 0;
    for (const f of files) {
      await cache.put(`/__shared/${i++}`, new Response(f, {
        headers: {
          'content-type': f.type || 'application/octet-stream',
          'x-name': encodeURIComponent(f.name || `shared-${i}`),
        },
      }));
    }
    return Response.redirect(`/?share=${files.length}`, 303);
  })());
});
