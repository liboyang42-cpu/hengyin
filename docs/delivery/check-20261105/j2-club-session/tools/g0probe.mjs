import { run, S } from './lib.mjs';
// G0 probe: what receives a click at the 立即报名 dock button on pages/topic/index?
await run({ path: 'pages/topic/index/index?id=990014&via=session', identity: 'j2b-m3', agree: true }, async (w) => {
  await S(3500); for (let i = 0; i < 3; i++) { await w.scroll(700); await S(400); }
  const info = await w.page.evaluate(() => {
    const hits = [];
    const walk = (r) => { for (const e of r.querySelectorAll('*')) { if (e.shadowRoot) walk(e.shadowRoot); let own = ''; for (const n of e.childNodes) if (n.nodeType === 3) own += n.data; if (own.trim() === '立即报名') { const b = e.getBoundingClientRect(); hits.push({ tag: e.tagName, cls: e.className, x: b.left + b.width / 2, y: b.top + b.height / 2, w: b.width, h: b.height }); } } };
    walk(document);
    const out = [];
    for (const h of hits) {
      let el = document.elementFromPoint(h.x, h.y); const chain = [];
      while (el && el.shadowRoot) { const inner = el.shadowRoot.elementFromPoint(h.x, h.y); if (!inner || inner === el) break; el = inner; }
      let p = el; for (let i = 0; i < 6 && p; i++) { const cs = getComputedStyle(p); chain.push((p.tagName || '') + '.' + String(p.className || '').slice(0, 60) + ' op=' + cs.opacity + ' pe=' + cs.pointerEvents + ' vis=' + cs.visibility); p = p.parentElement || (p.getRootNode && p.getRootNode().host); }
      out.push({ h, chain });
    }
    return out;
  });
  console.log('PROBE', JSON.stringify(info, null, 1));
});
