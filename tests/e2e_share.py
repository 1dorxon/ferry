"""Share-sheet path: a file POSTed to /share (as Android does) must be caught by
the service worker on the device, offered in the banner, and sent to the device
that is tapped -- byte-identical, without the upload ever reaching the server.

    python3 tests/e2e_share.py <bytes> [base_url]
"""
import asyncio, hashlib, os, sys, tempfile
from playwright.async_api import async_playwright

SIZE = int(sys.argv[1]) if len(sys.argv) > 1 else 4 * 1024 * 1024
BASE = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8899/"

async def main():
    work = tempfile.mkdtemp()
    src = os.path.join(work, "holiday.mov")
    with open(src, "wb") as f: f.write(os.urandom(SIZE))
    want = hashlib.sha256(open(src, "rb").read()).hexdigest()
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--disable-features=WebRtcHideLocalIpsWithMdns"])
        logs = []
        async def page(name):
            pg = await (await b.new_context(accept_downloads=True)).new_page()
            pg.on("console", lambda m: logs.append(f"{name}: {m.text}"))
            pg.on("pageerror", lambda e: logs.append(f"{name} PAGEERROR: {e}"))
            await pg.goto(BASE); return pg
        A = await page("A"); R = await page("B")
        # Files arriving through /share are confirmed by the user before Ferry
        # offers them (any website can post to /share). Accept, and record it.
        dialogs = []
        async def on_dialog(d): dialogs.append(d.message); await d.accept()
        A.on("dialog", lambda d: asyncio.ensure_future(on_dialog(d)))
        await A.evaluate("navigator.serviceWorker.ready.then(() => true)")
        await A.reload()                                    # now controlled by the worker
        controlled = await A.evaluate("!!navigator.serviceWorker.controller")
        # Submit the file to /share exactly as the Android share target does.
        await A.evaluate("""() => {
          const f = document.createElement('form');
          f.method = 'post'; f.action = '/share'; f.enctype = 'multipart/form-data';
          const i = document.createElement('input'); i.type = 'file'; i.name = 'files'; i.id = 'shareinput';
          f.appendChild(i); document.body.appendChild(f);
        }""")
        await A.set_input_files("#shareinput", src)
        async with A.expect_navigation():
            await A.evaluate("document.querySelector('#shareinput').form.submit()")
        await A.wait_for_selector("#share-banner:not([hidden])", timeout=10000)
        banner = await A.inner_text("#share-banner")
        await A.wait_for_selector(".peer", timeout=15000); await R.wait_for_selector(".peer", timeout=15000)
        await A.click(".peer")                              # shared files: no file picker
        await R.wait_for_selector("button:has-text('Accept')", timeout=20000)
        async with R.expect_download(timeout=300000) as dl:
            await R.click("button:has-text('Accept')")
        d = await dl.value
        out = os.path.join(work, "got.bin"); await d.save_as(out)
        got = hashlib.sha256(open(out, "rb").read()).hexdigest()
        banner_after = await A.is_hidden("#share-banner")
        print(f"controlled_by_sw={controlled}")
        print(f"banner: {banner.replace(chr(10), ' | ')}")
        print(f"name={d.suggested_filename} hash_match={got == want} banner_cleared={banner_after}")
        for l in logs:
            if "PAGEERROR" in l or "error" in l.lower(): print("  ", l[:160])
        await b.close()

asyncio.run(main())
