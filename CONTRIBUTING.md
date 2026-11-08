# Contributing

Thanks for wanting to help. Issues and pull requests are welcome.

## Before you start

- Open an issue first for anything bigger than a small fix, so we can agree on
  the approach before you spend time on it.
- Ferry has a few promises that changes must keep:
  - files only ever go device to device;
  - same network only (no TURN/STUN), unless a pairing code is used;
  - every file waits for Accept;
  - the server runs on Cloudflare's Free plan with no paid features.
- The web app, the Chrome extension and the Android app speak one protocol. A
  change to it must land in all three. Re-record the Android conformance
  traces with `tests/capture_traces.py`.

## Checks

Run the end-to-end tests in `tests/` against `wrangler dev`, and the Android JVM
tests, as the README describes. Include a test with a bug fix where you can.

## Licence of contributions

Ferry is licensed under the AGPL-3.0. By submitting a contribution you agree
that:

1. it is your own work, or you have the right to submit it;
2. it is licensed under the AGPL-3.0, like the rest of Ferry; and
3. you also grant Luitlabs a perpetual, worldwide, royalty-free licence to use,
   change and distribute your contribution under other terms as well, so the
   official apps on Google Play and the Chrome Web Store can keep shipping it.

Sign off your commits (`git commit -s`) to confirm this.
