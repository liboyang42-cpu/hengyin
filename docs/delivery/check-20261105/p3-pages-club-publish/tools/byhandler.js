// returns the centre of the first visible element whose tap (or disabledtap) handler has the given name
window.__byH = function (name) {
  let hit = null;
  (function walk(r) { for (const el of r.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); if (hit) return; const on = el.__on || {}; for (const k of Object.keys(on)) { const h = on[k] && on[k].h; if (h === name) { const b = el.getBoundingClientRect(); if (b.width > 2 && b.height > 2) { hit = { x: b.left + b.width / 2, y: b.top + b.height / 2, ev: k, txt: (el.innerText || '').slice(0, 20) }; return; } } } } })(document);
  return hit;
};
