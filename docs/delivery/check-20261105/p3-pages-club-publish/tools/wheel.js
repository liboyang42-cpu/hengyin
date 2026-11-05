// HARNESS: the rig cannot spin <picker-view>; fire the cy-date-field's own bindchange with the wanted column indexes.
window.__wheel = function (vals) {
  let hit = null;
  (function walk(r) { for (const el of r.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); const i = el.__inst; if (i && i.data && Array.isArray(i.data.columns) && i.data.show && !hit) hit = i; } })(document);
  if (!hit) return 'no open wheel';
  hit.onColumnChange({ detail: { value: vals } });
  return JSON.stringify(hit.data.index);
};
