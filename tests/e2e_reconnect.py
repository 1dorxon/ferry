"""Signalling drop mid-transfer: both tabs' WebSockets are closed while a file
is moving. The tabs must reconnect as the SAME devices (ids derived from their
per-tab tokens), the peer lists must recover, and the transfer -- which runs
peer-to-peer and never needed the server -- must still complete byte-identical.

    python3 tests/e2e_reconnect.py <bytes> [base_url]
"""
import asyncio, hashlib, os, sys, tempfile
from playwright.async_api import async_playwright

SIZE = int(sys.argv[1]) if len(sys.argv) > 1 else 48 * 1024 * 1024
BASE = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8787/"

async def main():
    work = tempfile.mkdtemp(); src = os.path.join(work, "payload.bin")
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
        async with R.expect_download(timeout=300000) as dl:
            await R.click("button:has-text('Accept')")
            for _ in range(400):
                await asyncio.sleep(0.05)
                w = await A.evaluate("parseFloat(document.querySelector('.fill')?.style.width||'0')")
                if w >= 15: break
            id_before = await A.evaluate("[...window.__bd.conns.keys()]")   # B's id, as A knows it
            await A.evaluate("window.__bd.ws.close()"); await R.evaluate("window.__bd.ws.close()")
            print(f"  closed both signalling sockets at {w:.0f}%")
        d = await dl.value
        out = os.path.join(work, "got.bin"); await d.save_as(out)
        got = hashlib.sha256(open(out, "rb").read()).hexdigest()
        await A.wait_for_selector(".row.done", timeout=60000)
        await asyncio.sleep(3)                                 # let reconnects settle
        conns_after = await A.evaluate("[...window.__bd.conns.keys()]")
        listed_after = await A.evaluate("[...document.querySelectorAll('.peer-name')].map(e => e.textContent)")
        peers_after = await A.locator(".peer").count()
        reconnected = await A.evaluate("window.__bd.ws.readyState === 1")
        print(f"  hash_match={got == want}  reconnected={reconnected}  peer_tiles_after={peers_after}  "
              f"same_peer_id={conns_after == id_before and len(id_before) == 1}")
        print(f"  B's id before={id_before} after={conns_after}")
        print("  sender row:", (await A.inner_text("#transfers")).replace("\n", " | ")[:140])
        for l in logs:
            if "PAGEERROR" in l or "error" in l.lower(): print("   ", l[:160])
        await b.close()

asyncio.run(main())
