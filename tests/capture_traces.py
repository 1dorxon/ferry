"""Record every data-channel message two browsers send, in five scenarios.

The native Android app's WebConformanceTest replays these against its own
protocol code. Re-record after any change to the web client's protocol:

    (cd cloudflare && npx wrangler dev --port 8899)       # local server
    docker run --rm --network host --ipc=host -v "$PWD/tests:/t" \
      mcr.microsoft.com/playwright/python:v1.49.1-jammy \
      sh -c "pip install -q playwright==1.49.1 && python3 /t/capture_traces.py"

then copy tests/traces.json (minus the *_rows keys) to
native-android/app/src/test/resources/web-traces.json.
"""
import asyncio, json, os
from playwright.async_api import async_playwright
HOOK = """
window.__trace = [];
const orig = RTCDataChannel.prototype.send;
RTCDataChannel.prototype.send = function (d) {
  if (typeof d === 'string') window.__trace.push({k: 'text', d});
  else { const b = d instanceof ArrayBuffer ? d : d.buffer;
         window.__trace.push({k: 'bin', off: new DataView(b, 0, 8).getFloat64(0), len: b.byteLength - 8}); }
  return orig.call(this, d);
};
"""
async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(args=["--disable-features=WebRtcHideLocalIpsWithMdns"])
        out = {}
        for scen in ["accept", "decline", "sender_cancel_waiting", "receiver_cancel_mid", "empty"]:
            ctxs = [await b.new_context(accept_downloads=True) for _ in range(2)]
            A, B = [await c.new_page() for c in ctxs]
            for pg in (A, B): await pg.add_init_script(HOOK); await pg.goto("http://127.0.0.1:8899/")
            await A.wait_for_selector(".peer"); await B.wait_for_selector(".peer")
            size = {"accept": 3*1024*1024+123, "decline": 1000, "sender_cancel_waiting": 1000,
                    "receiver_cancel_mid": 400*1024*1024, "empty": 0}[scen]
            path = f"/tmp/{scen}.bin"
            with open(path, "wb") as f: f.write(os.urandom(size))
            async with A.expect_file_chooser() as fc: await A.click(".peer")
            await (await fc.value).set_files(path)
            await B.wait_for_selector("button:has-text('Accept')")
            if scen == "decline":
                await B.click("button:has-text('Decline')")
            elif scen == "sender_cancel_waiting":
                await A.click(".row-x")
            elif scen == "receiver_cancel_mid":
                await B.click("button:has-text('Accept')")
                await B.wait_for_function("(() => { const f = document.querySelector('.row.active .fill'); return f && parseFloat(f.style.width) > 5; })()")
                await B.click(".row-x")
            else:
                async with B.expect_download() as dl:
                    await B.click("button:has-text('Accept')")
                await (await dl.value).path()
            await A.wait_for_timeout(1500)
            ta = await A.evaluate("window.__trace"); tb = await B.evaluate("window.__trace")
            # Collapse runs of binary frames to keep the trace readable.
            def squash(t):
                o = []
                for m in t:
                    if m["k"] == "bin" and o and o[-1]["k"] == "bins": o[-1]["n"] += 1; o[-1]["last"] = m["off"]; o[-1]["lens"].add(m["len"])
                    elif m["k"] == "bin": o.append({"k": "bins", "n": 1, "first": m["off"], "last": m["off"], "lens": {m["len"]}})
                    else: o.append(m)
                for m in o:
                    if "lens" in m: m["lens"] = sorted(m["lens"])
                return o
            out[scen] = {"sender": squash(ta), "receiver": squash(tb),
                         "sender_rows": await A.inner_text("#transfers"), "receiver_rows": await B.inner_text("#transfers")}
            for c in ctxs: await c.close()
        json.dump(out, open("/t/traces.json", "w"), indent=1)
        await b.close()
asyncio.run(main())
