// runs in page: returns {scroller, sections, stacks, cards, titles}
(() => {
  const P = getCurrentPages().slice(-1)[0];
  const RPX = 750 / 390;
  const R = (v) => Math.round(v * 10) / 10;
  // main scroller
  let sc = null, area = 0;
  (function walk(root) { for (const el of root.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); if (el.scrollHeight > el.clientHeight + 4 && /auto|scroll/.test(getComputedStyle(el).overflowY)) { const r = el.getBoundingClientRect(); const a = r.width * Math.min(r.height, 844); if (a > area && r.height > 200 && r.width > 300) { area = a; sc = el; } } } })(P.__root);
  if (!sc || P.__host.scrollHeight > P.__host.clientHeight + 4 && area < 390 * 400) sc = P.__host;
  const children = (el) => { const k = [...el.children]; if (el.shadowRoot) k.unshift(...el.shadowRoot.children); return k; };
  const cache = new Map();
  function paints(el, cs) {
    if (cs.backgroundImage !== 'none') return true;
    const bg = cs.backgroundColor; if (bg && !/rgba\(.*,\s*0\)$/.test(bg) && bg !== 'transparent') return true;
    if (parseFloat(cs.borderTopWidth) + parseFloat(cs.borderBottomWidth) + parseFloat(cs.borderLeftWidth) > 0 && cs.borderStyle !== 'none') return true;
    if (cs.boxShadow !== 'none') return true;
    if (/^(IMG|CANVAS|VIDEO)$/.test(el.tagName)) return true;
    return false;
  }
  function vis(el) { const cs = getComputedStyle(el); if (cs.display === 'none' || cs.visibility === 'hidden' || +cs.opacity === 0) return null; return cs; }
  // visual rect: painted box, or union of visible descendants + text
  function vrect(el) {
    if (cache.has(el)) return cache.get(el);
    let out = null; const cs = vis(el);
    if (cs) {
      const r = el.getBoundingClientRect();
      if (r.width >= 1 && r.height >= 1 && paints(el, cs)) out = { l: r.left, t: r.top, r: r.right, b: r.bottom };
      else {
        const u = (q) => { if (!q) return; if (!out) out = { ...q }; else { out.l = Math.min(out.l, q.l); out.t = Math.min(out.t, q.t); out.r = Math.max(out.r, q.r); out.b = Math.max(out.b, q.b); } };
        for (const n of el.childNodes) if (n.nodeType === 3 && n.data.trim()) { const rg = document.createRange(); rg.selectNodeContents(n); const b = rg.getBoundingClientRect(); if (b.width > 0) u({ l: b.left, t: b.top, r: b.right, b: b.bottom }); }
        for (const c of children(el)) u(vrect(c));
        if (out && cs.overflow !== 'visible' && r.width > 0) { out.l = Math.max(out.l, r.left); out.r = Math.min(out.r, r.right); out.t = Math.max(out.t, r.top); out.b = Math.min(out.b, r.bottom); if (out.r <= out.l || out.b <= out.t) out = null; }
      }
    }
    cache.set(el, out); return out;
  }
  const scTop = sc.getBoundingClientRect().top; const sst = sc.scrollTop;
  const cls = (el) => (typeof el.className === 'string' ? el.className : '').trim().split(/\s+/).filter(Boolean);
  const role = (el) => { const c = cls(el).filter((x) => !/^(on|active|is-|has-|flex|ep\d|mp-|cy-pressed|cy-fly|last|first|v\d$)/.test(x) && !/--(enter|on|in|show)/.test(x)); return (el.tagName.toLowerCase().replace('wx-', '') + (c.length ? '.' + c[0] : '')); };
  let id = 0; const ids = new Map(); const nid = (el) => { if (!ids.has(el)) ids.set(el, ++id); return ids.get(el); };
  const inScroll = (el) => { let n = el; while (n) { if (n === sc) return true; n = n.parentNode || n.host; } return false; };
  const all = []; (function walk(root) { for (const el of children(root)) { all.push(el); walk(el); } })(P.__root);
  const live = all.filter((el) => { const v = vrect(el); return v && v.r > 2 && v.l < 388 && v.b > -2000; });
  // stacks
  const stacks = [];
  for (const p of live) {
    const k = children(p).map((c) => [c, vrect(c)]).filter(([c, v]) => v && v.r - v.l >= 40 && v.b - v.t >= 6 && v.l < 388 && v.r > 2);
    if (k.length < 2) continue;
    if (p.getBoundingClientRect().width < 300) continue;
    k.sort((a, b) => a[1].t - b[1].t);
    let ok = true; for (let i = 1; i < k.length; i++) if (k[i][1].t < k[i - 1][1].b - 1) ok = false;
    if (!ok) continue;
    const pv = vrect(p); const pr = p.getBoundingClientRect();
    stacks.push({ id: nid(p), parent: role(p), fixed: !inScroll(p), x: R(pr.left), items: k.map(([c, v]) => ({ id: nid(c), role: role(c), t: R(v.t), b: R(v.b), l: R(v.l), r: R(v.r) })), gaps: k.slice(1).map(([c, v], i) => R(v.t - k[i][1].b)) });
  }
  // cards
  const cards = [];
  for (const el of live) {
    const cs = getComputedStyle(el); const r = el.getBoundingClientRect();
    const rad = parseFloat(cs.borderTopLeftRadius) || 0;
    if (r.width < 100 || r.height < 30 || rad < 4) continue;
    if (!paints(el, cs)) continue;
    if (r.left < -1 || r.right > 391) continue;
    cards.push({ id: nid(el), role: role(el), l: R(r.left), r: R(390 - r.right), t: R(r.top), b: R(r.bottom), w: R(r.width), h: R(r.height), radius: R(rad), radii: [cs.borderTopLeftRadius, cs.borderTopRightRadius, cs.borderBottomRightRadius, cs.borderBottomLeftRadius].map((x) => R(parseFloat(x) || 0)), pad: [cs.paddingTop, cs.paddingRight, cs.paddingBottom, cs.paddingLeft].map((x) => R(parseFloat(x))), bg: cs.backgroundColor, fixed: !inScroll(el), cls: cls(el).join(' ') });
  }
  // titles: element with title/head-ish class followed by a sibling
  const titles = [];
  for (const el of live) {
    const c = cls(el).join(' ');
    if (!/(title|head|hd\b|__hd|-hd|tit\b|sec-h|label)/i.test(c) || /(sub|meta|desc)/.test(c)) continue;
    const sibs = children(el.parentNode && el.parentNode.nodeType === 11 ? el.parentNode.host : el.parentElement || el.parentNode.host || el.parentNode);
    const i = sibs.indexOf(el); const nx = sibs.slice(i + 1).find((s) => vrect(s));
    const a = vrect(el); if (!nx || !a) continue; const b = vrect(nx);
    if (b.t < a.b - 1) continue;
    const ar = el.getBoundingClientRect();
    if (ar.width < 40) continue;
    titles.push({ id: nid(el), role: role(el), next: role(nx), nid: nid(nx), gap: R(b.t - a.b), t: R(a.t), b: R(a.b), l: R(a.l), nt: R(b.t), fixed: !inScroll(el) });
  }
  // text left edges inside scroller (page inset): distribution of left of visible text rects
  const lefts = {}; const rights = {};
  for (const el of live) { if (!inScroll(el)) continue; for (const n of el.childNodes) if (n.nodeType === 3 && n.data.trim()) { const rg = document.createRange(); rg.selectNodeContents(n); const b = rg.getBoundingClientRect(); if (b.width > 0 && b.left >= 0 && b.right <= 390) { const k = Math.round(b.left); lefts[k] = (lefts[k] || 0) + 1; } } }
  window.__mids = ids;
  return { scroller: role(sc), scH: R(sc.scrollHeight), scTop: R(scTop), sst, stacks, cards, titles, lefts, rpx: RPX };
})()
