"""Big file into a browser without a save picker: Chromium sends, Firefox accepts.

Firefox, like Safari 26 on iPhone, has the site's private storage but no save
picker, so a file above SPOOL_ABOVE must go to disk as it arrives rather than
into memory. Checks that it did, and that the file comes out byte-identical.

    python3 tests/e2e_spool.py [bytes] [base_url]
"""
import asyncio, hashlib, os, sys, tempfile
from playwright.async_api import async_playwright

SIZE = int(sys.argv[1]) if len(sys.argv) > 1 else 100 * 1024 * 1024
BASE = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8899/"

async def main():
    work = tempfile.mkdtemp()
    src = os.path.join(work, "movie.mp4")
    with open(src, "wb") as f: f.write(os.urandom(SIZE))
    want = hashlib.sha256(open(src, "rb").read()).hexdigest()
    logs = []
    async with async_playwright() as p:
        cb = await p.chromium.launch(args=["--disable-features=WebRtcHideLocalIpsWithMdns"])
        # Firefox hides host addresses behind mDNS names by default; this box has no mDNS.
        fb = await p.firefox.launch(firefox_user_prefs={"media.peerconnection.ice.obfuscate_host_addresses": False})
        async def page(b, name):
            pg = await (await b.new_context(accept_downloads=True)).new_page()
            pg.on("pageerror", lambda e: logs.append(f"{name} PAGEERROR: {e}"))
            await pg.goto(BASE + "?debug"); return pg
        A = await page(cb, "chromium"); R = await page(fb, "firefox")
        await A.wait_for_selector(".peer", timeout=15000); await R.wait_for_selector(".peer", timeout=15000)
        async with A.expect_file_chooser() as fc: await A.click(".peer")
        await (await fc.value).set_files(src)
        await R.wait_for_selector("button:has-text('Accept')", timeout=20000)
        t0 = asyncio.get_event_loop().time()
        async with R.expect_download(timeout=600000) as dl:
            await R.click("button:has-text('Accept')")
            await R.wait_for_function("[...window.__bd.conns.values()][0]?.rx?.received > 0", timeout=30000)
            spooled = await R.evaluate("!!([...window.__bd.conns.values()][0].rx?.handle)")
            in_memory = await R.evaluate("[...window.__bd.conns.values()][0].rx?.parts.length")
        d = await dl.value
        secs = asyncio.get_event_loop().time() - t0
        out = os.path.join(work, "got.bin"); await d.save_as(out)
        got = hashlib.sha256(open(out, "rb").read()).hexdigest()
        print(f"size={SIZE/1048576:.0f}MB time={secs:.1f}s spooled_to_disk={spooled} parts_in_memory={in_memory} "
              f"hash_match={got == want} saved_as={d.suggested_filename}")
        for l in logs: print("  ", l[:200])
        await cb.close(); await fb.close()

asyncio.run(main())
