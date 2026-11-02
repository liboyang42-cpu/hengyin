// Runs inside the rig page. window.__measure() returns layout facts in CONTENT coordinates (scrollTop 0 assumed);
// window.__annotate(marks, dy) draws labels shifted by -dy.
(function () {
  function vis(el) {
    if (!el || el.nodeType !== 1) return null;
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || +cs.opacity === 0) return null;
    const r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return null;
    return r;
  }
  function kids(el) {
    const src = el.shadowRoot ? [...el.shadowRoot.children] : [...el.children];
    const out = [];
    for (const k of src) {
      if (k.tagName === 'SLOT') { out.push(...k.assignedElements({ flatten: true })); continue; }
      if (/^(STYLE|SCRIPT|WX-WXS|TEMPLATE)$/.test(k.tagName)) continue;
      out.push(k);
    }
    return out;
  }
  function inFlow(el) { const p = getComputedStyle(el).position; return p !== 'fixed' && p !== 'absolute'; }
  function visKids(el) { return kids(el).filter((k) => vis(k)); }
  function hasSurface(el) {
    const cs = getComputedStyle(el);
    const bg = cs.backgroundColor; const hasBg = bg && !/rgba\(\d+, \d+, \d+, 0\)|transparent/.test(bg);
    const hasImg = cs.backgroundImage && cs.backgroundImage !== 'none' && !/^url/.test(cs.backgroundImage) ? true : false;
    const hasBorder = ['Top', 'Right', 'Bottom', 'Left'].filter((s) => parseFloat(cs['border' + s + 'Width']) > 0 && !/rgba\(\d+, \d+, \d+, 0\)/.test(cs['border' + s + 'Color'])).length >= 3;
    const hasShadow = cs.boxShadow && cs.boxShadow !== 'none';
    return !!(hasBg || hasImg || hasBorder || hasShadow);
  }
  function cls(el) {
    const c = (el.getAttribute('class') || '').trim().split(/\s+/).filter((x) => x && !/^(mp-|cy-pressed|ep\d)/.test(x)).slice(0, 2).join('.');
    let t = el.tagName.toLowerCase().replace(/^wx-/, '');
    if (el.shadowRoot && !/^(mp-page)$/.test(t)) t = '<' + t + '>';
    return c ? t + '.' + c : t;
  }
  // path of component hosts above el (to locate the wxss file)
  function owner(el) {
    const r = el.getRootNode(); const h = r && r.host;
    if (!h) return '';
    if (h.tagName === 'MP-PAGE') return 'page';
    return h.tagName.toLowerCase().replace(/^wx-/, '');
  }
  function rect(el) { const r = vis(el); return { top: r.top, bottom: r.bottom, left: r.left, right: r.right, w: r.width, h: r.height }; }
  function style(el) {
    const cs = getComputedStyle(el);
    return {
      radius: parseFloat(cs.borderTopLeftRadius) || 0,
      radii: [cs.borderTopLeftRadius, cs.borderTopRightRadius, cs.borderBottomRightRadius, cs.borderBottomLeftRadius].map((x) => Math.round(parseFloat(x) || 0)),
      pad: [cs.paddingTop, cs.paddingRight, cs.paddingBottom, cs.paddingLeft].map((x) => Math.round(parseFloat(x) * 10) / 10),
      margin: [cs.marginTop, cs.marginRight, cs.marginBottom, cs.marginLeft].map((x) => Math.round(parseFloat(x) * 10) / 10),
      bg: cs.backgroundColor, fw: cs.fontWeight, fs: cs.fontSize,
    };
  }
  // ink box: union of text leaves, images and surfaced boxes (not the empty padding of wrappers)
  function ink(el, acc) {
    acc = acc || { top: 1e9, bottom: -1e9, left: 1e9, right: -1e9, n: 0 };
    const r = vis(el); if (!r) return acc;
    const add = (q) => { acc.top = Math.min(acc.top, q.top); acc.bottom = Math.max(acc.bottom, q.bottom); acc.left = Math.min(acc.left, q.left); acc.right = Math.max(acc.right, q.right); acc.n++; };
    if (hasSurface(el) || /^WX-(IMAGE|CANVAS|INPUT|TEXTAREA|BUTTON)$/.test(el.tagName)) { add(r); return acc; }
    const k = visKids(el);
    if (!k.length) {
      // text only: use the text's range box, not the line box
      let txt = false; for (const n of (el.shadowRoot ? [] : el.childNodes)) if (n.nodeType === 3 && n.data.trim()) txt = true;
      if (txt) { const rg = document.createRange(); rg.selectNodeContents(el); const q = rg.getBoundingClientRect(); if (q.width > 0) add(q); }
      return acc;
    }
    for (const c of k) ink(c, acc);
    // own text nodes next to child elements
    for (const n of (el.shadowRoot ? [] : el.childNodes)) if (n.nodeType === 3 && n.data.trim()) { const rg = document.createRange(); rg.selectNodeContents(n); const q = rg.getBoundingClientRect(); if (q.width > 0) add(q); }
    return acc;
  }
  function mainContainer() {
    const p = getCurrentPages().slice(-1)[0];
    let best = null, area = 0;
    (function walk(root) { for (const el of root.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); if (el.scrollHeight > el.clientHeight + 4 && /auto|scroll/.test(getComputedStyle(el).overflowY)) { const r = el.getBoundingClientRect(); const a = r.width * r.height; if (a > area && r.height > 300) { area = a; best = el; } } } })(p.__root);
    if (!best || (p.__host.scrollHeight > p.__host.clientHeight + 4)) best = best && area > 390 * 500 ? best : p.__host;
    return { p, sc: best };
  }
  function sectionsOf(start) {
    let cur = start; const chain = [];
    for (let i = 0; i < 10; i++) {
      const k = visKids(cur).filter(inFlow).filter((x) => ink(x).n);
      if (k.length === 1 && (!hasSurface(k[0]) || vis(k[0]).width >= 388)) { chain.push(cls(k[0])); cur = k[0]; continue; }
      return { parent: cur, chain, list: k };
    }
    return { parent: cur, chain, list: [] };
  }
  function cardsIn(el, depth, out, inCard) {
    for (const k of visKids(el)) {
      const r = vis(k);
      if (!inFlow(k) && getComputedStyle(k).position === 'fixed') continue;
      if (hasSurface(k) && r.width >= 100 && r.height >= 30 && !(r.width >= 388 && r.height >= 400) && !/^WX-(IMAGE|INPUT|TEXTAREA)$/.test(k.tagName)) {
        out.push({ cls: cls(k), owner: owner(k), depth, nested: inCard, ...rect(k), ...style(k), text: (k.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 24) });
        cardsIn(k, depth + 1, out, true);
      } else cardsIn(k, depth + 1, out, inCard);
    }
    return out;
  }
  window.__measure = function () {
    const { p, sc } = mainContainer();
    const scr = vis(sc); const st = sc.scrollTop;
    const secs = sectionsOf(sc);
    function stacked(k) { for (let i = 1; i < k.length; i++) { if (vis(k[i]).top < vis(k[i - 1]).bottom - 1) return false; } return true; }
    function expand(el, d) {
      const r = vis(el);
      if (hasSurface(el) || d > 4 || r.width < 300) return [el];
      const k = visKids(el).filter(inFlow).filter((x) => ink(x).n);
      if (k.length < 2 || !stacked(k)) return k.length === 1 && d < 4 ? expand(k[0], d + 1) : [el];
      return k.flatMap((x) => expand(x, d + 1));
    }
    const flat = secs.list.flatMap((x) => expand(x, 0));
    const list = flat.map((el) => {
      const ik = ink(el);
      const k = visKids(el).filter(inFlow).filter((x) => ink(x).n);
      let tg = null;
      if (k.length >= 2 && !hasSurface(el)) {
        const a = ink(k[0]), b = ink(k[1]);
        const head = cls(k[0]);
        if (/title|head|hd|label|caption|section-t|heading/i.test(head) || (a.bottom - a.top) < 40) tg = { head, next: cls(k[1]), gap: b.top - a.bottom, y1: a.bottom, y2: b.top, x: a.left };
      }
      return { cls: cls(el), owner: owner(el), ...rect(el), ...style(el), surface: hasSurface(el), ink: ik, titleGap: tg, text: (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 30) };
    });
    const cards = cardsIn(sc, 0, [], false);
    // fixed chrome: header bottom (top of scroll area content) and bottom bar
    let bottomBar = 844;
    (function walk(root) { for (const el of root.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); const r = vis(el); if (!r) continue; const cs = getComputedStyle(el); if (cs.position === 'fixed' && r.top > 650 && r.width > 300 && r.bottom >= 840) bottomBar = Math.min(bottomBar, r.top); } })(p.__root);
    return { route: p.route, container: cls(sc), cTop: scr.top, cBottom: scr.bottom, scrollTop: st, scrollHeight: sc.scrollHeight, clientHeight: sc.clientHeight, chain: secs.chain, parent: cls(secs.parent), parentStyle: style(secs.parent), sections: list, cards, bottomBar };
  };
  window.__scrollTo = function (y) { const { sc } = mainContainer(); sc.scrollTop = y; sc.dispatchEvent(new Event('scroll')); return sc.scrollTop; };
  window.__annotate = function (marks, dy) {
    let ov = document.getElementById('__ann'); if (ov) ov.remove();
    if (!marks) return;
    ov = document.createElement('div'); ov.id = '__ann';
    ov.style.cssText = 'position:fixed;left:0;top:0;width:390px;height:844px;pointer-events:none;z-index:2147483647;font:bold 10px/1 Arial,sans-serif;overflow:hidden';
    const add = (css, text) => { const d = document.createElement('div'); d.style.cssText = 'position:absolute;' + css; if (text != null) d.textContent = text; ov.appendChild(d); };
    for (const m of marks) {
      const c = m.ok ? '#0a9d3a' : '#e0001b';
      const lab = `background:${c};color:#fff;padding:2px 3px;border-radius:3px;white-space:nowrap;`;
      if (m.kind === 'vgap') {
        const y1 = m.y1 - dy, y2 = m.y2 - dy;
        add(`left:${m.x}px;top:${y1}px;height:${Math.max(1, y2 - y1)}px;width:0;border-left:2px solid ${c};`);
        add(`left:${m.x - 4}px;top:${y1}px;width:10px;border-top:2px solid ${c};`);
        add(`left:${m.x - 4}px;top:${y2 - 2}px;width:10px;border-top:2px solid ${c};`);
        add(`left:${m.x + 5}px;top:${(y1 + y2) / 2 - 7}px;${lab}`, m.label);
      } else if (m.kind === 'hgap') {
        const y = m.y - dy;
        add(`top:${y}px;left:${m.x1}px;width:${Math.max(1, m.x2 - m.x1)}px;border-top:2px solid ${c};`);
        add(`top:${y + 3}px;left:${m.x1 + (m.x2 - m.x1) / 2 - 8}px;${lab}`, m.label);
      } else if (m.kind === 'box') {
        add(`left:${m.x}px;top:${m.y - dy}px;width:${m.w}px;height:${m.h}px;outline:1.5px dashed ${c};border-radius:${m.r || 0}px;box-sizing:border-box`);
      } else if (m.kind === 'tag') {
        add(`left:${m.x}px;top:${m.y - dy}px;${lab}font-size:9px`, m.label);
      }
    }
    document.body.appendChild(ov);
  };
})();
