"""The Chrome extension against the website, both directions.

Loads extension/ (pointed at a local Worker) into Chromium and a phone-sized
copy of the website into a second browser, then checks:
  1. they see each other;
  2. extension -> website, byte-for-byte, with the side panel CLOSED before the
     phone accepts (the engine keeps the File and sends it on its own);
  3. website -> extension, accepted in the panel, saved through chrome.downloads;
  4. website -> extension with no panel open: the Accept/Decline notification
     appears, and its Accept button (pressed via the service worker) receives it;
  5. an MP3 is saved under its own name (not .txt);
  6. text, website -> extension: a notification with Copy, and the panel row;
  7. text, extension -> website, from the clipboard button on a device.

    cd cloudflare && npx wrangler dev --port 8899 --var ALLOWED_ORIGINS:chrome-extension://*
    docker run --rm --network host --ipc=host -v "$PWD/tests:/t" -v "$PWD/extension:/ext:ro" -v /tmp:/o \
      mcr.microsoft.com/playwright/python:v1.49.1-jammy \
      sh -c "pip install -q playwright==1.49.1 && python3 /t/e2e_extension.py"
"""
import asyncio, hashlib, json, os, re, shutil
from playwright.async_api import async_playwright

SERVER = "http://127.0.0.1:8899"
IPH = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
ARGS = ["--disable-features=WebRtcHideLocalIpsWithMdns"]
sha = lambda b: hashlib.sha256(b).hexdigest()

def local_copy():
    """The extension, pointed at the local Worker instead of ferry.luitlabs.in."""
    d = "/tmp/ferry-ext"
    shutil.rmtree(d, ignore_errors=True); shutil.copytree("/ext", d)
    open(f"{d}/config.js", "w").write(f"export const SERVER = '{SERVER}';\n")
    m = json.load(open(f"{d}/manifest.json"))
    m["host_permissions"] = [f"{SERVER}/*"]
    m["content_security_policy"]["extension_pages"] = re.sub(
        r"connect-src[^;]*", f"connect-src 'self' {SERVER} ws://127.0.0.1:8899", m["content_security_policy"]["extension_pages"])
    json.dump(m, open(f"{d}/manifest.json", "w"))
    return d

