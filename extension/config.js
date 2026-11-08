// The signalling server: the same one the website and the Android app use.
// tests/e2e_extension.py swaps this (and the manifest's host permission) for a
// local `wrangler dev`.
export const SERVER = 'https://ferry.luitlabs.in';
