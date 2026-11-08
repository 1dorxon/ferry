"""End-to-end transfer test: two real Chromium pages, one sends, one accepts.

Run inside the Playwright image against a local server:
    python3 tests/e2e.py <bytes> [base_url]
Prints time, rate, whether the received file is byte-identical, and which ICE
candidate types were gathered -- only `host` is allowed (same network only).
"""
import asyncio, hashlib, os, sys, tempfile
from playwright.async_api import async_playwright

SIZE = int(sys.argv[1]) if len(sys.argv) > 1 else 8 * 1024 * 1024
BASE = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8899/"

async def main():
    work = tempfile.mkdtemp()
    src = os.path.join(work, "payload.bin")
    with open(src, "wb") as f: f.write(os.urandom(SIZE))
    want = hashlib.sha256(open(src, "rb").read()).hexdigest()
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--disable-features=WebRtcHideLocalIpsWithMdns"])
        logs = []
        async def page(name):
            pg = await (await b.new_context(accept_downloads=True)).new_page()
            pg.on("console", lambda m: logs.append(f"{name}: {m.text}"))
            pg.on("pageerror", lambda e: logs.append(f"{name} PAGEERROR: {e}"))
            await pg.goto(BASE + "?debug"); return pg
        A = await page("A"); R = await page("B")
        await A.wait_for_selector(".peer", timeout=15000); await R.wait_for_selector(".peer", timeout=15000)
        async with A.expect_file_chooser() as fc: await A.click(".peer")
        await (await fc.value).set_files(src)
        await R.wait_for_selector("button:has-text('Accept')", timeout=20000)
        t0 = asyncio.get_event_loop().time()
        async with R.expect_download(timeout=600000) as dl:
            await R.click("button:has-text('Accept')")
        d = await dl.value
        secs = asyncio.get_event_loop().time() - t0
        out = os.path.join(work, "got.bin"); await d.save_as(out)
        got = hashlib.sha256(open(out, "rb").read()).hexdigest()
        cands = await A.evaluate("[...[...window.__bd.conns.values()][0].candTypes]")
        await A.wait_for_selector(".row.done", timeout=30000)
        row = (await A.inner_text("#transfers")).replace("\n", " | ")
        print(f"size={SIZE/1048576:.0f}MB time={secs:.1f}s rate={SIZE/secs/1048576:.1f}MB/s "
              f"hash_match={got == want} candidates={sorted(cands)}")
        print("sender row:", row[:160])
        for l in logs:
            if "PAGEERROR" in l or "error" in l.lower(): print("  ", l[:160])
        await b.close()

asyncio.run(main())
