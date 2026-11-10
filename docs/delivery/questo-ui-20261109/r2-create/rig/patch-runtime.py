#!/usr/bin/env python3
"""r2-create rig-only harness patch for mpweb/src/runtime.js (never product code).

Adds what real WeChat (WebView renderer) has and the headless runtime lacked:
  1. WXS event handlers get (event, ownerInstance) with the ComponentDescriptor API
     (setStyle/addClass/removeClass/hasClass/getDataset/getState/selectComponent/
     selectAllComponents/callMethod/requestAnimationFrame); returning false stops propagation.
  2. WXS `change:prop="{{m.fn}}"` observers: fn(newValue, oldValue, ownerInstance, instance).
  3. textarea: a changed controlled `value` applies even while focused, `focus`/`cursor` are honoured,
     `auto-height` follows the content, blur/input carry `cursor` (same as qx-a5d's harness patch).
Usage: python3 patch-runtime.py <path/to/runtime.js>
"""
import sys

path = sys.argv[1]
src = open(path, encoding='utf-8').read()
MARK = '/* R2-CREATE HARNESS PATCH */'
if MARK in src:
    print('already patched')
    sys.exit(0)


def rep(old, new):
    global src
    if src.count(old) != 1:
        raise SystemExit('anchor not unique: ' + old[:60])
    src = src.replace(old, new)


# 1. WXS handler call
rep("  if (typeof h === 'function') return h.call(owner, evt);",
    "  if (typeof h === 'function') { " + MARK + " evt.instance = wxsDesc(el); var rr = h(evt, wxsDesc(owner && owner.__root)); if (rr === false && evt.__nat) { evt.__nat.stopPropagation(); } return rr; }")
rep("    stopPropagation: function () { native && native.stopPropagation(); }, preventDefault: function () {},",
    "    stopPropagation: function () { native && native.stopPropagation(); }, preventDefault: function () {}, __nat: native,")

# descriptor + change observers + styles/classes surviving re-render
rep("var EVT_RE = /^(capture-bind|capture-catch|mut-bind|bind|catch):?(.+)$/;",
    MARK + """
function wxsDesc(el) {
  if (!el) return null;
  if (el.__wxsDesc) return el.__wxsDesc;
  var isEl = el.nodeType === 1;
  var d = {
    setStyle: function (s) {
      var css = typeof s === 'string' ? s : Object.keys(s || {}).map(function (k) { return k.replace(/[A-Z]/g, function (m) { return '-' + m.toLowerCase(); }) + ':' + s[k]; }).join(';');
      if (isEl) { el.__wxsSt = rpxStr(css); el.style.cssText = (el.__st || '') + ';' + el.__wxsSt; }
      return d;
    },
    addClass: function (c) { if (isEl) { el.__wxsCls = el.__wxsCls || {}; String(c).split(/\\s+/).filter(Boolean).forEach(function (x) { el.__wxsCls[x] = 1; el.classList.add(x); }); } return d; },
    removeClass: function (c) { if (isEl) { el.__wxsCls = el.__wxsCls || {}; String(c).split(/\\s+/).filter(Boolean).forEach(function (x) { delete el.__wxsCls[x]; el.classList.remove(x); }); } return d; },
    hasClass: function (c) { return isEl && el.classList.contains(c); },
    getDataset: function () { return el.__ds || {}; },
    getState: function () { return el.__wxsState || (el.__wxsState = {}); },
    selectComponent: function (sel) { var n = el.querySelector(sel); return n ? wxsDesc(n) : null; },
    selectAllComponents: function (sel) { return Array.prototype.slice.call(el.querySelectorAll(sel)).map(wxsDesc); },
    callMethod: function (name, arg) {
      var o = isEl ? el.__owner : (el.__ownerInst || null);
      if (!o) { var h = el.host; o = h && h.__inst; }
      if (!o || typeof o[name] !== 'function') { MP.log.push('wxs callMethod missing ' + name); return; }
      try { return o[name](arg); } catch (e) { console.error('wxs callMethod ' + name, e); }
    },
    requestAnimationFrame: function (cb) { return window.requestAnimationFrame(cb); },
    getComputedStyle: function (keys) { var cs = window.getComputedStyle(el); var o = {}; (keys || []).forEach(function (k) { o[k] = cs.getPropertyValue(k); }); return o; },
  };
  el.__wxsDesc = d;
  return d;
}
function runChangeObservers(el, p) {
  for (var k in p) {
    if (k.indexOf('change:') !== 0) continue;
    var fn = p[k]; if (typeof fn !== 'function') continue;
    var prop = k.slice(7); var nv = p[prop];
    el.__chg = el.__chg || {};
    var key = JSON.stringify(nv === undefined ? null : nv);
    if (el.__chg[prop] === key) continue;
    var had = Object.prototype.hasOwnProperty.call(el.__chg, prop);
    var ov = had ? JSON.parse(el.__chg[prop]) : undefined;
    el.__chg[prop] = key;
    (function (fn, nv, ov) { Promise.resolve().then(function () { try { var root = el.__owner && el.__owner.__root; if (root && !root.__ownerInst) root.__ownerInst = el.__owner; fn(nv, ov, wxsDesc(root), wxsDesc(el)); } catch (e) { console.error('wxs change observer', e); } }); })(fn, nv, ov);
  }
}
var EVT_RE = /^(capture-bind|capture-catch|mut-bind|bind|catch):?(.+)$/;""")

