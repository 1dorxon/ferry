"""The receiver vanishes mid-transfer (as when iOS kills a tab), then comes back.

The sender must say the connection was lost, in words, with Try again; and Try
again must reach the reloaded receiver over a fresh connection and finish.

    python3 tests/e2e_lost.py [bytes] [base_url]
"""
import asyncio, hashlib, os, sys, tempfile
from playwright.async_api import async_playwright

SIZE = int(sys.argv[1]) if len(sys.argv) > 1 else 300 * 1024 * 1024
BASE = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8899/"

async def main():
    work = tempfile.mkdtemp()
    src = os.path.join(work, "movie.mp4")
    with open(src, "wb") as f: f.write(os.urandom(SIZE))
    want = hashlib.sha256(open(src, "rb").read()).hexdigest()
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--disable-features=WebRtcHideLocalIpsWithMdns"])
        async def page():
            pg = await (await b.new_context(accept_downloads=True)).new_page()
            await pg.goto(BASE + "?debug"); return pg
        A = await page(); R = await page()
        await A.wait_for_selector(".peer", timeout=15000); await R.wait_for_selector(".peer", timeout=15000)
        async with A.expect_file_chooser() as fc: await A.click(".peer")
        await (await fc.value).set_files(src)
        await R.click("button:has-text('Accept')", timeout=20000)
        await R.wait_for_function("[...window.__bd.conns.values()][0]?.rx?.received > 20e6", timeout=30000)
        await R.reload()                                   # the tab dies mid-transfer
        await A.wait_for_selector(".row.failed", timeout=60000)
        failed = (await A.inner_text(".row.failed")).replace("\n", " | ")
        print("sender says:", failed[:200])
        await R.wait_for_selector(".peer", timeout=15000)
        await A.click(".row.failed button:has-text('Try again')")
        async with R.expect_download(timeout=600000) as dl:
            await R.click("button:has-text('Accept')", timeout=30000)
        out = os.path.join(work, "got.bin"); await (await dl.value).save_as(out)
        got = hashlib.sha256(open(out, "rb").read()).hexdigest()
        print("retry: hash_match =", got == want)
        await b.close()

asyncio.run(main())
