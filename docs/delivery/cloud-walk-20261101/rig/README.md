# Walkthrough rig

`mpweb-seed/` is an earlier offline demo runtime that renders real WXML/WXSS/JS in headless Chrome. It is a starting point only: it has a stubbed `wx.request`, a fake `getApp`, and only a single page at a time.

- `tools/bundle.py <xcx root> <out.js> <page entries...>`: collects pages, component closure and JS closure.
- `tools/wxss2css.py`: WXSS to CSS. The `RPX_W` environment variable sets device width.
- `tools/compile.mjs`: precompiles WXML to AST plus expression functions (`node compile.mjs <out> <bundle> <patches.json>`).
- `src/runtime.js`: Shadow DOM components, keyed patching, the `wx` and `getApp` shim. `src/driver.js` and `src/ui.js` are helpers.

The rig lane extends this and documents the result in `RIG.md` next to this file.