async def main():
    ext = local_copy()
    errs = []
    async with async_playwright() as p:
        X = await p.chromium.launch_persistent_context("/tmp/ferry-profile", channel="chromium", headless=True,
            args=ARGS + [f"--disable-extensions-except={ext}", f"--load-extension={ext}"],
            viewport={"width": 380, "height": 900}, color_scheme="light")
        sw = X.service_workers[0] if X.service_workers else await X.wait_for_event("serviceworker")
        ext_id = sw.url.split("/")[2]
        print("extension id:", ext_id)

        async def open_panel():
            pg = await X.new_page(); pg.on("pageerror", lambda e: errs.append(f"panel: {e}"))
            await pg.goto(f"chrome-extension://{ext_id}/panel.html"); return pg
        panel = await open_panel()

        b = await p.chromium.launch(args=ARGS)
        wc = await b.new_context(user_agent=IPH, viewport={"width": 390, "height": 844}, device_scale_factor=2,
                                 is_mobile=True, has_touch=True, accept_downloads=True, color_scheme="dark")
        web = await wc.new_page(); web.on("pageerror", lambda e: errs.append(f"web: {e}"))
        await web.goto(f"{SERVER}/?debug")

        await panel.wait_for_selector(".peer", timeout=20000)
        await web.wait_for_selector(".peer", timeout=20000)
        print("1. panel sees:", await panel.inner_text(".peer-name"), "/", await panel.inner_text(".peer-sub"),
              "| web sees:", await web.inner_text(".peer-name"), "/", await web.inner_text(".peer-sub"))
        await panel.screenshot(path="/o/ext-1-panel.png")
        await panel.click("#pair-link"); await panel.wait_for_timeout(600)
        print("   pairing code shown:", (await panel.inner_text("#pair-code")).replace(" ", "").isdigit(), "| chip:", await panel.is_visible("#pair-chip"))
        await panel.emulate_media(color_scheme="dark"); await panel.screenshot(path="/o/ext-1b-pair-dark.png")
        await panel.emulate_media(color_scheme="light"); await panel.click("#pair-stop"); await panel.wait_for_timeout(400)

        # 2. extension -> web; the panel is closed while the phone decides.
        data = os.urandom(30 * 1024 * 1024 + 123); open("/tmp/ext-out.bin", "wb").write(data)
        async with panel.expect_file_chooser() as fc: await panel.click(".peer")
        await (await fc.value).set_files("/tmp/ext-out.bin")
        await web.wait_for_selector("button:has-text('Accept')", timeout=15000)
        await panel.close()
        async with web.expect_download(timeout=120000) as dl:
            await web.click("button:has-text('Accept')")
        await (await dl.value).save_as("/tmp/ext-got-web.bin")
        print("2. extension -> web, panel closed first, hash match:", sha(open("/tmp/ext-got-web.bin", "rb").read()) == sha(data))

        # 3. web -> extension, accepted in the panel.
        panel = await open_panel()
        await panel.wait_for_selector(".row.done", timeout=20000)
        print("   sender row after reopening:", (await panel.inner_text(".row.done .row-sub")))
        back = os.urandom(25 * 1024 * 1024 + 7); open("/tmp/web-out.bin", "wb").write(back)
        async with web.expect_file_chooser() as fc: await web.click(".peer")
        await (await fc.value).set_files("/tmp/web-out.bin")
        await panel.wait_for_selector(".row.ask", timeout=15000)
        notes = await sw.evaluate("chrome.notifications.getAll()")
        print("3. ask notification while panel open:", list(notes.keys()))
        await panel.screenshot(path="/o/ext-2-ask.png")
        await panel.click(".row.ask [data-a=accept]")
        await panel.wait_for_selector(".row.done:has-text('web-out.bin')", timeout=60000)
        await panel.wait_for_timeout(1500)
        got = await panel.evaluate("chrome.downloads.search({orderBy: ['-startTime'], limit: 1})")
        f = got[0]
        print("   download:", f["state"], os.path.basename(f["filename"]), f["fileSize"],
              "| hash match:", f["state"] == "complete" and sha(open(f["filename"], "rb").read()) == sha(back))
        await panel.screenshot(path="/o/ext-3-done.png")

        # 4. web -> extension with no panel: the notification's Accept button.
        await panel.close()
        third = os.urandom(5 * 1024 * 1024); open("/tmp/web-out2.bin", "wb").write(third)
        async with web.expect_file_chooser() as fc: await web.click(".peer")
        await (await fc.value).set_files("/tmp/web-out2.bin")
        nid = None
        for _ in range(50):
            ids = [k for k in (await sw.evaluate("chrome.notifications.getAll()")).keys() if k.startswith("ask:")]
            if ids: nid = ids[0]; break
            await asyncio.sleep(0.3)
        print("4. notification with panel closed:", nid)
        await sw.evaluate("""(tid) => chrome.runtime.sendMessage({ target: 'engine', type: 'answer', tid, accept: true })""", nid[4:])
        await web.wait_for_selector(".row.done:has-text('web-out2.bin')", timeout=60000)
        for _ in range(40):
            got = await sw.evaluate("chrome.downloads.search({orderBy: ['-startTime'], limit: 1})")
            if got[0]["state"] == "complete" and got[0]["fileSize"] == len(third): break
            await asyncio.sleep(0.3)
        f = got[0]
        print("   download:", f["state"], "| hash match:", sha(open(f["filename"], "rb").read()) == sha(third),
              "| notification gone:", nid not in await sw.evaluate("chrome.notifications.getAll()"))
        await asyncio.sleep(1)
        left = await sw.evaluate("""async () => { const r = await navigator.storage.estimate(); return r.usage; }""")
        print("   extension storage left after saving (bytes):", left)
        await web.screenshot(path="/o/ext-4-web.png")

        # 5. An MP3 keeps its name. The stored copy has no extension, and Chrome
        #    used to sniff the ID3 tag as text and save "song.txt".
        mp3 = b"ID3\x04\x00\x00\x00\x00\x00\x23TSSE\x00\x00\x00\x0f\x00\x00\x03Lavf58.76.100\x00" + os.urandom(200_000)
        open("/tmp/song.mp3", "wb").write(mp3)
        async with web.expect_file_chooser() as fc: await web.click(".peer")
        await (await fc.value).set_files("/tmp/song.mp3")
        for _ in range(50):
            ids = [k for k in (await sw.evaluate("chrome.notifications.getAll()")).keys() if k.startswith("ask:")]
            if ids: break
            await asyncio.sleep(0.3)
        await sw.evaluate("""(tid) => chrome.runtime.sendMessage({ target: 'engine', type: 'answer', tid, accept: true })""", ids[0][4:])
        for _ in range(60):
            got = await sw.evaluate("chrome.downloads.search({orderBy: ['-startTime'], limit: 1})")
            if got and got[0]["state"] == "complete" and got[0]["fileSize"] == len(mp3): break
            await asyncio.sleep(0.3)
        print("5. mp3 saved as:", os.path.basename(got[0]["filename"]), "| mime:", got[0]["mime"],
              "| hash match:", sha(open(got[0]["filename"], "rb").read()) == sha(mp3))

        # 6. Text, web -> extension, panel closed: a notification with Copy.
        await web.click("#btn-text"); await web.fill("#text-input", "otp 482913 🙂 from the phone")
        await web.click("#text-send")
        nid = None
        for _ in range(50):
            ids = [k for k in (await sw.evaluate("chrome.notifications.getAll()")).keys() if k.startswith("text:")]
            if ids: nid = ids[0]; break
            await asyncio.sleep(0.3)
        n = (await sw.evaluate("chrome.notifications.getAll()"))
        print("6. text notification:", nid)
        await web.wait_for_selector(".row.text.done", timeout=10000)
        print("   web row:", await web.inner_text(".row.text.done .row-sub"))
        await sw.evaluate("""(tid) => chrome.runtime.sendMessage({ target: 'engine', type: 'copy', tid })""", nid[5:])
        await asyncio.sleep(0.5)
        panel = await open_panel()
        await X.grant_permissions(["clipboard-read", "clipboard-write"])
        await panel.bring_to_front()
        try: clip = await panel.evaluate("navigator.clipboard.readText()")
        except Exception as e: clip = f"(could not read: {str(e)[:80]})"
        print("   notification Copy -> clipboard:", repr(clip))
        await panel.wait_for_selector(".row.text", timeout=5000)
        print("   panel row:", await panel.inner_text(".row.text .row-sub"), "| button:", await panel.inner_text(".row.text .row-act"))
        await panel.screenshot(path="/o/ext-5-text.png")

        # 7. Text, extension -> web, from the clipboard button on the device.
        await panel.click(".peer [data-text]")
        await panel.fill("#compose-text", "https://ferry.luitlabs.in/ from the laptop")
        await panel.screenshot(path="/o/ext-6-compose.png")
        await panel.click("#compose-send")
        await web.wait_for_selector(".row.text.done:has-text('from the laptop')", timeout=10000)
        await panel.wait_for_selector(".row.text.done:has-text('from the laptop')", timeout=10000)
        print("7. web got:", await web.inner_text(".row.text.done:has-text('from the laptop') .row-sub"),
              "| panel:", await panel.inner_text(".row.text.done:has-text('from the laptop') .row-sub"))
        await web.screenshot(path="/o/ext-7-web.png")
        print("errors:", errs)
        await X.close(); await b.close()

asyncio.run(main())
