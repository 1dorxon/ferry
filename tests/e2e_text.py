"""The clipboard: text between two copies of the website.

  1. A sends a long text (several parts, an emoji on a part boundary) from the
     "Send text" sheet; B shows it with Copy, and Copy puts exactly it on the
     clipboard. A's row says Sent.
  2. Pasting on B's page (outside any field) opens the sheet with the pasted
     text; sending it reaches A.
  3. A peer that never answers (an older Ferry) gets "may need an update".

    cd cloudflare && npx wrangler dev --port 8899
    docker run --rm --network host --ipc=host -v "$PWD/tests:/t" -v /tmp:/o \
      mcr.microsoft.com/playwright/python:v1.49.1-jammy \
      sh -c "pip install -q playwright==1.49.1 && python3 /t/e2e_text.py"
"""
import asyncio, sys
from playwright.async_api import async_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8899/"
# 8191 ASCII characters then an emoji: its two halves would straddle the first
# 8192-character part boundary if the split were naive.
LONG = "a" * 8191 + "🙂" + "Line two\nhttps://example.com/x?y=1 — ünïcødé ✓\n" + "b" * 9000 + "\tend"


async def main():
    errs = []
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--disable-features=WebRtcHideLocalIpsWithMdns"])

        async def page(name):
            ctx = await b.new_context(permissions=["clipboard-read", "clipboard-write"], viewport={"width": 420, "height": 900})
            pg = await ctx.new_page()
            pg.on("pageerror", lambda e: errs.append(f"{name}: {e}"))
            await pg.goto(BASE + "?debug")
            return pg

        A = await page("A"); B = await page("B")
        await A.wait_for_selector(".peer", timeout=15000); await B.wait_for_selector(".peer", timeout=15000)

        # 1. A -> B from the sheet.
        await A.click("#btn-text")
        await A.fill("#text-input", LONG)
        print("1. sheet: one device preselected:", await A.get_attribute("#text-to button", "aria-checked"),
              "| count:", await A.inner_text("#text-count"))
        await A.screenshot(path="/o/text-1-sheet.png")
        await A.click("#text-send")
        await B.wait_for_selector(".row.text.done", timeout=15000)
        await A.wait_for_selector(".row.text.done", timeout=15000)
        print("   sender row:", await A.inner_text(".row.text .row-sub"))
        print("   receiver row:", await B.inner_text(".row.text .row-sub"))
        await B.click(".row.text button:has-text('Copy')")
        await B.wait_for_timeout(200)
        clip = await B.evaluate("navigator.clipboard.readText()")
        print("   copied text identical:", clip == LONG, len(clip), len(LONG))
        await B.screenshot(path="/o/text-2-received.png")

        # 2. Paste anywhere on B -> the sheet opens with it -> send to A.
        await B.evaluate("""() => {
            const dt = new DataTransfer(); dt.setData('text/plain', 'pasted from B 📋');
            document.body.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true }));
        }""")
        await B.wait_for_selector("#dlg-text[open]", timeout=3000)
        print("2. paste opened the sheet with:", repr(await B.input_value("#text-input")))
        await B.click("#text-send")
        await A.wait_for_selector(".row.text.done:has-text('pasted from B')", timeout=15000)
        print("   A got it:", await A.inner_text(".row.text.done:has-text('pasted from B') .row-sub"))

        # 3. A peer that ignores "text" (Ferry before the clipboard): B stops answering.
        await B.evaluate("""() => { for (const c of window.__bd.conns.values()) { const f = c.onTextPart.bind(c); c.onTextPart = () => {}; } }""")
        await A.click("#btn-text"); await A.fill("#text-input", "nobody answers")
        await A.evaluate("() => { const real = setTimeout; window.setTimeout = (f, ms, ...r) => real(f, ms === 20000 ? 500 : ms, ...r); }")
        await A.click("#text-send")
        await A.wait_for_selector(".row.failed:has-text('nobody answers')", timeout=5000)
        print("3. old peer:", await A.inner_text(".row.failed .row-sub"))

        print("errors:", errs)
        await b.close()

asyncio.run(main())
