# Security

Please report vulnerabilities **privately**: on GitHub, open the repository's
**Security** tab and choose **Report a vulnerability**. Don't open a public
issue for a security problem.

Useful to include: what you found, how to reproduce it locally (with
`wrangler dev`), and what a person could do with it.

Things we especially care about:
- a file or text reaching a device whose user didn't press Accept;
- devices on different networks finding or connecting to each other without a
  pairing code;
- anything that makes the server store or relay file contents;
- denial of service against the shared signalling server.

Please test against your own local instance (`wrangler dev`), not against
`ferry.luitlabs.in` or other people's devices.