rep("  if (el.getAttribute('class') !== cls) { if (cls) el.setAttribute('class', cls); else el.removeAttribute('class'); }",
    "  if (el.__wxsCls) { for (var wc in el.__wxsCls) cls += ' ' + wc; } " + MARK + "\n  if (el.getAttribute('class') !== cls) { if (cls) el.setAttribute('class', cls); else el.removeAttribute('class'); }")
rep("  if (el.__st !== st) { el.style.cssText = st; el.__st = st; }",
    "  if (el.__st !== st) { el.style.cssText = st + (el.__wxsSt ? ';' + el.__wxsSt : ''); el.__st = st; } " + MARK + "\n  runChangeObservers(el, p);")

# 3. textarea controlled value / focus / cursor / auto-height
rep("    if (p.value != null && document.activeElement !== inp && inp.value !== String(p.value)) inp.value = String(p.value);",
    "    " + MARK + """
    var pv = p.value == null ? null : String(p.value);
    if (pv != null && inp.value !== pv && (document.activeElement !== inp || el.__lastPv !== pv)) inp.value = pv;
    el.__lastPv = pv;
    if (tag === 'textarea' && p['auto-height'] != null && p['auto-height'] !== false) { el.__autoH = true; rST(function () { el.style.height = 'auto'; inp.style.height = '0px'; var h = inp.scrollHeight; inp.style.height = ''; el.style.height = Math.max(h, 20) + 'px'; }, 0); }
    var wantFocus = p.focus === true || p.focus === 'true';
    if (wantFocus && !el.__hadFocus) { rST(function () { try { inp.focus(); var c = Number(p.cursor); if (c >= 0) inp.setSelectionRange(c, c); } catch (x) {} }, 0); }
    el.__hadFocus = wantFocus;""")
rep("  inp.addEventListener('blur', function () { fire(el, 'blur', { value: inp.value }); });",
    "  inp.addEventListener('blur', function () { fire(el, 'blur', { value: inp.value, cursor: inp.selectionStart }); }); " + MARK + "\n  inp.addEventListener('input', function () { if (el.__autoH) { inp.style.height = '0px'; var h = inp.scrollHeight; inp.style.height = ''; el.style.height = Math.max(h, 20) + 'px'; } });")

open(path, 'w', encoding='utf-8').write(src)
print('patched')
