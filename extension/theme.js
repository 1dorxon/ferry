/* Light / dark / automatic, applied before the page paints (a plain script in
   <head>, so there's no flash of the wrong theme). Automatic follows the system. */
window.ferryTheme = {
  get() { try { return localStorage.getItem('bd.theme') || 'auto'; } catch { return 'auto'; } },
  set(t) {
    try { localStorage.setItem('bd.theme', t); } catch {}
    this.apply(t);
  },
  apply(t) {
    if (t === 'light' || t === 'dark') document.documentElement.dataset.theme = t;
    else delete document.documentElement.dataset.theme;
  },
};
ferryTheme.apply(ferryTheme.get());
