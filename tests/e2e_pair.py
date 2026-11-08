"""Pairing with a code, across two "networks".

Two browsers that the server puts in different network rooms (as happens when
one device is on IPv4 and the other on IPv6) must not see each other, must be
offered the code after a while, and after pairing must find each other and
transfer a file byte-for-byte. Needs the local Worker started with the
test-only address override:

    cd cloudflare && npx wrangler dev --port 8899 --var ALLOW_TEST_IP:1
    docker run --rm --network host --ipc=host -v "$PWD/tests:/t" -v /tmp:/o \
      mcr.microsoft.com/playwright/python:v1.49.1-jammy \
      sh -c "pip install -q playwright==1.49.1 && python3 /t/e2e_pair.py"
"""
import asyncio, hashlib, os
from playwright.async_api import async_playwright
IPH="Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
WIN="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--disable-features=WebRtcHideLocalIpsWithMdns"])
        errs = []
        async def page(ua, ip, mobile, scheme):
            c = await b.new_context(user_agent=ua, color_scheme=scheme,
                viewport={"width":390,"height":844} if mobile else {"width":1280,"height":860},
                device_scale_factor=2 if mobile else 1, is_mobile=mobile, has_touch=mobile, accept_downloads=True)
            await c.add_cookies([{"name": "testip", "value": ip, "url": "http://127.0.0.1:8899"}])
            pg = await c.new_page(); pg.on("pageerror", lambda e: errs.append(str(e)))
            await pg.goto("http://127.0.0.1:8899/?debug"); return pg
        A = await page(WIN, "203.0.113.1", False, "light")     # PC "on IPv4"
        B = await page(IPH, "2001:db8:1:2::9", True, "dark")      # phone "on IPv6"
        await A.wait_for_timeout(3000)
        print("peers before pairing:", await A.locator(".peer").count(), await B.locator(".peer").count())
        await A.wait_for_selector("#pair-prompt:not([hidden])", timeout=20000)
        await B.wait_for_selector("#pair-prompt:not([hidden])", timeout=20000)
        await A.screenshot(path="/o/1-prompt-desktop.png"); await B.screenshot(path="/o/1-prompt-phone.png")
        await A.click("#pair-prompt [data-pair]"); await A.wait_for_timeout(500)
        code = (await A.text_content("#pair-code")).replace(" ", "")
        print("code:", code)
        await A.screenshot(path="/o/2-code-desktop.png")
        await B.click("#pair-prompt [data-pair]"); await B.wait_for_timeout(300)
        await B.fill("#pair-input", code)
        await B.screenshot(path="/o/3-enter-phone.png")
        await B.click("#pair-join button[type=submit]")
        await A.wait_for_selector(".peer", timeout=15000); await B.wait_for_selector(".peer", timeout=15000)
        await A.wait_for_timeout(300); await A.screenshot(path="/o/4-found-desktop.png")
        await B.wait_for_timeout(2000)
        print("dialogs closed:", not await A.evaluate("document.getElementById('dlg-pair').open"), not await B.evaluate("document.getElementById('dlg-pair').open"))
        print("chip:", await A.text_content("#pair-chip"), "| peer sub:", await B.text_content(".peer-sub"))
        await B.screenshot(path="/o/5-paired-phone.png"); await A.screenshot(path="/o/5-paired-desktop.png")
        src = "/tmp/payload.bin"; data = os.urandom(20*1024*1024); open(src,"wb").write(data)
        async with A.expect_file_chooser() as fc: await A.click(".peer")
        await (await fc.value).set_files(src)
        await B.wait_for_selector("button:has-text('Accept')")
        async with B.expect_download(timeout=120000) as dl:
            await B.click("button:has-text('Accept')")
        d = await dl.value; out = "/tmp/got.bin"; await d.save_as(out)
        print("transfer via pairing, hash match:", hashlib.sha256(open(out,"rb").read()).hexdigest() == hashlib.sha256(data).hexdigest())
        await A.wait_for_selector(".row.done", timeout=20000)
        print("sender row:", (await A.inner_text("#transfers")).replace("\n"," | ")[:100])
        await B.reload(); await B.wait_for_timeout(3000)
        print("after reload, phone still paired:", await B.locator(".peer").count() > 0, (await B.text_content("#pair-chip")).strip()[:30])
        await A.click("#pair-stop"); await A.wait_for_timeout(2500)
        print("after Stop: desktop peers", await A.locator(".peer").count(), "| phone peers", await B.locator(".peer").count())
        print("errors:", errs); await b.close()
asyncio.run(main())
