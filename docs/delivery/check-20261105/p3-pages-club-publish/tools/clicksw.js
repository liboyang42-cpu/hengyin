// click the cy-switch in the row whose label contains the given text (real mouse click at its centre)
window.__swBox = function (label) {
  let row = null;
  (function walk(r) { for (const el of r.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); if (!row && el.classList && el.classList.contains('frow') && el.textContent.includes(label)) row = el; } })(document);
  if (!row) return null; const sw = row.querySelector('cy-switch') || row.lastElementChild; sw.scrollIntoView({ block: 'center' }); const b = sw.getBoundingClientRect(); return { x: b.left + b.width / 2, y: b.top + b.height / 2 };
};
