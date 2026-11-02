/* mpweb runtime (cloud-walk rig: multi-page, real App, real wx.request; derived from mpweb-seed): runs compiled WeChat mini-program pages/components (real WXML/WXSS/JS) in a browser.
   Expects globals from bundle.js: __F (expr fns), __T (templates), __M (modules), __C (component graph), __CSS, __APPCSS. */
(function () {
'use strict';
var DEV0 = window.__DEVICE || { w: 390, h: 844 };
var K = DEV0.w / 750;
var MP = window.MPWEB = { log: [], unknownWx: {}, speed: 1 };

// ---------------- time scaling (slow motion) ----------------
var rST = window.setTimeout.bind(window), rSI = window.setInterval.bind(window), rCT = window.clearTimeout.bind(window), rCI = window.clearInterval.bind(window);
var rNow = Date.now, rPerf = performance.now.bind(performance), rRAF = window.requestAnimationFrame.bind(window);
var vBase = rNow(), rBase = rPerf();
function vnow() { return vBase + (rPerf() - rBase) * MP.speed; }
MP.setSpeed = function (s) { vBase = vnow(); rBase = rPerf(); MP.speed = s; applyAnimRate(); };
function applyAnimRate() { try { document.getAnimations().forEach(function (a) { if (a.playbackRate !== MP.speed) a.playbackRate = MP.speed; }); } catch (e) {} }
(function loop() { if (MP.speed !== 1) applyAnimRate(); rRAF(loop); })();
var T = MP.timers = {
  setTimeout: function (f, d) { var a = [].slice.call(arguments, 2); return rST(function () { f.apply(null, a); }, (d || 0) / MP.speed); },
  setInterval: function (f, d) { return rSI(f, Math.max(4, (d || 0) / MP.speed)); },
  clearTimeout: rCT, clearInterval: rCI,
  now: vnow,
};
window.setTimeout = T.setTimeout; window.setInterval = T.setInterval;
Date.now = function () { return Math.round(vnow()); };
var RealDate = Date;
var perfOff = rPerf() - vnow();
performance.now = function () { return vnow() + perfOff; };
window.requestAnimationFrame = function (cb) { return rRAF(function () { cb(performance.now()); }); };

// ---------------- helpers ----------------
function rpxStr(s) {
  if (typeof s !== 'string') return s;
  return s.replace(/(-?\d*\.?\d+)rpx/g, function (_, n) { return (parseFloat(n) * K).toFixed(2) + 'px'; })
    .replace(/(-?\d*\.?\d+)vh/g, function (_, n) { return (parseFloat(n) * DEV0.h / 100).toFixed(2) + 'px'; })
    .replace(/(-?\d*\.?\d+)vw/g, function (_, n) { return (parseFloat(n) * DEV0.w / 100).toFixed(2) + 'px'; })
    .replace(/(?:env|constant)\(\s*safe-area-inset-bottom\s*(?:,[^)]*)?\)/g, '34px')
    .replace(/(?:env|constant)\(\s*safe-area-inset-top\s*(?:,[^)]*)?\)/g, '47px')
    .replace(/(?:env|constant)\(\s*safe-area-inset-(?:left|right)\s*(?:,[^)]*)?\)/g, '0px');
}
MP.rpxStr = rpxStr;
function assetUrl(p, dir) {
  if (!p || typeof p !== 'string') return p;
  if (/^(https?:|data:|blob:|wxfile:)/.test(p)) return MP.mapRemote ? MP.mapRemote(p) : p;
  var path;
  if (p[0] === '/') path = p.slice(1);
  else { var segs = (dir || '').split('/').filter(Boolean); p.split('/').forEach(function (s) { if (s === '..') segs.pop(); else if (s !== '.') segs.push(s); }); path = segs.join('/'); }
  return 'mp/' + path;
}
MP.assetUrl = assetUrl;
function camel(s) { return s.replace(/-([a-z])/g, function (_, c) { return c.toUpperCase(); }); }
function clone(v) { if (v === undefined) return v; try { return JSON.parse(JSON.stringify(v)); } catch (e) { return v; } }
function same(a, b) {
  if (a === b) return true;
  if (a && b && typeof a === 'object' && typeof b === 'object') { try { return JSON.stringify(a) === JSON.stringify(b); } catch (e) { return false; } }
  return false;
}
function parsePath(k) { var out = []; String(k).replace(/[^.[\]]+|\[(\d+)\]/g, function (m, idx) { out.push(idx !== undefined ? Number(idx) : m); }); return out; }
function setPath(obj, k, v) {
  var p = parsePath(k), o = obj;
  for (var i = 0; i < p.length - 1; i++) { if (o[p[i]] == null || typeof o[p[i]] !== 'object') o[p[i]] = typeof p[i + 1] === 'number' ? [] : {}; o = o[p[i]]; }
  o[p[p.length - 1]] = v;
}
function getPath(obj, k) { var p = parsePath(k), o = obj; for (var i = 0; i < p.length; i++) { if (o == null) return undefined; o = o[p[i]]; } return o; }
function dirOf(path) { return path.split('/').slice(0, -1).join('/'); }
function resolveRel(from, p) {
  var segs = p[0] === '/' ? [] : dirOf(from).split('/').filter(Boolean);
  p.replace(/^\//, '').split('/').forEach(function (s) { if (s === '..') segs.pop(); else if (s !== '.' && s) segs.push(s); });
  return segs.join('/');
}

// ---------------- modules ----------------
var cache = {}, defs = {}, curDef = null;
function findModule(p) {
  var c = [p, p + '.js', p + '/index.js', p.replace(/\.js$/, '') + '.js'];
  for (var i = 0; i < c.length; i++) if (__M[c[i]]) return c[i];
  return null;
}
function req(path) {
  var key = findModule(path);
  if (!key) throw new Error('module not found: ' + path);
  if (cache[key]) return cache[key].exports;
  var module = { exports: {} }; cache[key] = module;
  var prev = curDef; curDef = key.replace(/\.js$/, '');
  try {
    __M[key].call(module.exports, module, module.exports, function (r) { return req(resolveRel(key, r)); },
      ComponentFn, PageFn, BehaviorFn, getApp, getCurrentPages, wx);
  } finally { curDef = prev; }
  return module.exports;
}
MP.require = req; MP.defs = defs;
var wxsCache = {};
function reqWxs(key) {
  if (wxsCache[key]) return wxsCache[key].exports;
  if (!__M[key]) { console.warn('wxs missing', key); return {}; }
  var module = { exports: {} }; wxsCache[key] = module;
  __M[key].call(module.exports, module, module.exports, function (r) { return reqWxs(resolveRel(key, r)); },
    function (p, f) { return new RegExp(p, f); }, function () { var a = [].slice.call(arguments); return a.length ? new (Function.prototype.bind.apply(RealDate, [null].concat(a)))() : new RealDate(Date.now()); });
  return module.exports;
}
function ComponentFn(def) { defs[curDef] = { kind: 'component', def: def }; }
function PageFn(def) { defs[curDef] = { kind: 'page', def: def }; }
function BehaviorFn(def) { def.__behavior = true; return def; }
function loadDef(path) { if (!defs[path]) { try { req(path + '.js'); } catch (e) { console.error('load comp', path, e); } } return defs[path] || { kind: 'component', def: {} }; }

// ---------------- styles ----------------
var BASE = [
  'wx-view,wx-scroll-view,wx-swiper,wx-swiper-item,wx-cover-view,wx-movable-area,wx-movable-view,wx-navigator,wx-form,wx-label,wx-map,wx-page-container,wx-rich-text,wx-progress,wx-picker,wx-video{display:block}',
  'wx-text{display:inline;white-space:pre-line}',
  'wx-image{display:inline-block;width:320px;height:240px;overflow:hidden;position:relative}',
  'wx-image>div{position:absolute;inset:0;background-repeat:no-repeat}',
  'wx-image>img{position:absolute;inset:0;width:100%;height:100%;opacity:0;pointer-events:none}',
  'wx-scroll-view{-webkit-overflow-scrolling:touch}',
  'wx-scroll-view.mp-noscrollbar{scrollbar-width:none}wx-scroll-view.mp-noscrollbar::-webkit-scrollbar{display:none}',
  'wx-swiper{position:relative;overflow:hidden;height:150px}',
  'wx-swiper>.mp-track{position:absolute;inset:0;display:flex;will-change:transform}',
  'wx-swiper-item{flex:none;height:100%;position:relative;overflow:hidden}',
  'wx-canvas{display:inline-block;width:300px;height:150px}',
  'wx-button{display:block;position:relative;text-align:center;border-radius:5px;padding:0 14px;line-height:2.55;font-size:18px;background:#f8f8f8;color:#000}',
  'wx-input{display:block;height:1.4rem;min-height:1.4rem;overflow:hidden}',
  'wx-input input,wx-textarea textarea{all:unset;width:100%;height:100%;font:inherit;color:inherit;text-align:inherit;box-sizing:border-box}',
  'wx-textarea{display:block;width:300px;height:150px}',
  '[hidden]{display:none!important}',
  '*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}',
  'canvas{display:block}',
  'wx-page-container{display:none!important}',
  'wx-checkbox,wx-radio{display:inline-flex;align-items:center;vertical-align:middle}wx-checkbox::before,wx-radio::before{content:"";display:inline-block;width:18px;height:18px;margin-right:6px;border:1px solid #888;border-radius:3px;box-sizing:border-box;background:#fff;color:var(--mp-ctl,#07c160);font-size:14px;line-height:16px;text-align:center}wx-radio::before{border-radius:50%}wx-checkbox.mp-on::before,wx-radio.mp-on::before{content:"✓";border-color:var(--mp-ctl,#07c160)}',
  'wx-switch{display:inline-block;width:52px;height:32px;border-radius:16px;background:#ddd;position:relative;vertical-align:middle}wx-switch::after{content:"";position:absolute;top:2px;left:2px;width:28px;height:28px;border-radius:50%;background:#fff;transition:left .2s}wx-switch.mp-on{background:var(--mp-ctl,#07c160)}wx-switch.mp-on::after{left:22px}',
  'wx-label{display:inline}wx-checkbox-group,wx-radio-group{display:block}wx-picker{display:block}',
].join('\n');
var sheetCache = {};
function makeSheet(key, css) {
  if (sheetCache[key]) return sheetCache[key];
  var s = new CSSStyleSheet();
  try { s.replaceSync(css); } catch (e) { console.warn('css', key, e); }
  sheetCache[key] = s; return s;
}
function convCss(css, isPage) {
  css = rpxStr(css);
  css = css.replace(/url\(\s*(['"]?)(\/[^'")]+)\1\s*\)/g, function (_, q, p) { return 'url("' + assetUrl(p) + '")'; });
  // tag selectors -> wx-* custom elements; page -> :host
  css = css.replace(/([^{}]+)\{/g, function (m, sel) {
    if (/^\s*@/.test(sel) || /^\s*(from|to|\d+%)/.test(sel.trim().split(',')[0])) return m;
    var out = sel.split(',').map(function (s) {
      return s.replace(/(^|[\s>+~(])(page)(?=$|[\s.:#\[>+~,)])/g, isPage === 'comp' ? '$1:host(.__never-page)' : '$1:host')
        .replace(/(^|[\s>+~(])(view|text|image|scroll-view|swiper-item|swiper|button|input|textarea|canvas|cover-view|cover-image|navigator|label|icon|progress|rich-text|map|movable-view|movable-area|picker|switch|checkbox|radio|video)(?=$|[\s.:#\[>+~,)])/g, '$1wx-$2');
    }).join(',');
    return out + '{';
  });
  return css;
}
var baseSheet = null, appSheet = null, appSheetComp = null;
function sheetsFor(path, def) {
  baseSheet = baseSheet || makeSheet('__base', BASE);
  appSheet = appSheet || makeSheet('__app', convCss(__APPCSS));
  var list = [baseSheet];
  var opts = (def && def.options) || {}; var conf = (__C[path] && __C[path].json) || {};
  var iso = opts.styleIsolation || conf.styleIsolation || (opts.addGlobalClass ? 'apply-shared' : '');
  var isPage = defs[path] && defs[path].kind === 'page';
  // 真机上 app.wxss 的 page{} 只作用于页面根,不会落到组件宿主上
  if (isPage) list.push(appSheet);
  else if (iso === 'apply-shared' || iso === 'shared' || conf.component !== true) list.push(appSheetComp = appSheetComp || makeSheet('__appc', convCss(__APPCSS, 'comp')));
  if (iso === 'shared' && MP.curPage) list.push(makeSheet(MP.curPage.__path, convCss(__CSS[MP.curPage.__path] || '')));
  if (__CSS[path]) list.push(makeSheet(path, convCss(__CSS[path])));
  return list;
}

// ---------------- template rendering ----------------
var fallback = new Proxy({}, {
  has: function (t, k) { return typeof k === 'string' && k.slice(0, 2) !== '__'; },
  get: function (t, k) {
    if (k === Symbol.unscopables) return undefined;
    if (k === 'Math' || k === 'Number' || k === 'String' || k === 'JSON' || k === 'parseInt' || k === 'parseFloat' || k === 'Array' || k === 'Object' || k === 'isNaN' || k === 'encodeURIComponent' || k === 'Boolean' || k === 'Date') return window[k];
    if (k === 'undefined') return undefined;
    return undefined;
  },
});
var evalErrors = {};
function ev(spec, scope) {
  if (spec === true || spec === null || typeof spec === 'string') return spec;
  try { return __F[spec.f](scope); } catch (e) { if (!evalErrors[spec.f]) { evalErrors[spec.f] = 1; MP.log.push('expr ' + spec.f + ': ' + e.message); } return spec.s ? '' : undefined; }
}
function childScope(parent, vars) { var s = Object.create(parent); for (var k in vars) s[k] = vars[k]; return s; }
var fileInfo = {};
function infoOf(file) {
  if (fileInfo[file]) return fileInfo[file];
  var nodes = __T[file] || [];
  var info = { file: file, wxs: {}, tpl: {} };
  fileInfo[file] = info;
  nodes.forEach(function (n) {
    if (n.g === 'wxs') info.wxs[n.m] = n.src ? reqWxs(n.src) : reqWxs(n.inlineId);
    if (n.g === 'template' && n.name) info.tpl[n.name] = { nodes: n.c, file: file };
    if (n.g === 'import') { var sub = infoOf(n.src); for (var t in sub.tpl) info.tpl[t] = sub.tpl[t]; }
  });
  // nested wxs (inside blocks) too
  (function walk(list) { list.forEach(function (n) { if (n.g === 'wxs' && !info.wxs[n.m]) info.wxs[n.m] = n.src ? reqWxs(n.src) : reqWxs(n.inlineId); if (n.g === 'template' && n.name && !info.tpl[n.name]) info.tpl[n.name] = { nodes: n.c, file: file }; /* CLUB-WALK HARNESS PATCH: nested <template name> */ if (n.c && n.g !== 'template') walk(n.c); }); })(nodes);
  return info;
}
function rootScope(data, info, extraWxs) {
  var s = Object.create(fallback);
  for (var m in info.wxs) s[m] = info.wxs[m];
  if (extraWxs) for (var m2 in extraWxs) if (!(m2 in s)) s[m2] = extraWxs[m2];
  var d = Object.create(s);
  for (var k in data) d[k] = data[k];
  return d;
}
// produce vnodes: {k, tag, props, kids, text}
function renderNodes(nodes, scope, ctx, kp, out) {
  var chainOpen = false, chainDone = false;
  for (var i = 0; i < nodes.length; i++) {
    var n = nodes[i];
    if (n.x !== undefined) { var t = ev(n.x, scope); out.push({ k: kp + n.i, text: t == null ? '' : String(t) }); chainOpen = false; continue; }
    if (n.g === 'wxs' || n.g === 'import' || (n.g === 'template' && n.name)) continue;
    if (n.for !== undefined) { chainOpen = false; renderFor(n, scope, ctx, kp, out); continue; }
    if (n.if !== undefined) { chainOpen = true; chainDone = !!ev(n.if, scope); if (!chainDone) continue; }
    else if (n.elif !== undefined) { if (!chainOpen || chainDone) continue; chainDone = !!ev(n.elif, scope); if (!chainDone) continue; }
    else if (n.else) { if (!chainOpen || chainDone) { chainOpen = false; continue; } chainOpen = false; }
    else chainOpen = false;
    if (n.for !== undefined) { renderFor(n, scope, ctx, kp, out); continue; }
    renderOne(n, scope, ctx, kp, out);
  }
  return out;
}
function renderFor(n, scope, ctx, kp, out) {
  var list = ev(n.for, scope);
  if (typeof list === 'number') { var arr = []; for (var j = 0; j < list; j++) arr.push(j); list = arr; }
  else if (typeof list === 'string') list = list.split('');
  else if (list && !Array.isArray(list) && typeof list === 'object') { var o = list; list = Object.keys(o).map(function (k) { return o[k]; }); }
  if (!Array.isArray(list)) return;
  var item = n.fi || 'item', idx = n.fx || 'index'; var seen = {};
  for (var i = 0; i < list.length; i++) {
    var v = {}; v[item] = list[i]; v[idx] = i;
    var s = childScope(scope, v);
    var key;
    if (n.key === '*this') key = String(list[i]);
    else if (n.key && list[i] && typeof list[i] === 'object') key = String(list[i][n.key]);
    else key = 'i' + i;
    if (key === 'undefined' || key === 'null' || seen[key]) key = 'i' + i + (seen[key] ? '#' : '');
    seen[key] = 1;
    if (n.if !== undefined && !ev(n.if, s)) continue;
    var copy = Object.assign({}, n); delete copy.for; delete copy.if; delete copy.elif; delete copy.else;
    renderOne(copy, s, ctx, kp + n.i + '[' + key + ']', out);
  }
}
function renderOne(n, scope, ctx, kp, out) {
  if (n.g === 'block') { renderNodes(n.c, scope, ctx, kp + n.i + '/', out); return; }
  if (n.g === 'include') {
    var inc = __T[n.src] || [];
    infoOf(n.src); // templates in included file
    renderNodes(inc, scope, Object.assign({}, ctx, { tpl: Object.assign({}, ctx.tpl, infoOf(n.src).tpl) }), kp + n.i + '/', out);
    return;
  }
  if (n.g === 'template' && n.is !== undefined) {
    var name = ev(n.is, scope); var tpl = ctx.tpl[name];
    if (!tpl) return;
    var data = n.data ? ev(n.data, scope) : {};
    var tinfo = infoOf(tpl.file);
    renderNodes(tpl.nodes, rootScope(data || {}, tinfo), Object.assign({}, ctx, { tpl: Object.assign({}, ctx.tpl, tinfo.tpl) }), kp + n.i + '/', out);
    return;
  }
  var props = {};
  for (var k in n.p) props[k] = ev(n.p[k], scope);
  var vn = { k: kp + n.i, tag: n.g, props: props, kids: null, ctx: ctx };
  vn.kids = renderNodes(n.c || [], scope, ctx, kp + n.i + '/', []);
  out.push(vn);
}

// ---------------- DOM patching ----------------
var BUILTIN = { view: 1, text: 1, image: 1, 'scroll-view': 1, swiper: 1, 'swiper-item': 1, canvas: 1, input: 1, textarea: 1, button: 1, slot: 1, 'page-container': 1, map: 1, 'cover-view': 1, 'cover-image': 1, navigator: 1, label: 1, icon: 1, progress: 1, 'rich-text': 1, 'movable-area': 1, 'movable-view': 1, picker: 1, switch: 1, checkbox: 1, radio: 1, video: 1, form: 1, 'checkbox-group': 1, 'radio-group': 1, 'picker-view': 1, 'picker-view-column': 1, 'match-media': 1, camera: 1, 'web-view': 1, 'share-element': 1 };
function compPathFor(tag, ownerPath) {
  var c = __C[ownerPath]; var p = c && c.using && c.using[tag];
  if (!p && __C.__app_using__) p = __C.__app_using__.using[tag];
  return p || null;
}
var pendingMount = [];
function patchChildren(parent, vnodes, owner) {
  var old = parent.__kids || [];
  var byKey = {};
  for (var i = 0; i < old.length; i++) byKey[old[i].__k] = old[i];
  var next = [];
  for (var j = 0; j < vnodes.length; j++) {
    var vn = vnodes[j]; var el = byKey[vn.k];
    if (el && (vn.text !== undefined ? el.nodeType === 3 : el.__tag === vn.tag)) { delete byKey[vn.k]; updateEl(el, vn, owner); }
    else el = createEl(vn, owner);
    next.push(el);
  }
  for (var key in byKey) destroyEl(byKey[key]);
  // order
  var ref = parent.__anchorStart ? parent.__anchorStart.nextSibling : parent.firstChild;
  for (var m = 0; m < next.length; m++) {
    var node = next[m];
    if (node !== ref) parent.insertBefore(node, ref || null);
    else ref = ref.nextSibling;
    ref = node.nextSibling;
  }
  parent.__kids = next;
}
function createEl(vn, owner) {
  if (vn.text !== undefined) { var tn = document.createTextNode(vn.text); tn.__k = vn.k; return tn; }
  var tag = vn.tag; var el;
  var cpath = BUILTIN[tag] ? null : compPathFor(tag, owner.__path);
  if (cpath) {
    el = document.createElement(/-/.test(tag) ? tag : 'mp-' + tag);
    el.__tag = tag; el.__k = vn.k; el.__owner = owner;
    var inst = createComponent(cpath, el, owner);
    applyProps(el, vn, owner, true);
    inst.__setProps(compProps(vn.props), true);
    patchChildren(el, vn.kids, owner); // slot content (light DOM)
    inst.__render();
    pendingMount.push(inst);
    return el;
  }
  if (tag === 'slot') { el = document.createElement('slot'); }
  else if (tag === 'canvas') { el = document.createElement('canvas'); el.__isCanvas = true; }
  else if (tag === 'input') { el = document.createElement('wx-input'); var ip = document.createElement('input'); el.appendChild(ip); el.__inp = ip; el.__anchorStart = ip; bindInput(el, ip); }
  else if (tag === 'textarea') { el = document.createElement('wx-textarea'); var ta = document.createElement('textarea'); el.appendChild(ta); el.__inp = ta; el.__anchorStart = ta; bindInput(el, ta); }
  else if (tag === 'image' || tag === 'cover-image') { el = document.createElement('wx-image'); var bg = document.createElement('div'); el.appendChild(bg); el.__bg = bg; el.__anchorStart = bg; }
  else if (tag === 'swiper') { el = document.createElement('wx-swiper'); var tr = document.createElement('div'); tr.className = 'mp-track'; el.appendChild(tr); el.__track = tr; }
  else el = document.createElement('wx-' + tag);
  el.__tag = tag; el.__k = vn.k; el.__owner = owner;
  applyProps(el, vn, owner, true);
  if (tag === 'swiper') patchChildren(el.__track, vn.kids, owner);
  else if (tag !== 'rich-text') patchChildren(el, vn.kids, owner);
  afterProps(el, vn, owner, true);
  if (/^(checkbox|radio|switch|label|picker)$/.test(tag)) { installCtl(el); ctlUpdate(el); }
  return el;
}
function updateEl(el, vn, owner) {
  if (vn.text !== undefined) { if (el.data !== vn.text) el.data = vn.text; return; }
  applyProps(el, vn, owner, false);
  if (el.__inst) {
    el.__inst.__setProps(compProps(vn.props), false);
    patchChildren(el, vn.kids, owner);
    return;
  }
  if (el.__tag === 'swiper') patchChildren(el.__track, vn.kids, owner);
  else if (el.__tag !== 'rich-text') patchChildren(el, vn.kids, owner);
  afterProps(el, vn, owner, false);
  if (el.__ctl) ctlUpdate(el);
}
function destroyEl(el) {
  if (el.nodeType === 3) { el.remove(); return; }
  detachTree(el);
  el.remove();
}
function detachTree(el) {
  if (el.__io) { el.__io.forEach(function (o) { o.disconnect(); }); }
  var kids = el.__kids || [];
  kids.forEach(function (k) { if (k.nodeType === 1) detachTree(k); });
  if (el.__track) (el.__track.__kids || []).forEach(function (k) { if (k.nodeType === 1) detachTree(k); });
  if (el.__swiperTimer) rCI(el.__swiperTimer);
  if (el.__inst) { var inst = el.__inst; (inst.__root.__kids || []).forEach(function (k) { if (k.nodeType === 1) detachTree(k); }); inst.__detached(); }
}
function isOn(v) { return v !== undefined && v !== null && v !== false && v !== 'false' && v !== ''; }
// ---- form controls: checkbox / radio (+groups, label), switch, picker
function ctlUpdate(el) {
  var p = el.__vp || {};
  if (el.__tag === 'checkbox' || el.__tag === 'radio' || el.__tag === 'switch') {
    if (el.__userSet !== true || el.__lastProp !== String(p.checked)) { el.__checked = isOn(p.checked); el.__lastProp = String(p.checked); el.__userSet = false; }
    el.classList.toggle('mp-on', !!el.__checked); el.setAttribute('aria-checked', el.__checked ? 'true' : 'false');
    if (p.color) el.style.setProperty('--mp-ctl', p.color);
  }
}
function groupOf(el, tag) { var n = el.parentNode; while (n) { if (n.__tag === tag) return n; n = n.parentNode || (n.host && null); } return null; }
function ctlFire(el) {
  if (el.__tag === 'switch') { fire(el, 'change', { value: !!el.__checked }); return; }
  var gtag = el.__tag === 'checkbox' ? 'checkbox-group' : 'radio-group';
  var g = groupOf(el, gtag); if (!g) return;
  var boxes = g.querySelectorAll('wx-' + el.__tag);
  if (el.__tag === 'radio') { for (var i = 0; i < boxes.length; i++) if (boxes[i] !== el) { boxes[i].__checked = false; boxes[i].__userSet = true; boxes[i].classList.remove('mp-on'); } }
  var vals = []; for (var j = 0; j < boxes.length; j++) if (boxes[j].__checked) vals.push(String((boxes[j].__vp || {}).value == null ? '' : boxes[j].__vp.value));
  fire(g, 'change', { value: el.__tag === 'radio' ? (vals[0] || '') : vals });
}
function ctlToggle(el) {
  if (isOn((el.__vp || {}).disabled)) return;
  if (el.__tag === 'radio' && el.__checked) return;
  el.__checked = !el.__checked; el.__userSet = true; el.classList.toggle('mp-on', el.__checked);
  ctlFire(el);
}
function installCtl(el) {
  if (el.__ctl) return; el.__ctl = true;
  if (el.__tag === 'checkbox' || el.__tag === 'radio' || el.__tag === 'switch') el.addEventListener('click', function (e) { e.__ctlDone = true; ctlToggle(el); });
  if (el.__tag === 'label') el.addEventListener('click', function (e) { if (e.__ctlDone) return; var c = el.querySelector('wx-checkbox,wx-radio,wx-switch'); if (c) { e.__ctlDone = true; ctlToggle(c); } });
  if (el.__tag === 'picker') el.addEventListener('click', function () { MP.pickerSeam ? MP.pickerSeam(el) : null; });
}
MP.ctlToggle = ctlToggle;
var EVT_RE = /^(capture-bind|capture-catch|mut-bind|bind|catch):?(.+)$/;
function compProps(props) {
  var o = {};
  for (var k in props) {
    if (EVT_RE.test(k) || k === 'class' || k === 'style' || k === 'id' || k === 'slot' || /^data-/.test(k) || /^aria-/.test(k) || k === 'hidden' || /^(hover-|generic:|mark:)/.test(k)) continue;
    o[camel(k)] = props[k];
  }
  return o;
}
function applyProps(el, vn, owner, isNew) {
  var p = vn.props; var ds = {}; var on = {};
  for (var k in p) {
    var v = p[k];
    var m = EVT_RE.exec(k);
    if (m && !/^(bindingx)/.test(k)) {
      var ev2 = m[2]; if (ev2[0] === ':') ev2 = ev2.slice(1);
      on[ev2] = { h: v, c: m[1].indexOf('catch') >= 0, cap: m[1].indexOf('capture') === 0 }; continue;
    }
    if (k.indexOf('data-') === 0) { ds[camel(k.slice(5))] = v; continue; }
  }
  el.__ds = ds; el.__on = on; el.__owner = owner; el.__vp = p;
  installEvents(el);
  var cls = p['class'] == null ? '' : String(p['class']);
  if (el.__hoverOn && el.__hoverCls) cls += ' ' + el.__hoverCls;
  if (el.getAttribute('class') !== cls) { if (cls) el.setAttribute('class', cls); else el.removeAttribute('class'); }
  var st = p.style == null ? '' : rpxStr(String(p.style));
  if (el.__tag === 'image' && el.__fixH) st += ';' + el.__fixH;
  if (el.__st !== st) { el.style.cssText = st; el.__st = st; }
  var id = p.id == null ? '' : String(p.id);
  if (el.id !== id) el.id = id;
  var hid = p.hidden !== undefined && p.hidden !== false && p.hidden !== 'false' && p.hidden !== null;
  if (el.hidden !== hid) el.hidden = hid;
  if (p.slot != null) el.setAttribute('slot', p.slot);
  if (p['aria-label'] != null) el.setAttribute('aria-label', p['aria-label']);
  if (p['aria-role'] != null) el.setAttribute('role', p['aria-role']);
  el.__hoverCls = p['hover-class'] && p['hover-class'] !== 'none' ? String(p['hover-class']) : '';
  el.__hoverStay = Number(p['hover-stay-time'] || 400);
  el.__hoverStop = !!p['hover-stop-propagation'];
  if (el.__tag === 'button' && p['open-type'] && !el.__openTypeInstalled) {
    el.__openTypeInstalled = true;
    el.addEventListener('click', function () {
      var ot = String((el.__vp || {})['open-type'] || '');
      if (isOn((el.__vp || {}).disabled)) return;
      var seam = MP.openTypeSeam && MP.openTypeSeam(ot, el);
      if (seam) rST(function () { fire(el, seam.event, seam.detail); }, 30);
    });
  }
}
function afterProps(el, vn, owner, isNew) {
  var p = vn.props, tag = el.__tag;
  if (tag === 'image' || tag === 'cover-image') {
    var src = assetUrl(p.src, dirOf(owner.__path));
    var mode = p.mode || 'scaleToFill';
    if (el.__src !== src) {
      el.__src = src;
      el.__bg.style.backgroundImage = src ? 'url("' + src + '")' : 'none';
      if (src) {
        var im = new Image(); im.onload = function () {
          el.__nat = { w: im.naturalWidth, h: im.naturalHeight }; fixImg(el);
          fire(el, 'load', { width: im.naturalWidth, height: im.naturalHeight });
        }; im.onerror = function () { fire(el, 'error', { errMsg: 'load fail' }); }; im.src = src;
      }
    }
    if (el.__mode !== mode) { el.__mode = mode; var bs = { aspectFill: 'cover', aspectFit: 'contain', scaleToFill: '100% 100%', widthFix: '100% 100%', heightFix: '100% 100%' }[mode] || 'auto';
      el.__bg.style.backgroundSize = bs; el.__bg.style.backgroundPosition = { top: 'top', bottom: 'bottom', left: 'left', right: 'right', 'top left': 'top left', 'top right': 'top right', 'bottom left': 'bottom left', 'bottom right': 'bottom right' }[mode] || 'center'; fixImg(el); }
  } else if (tag === 'scroll-view') {
    if (isNew) {
      el.addEventListener('scroll', function () {
        var d = { scrollTop: el.scrollTop, scrollLeft: el.scrollLeft, scrollHeight: el.scrollHeight, scrollWidth: el.scrollWidth, deltaY: (el.__lastTop || 0) - el.scrollTop, deltaX: 0 };
        el.__lastTop = el.scrollTop;
        fire(el, 'scroll', d);
        if (el.scrollHeight - el.scrollTop - el.clientHeight < 50) fire(el, 'scrolltolower', {});
      }, { passive: true });
    }
    el.style.overflowY = (p['scroll-y'] && p['scroll-y'] !== 'false') ? 'auto' : 'hidden';
    el.style.overflowX = (p['scroll-x'] && p['scroll-x'] !== 'false') ? 'auto' : 'hidden';
    if (p['show-scrollbar'] === false || p['show-scrollbar'] === 'false' || p.enhanced) el.classList.add('mp-noscrollbar');
    var smooth = p['scroll-with-animation'] && p['scroll-with-animation'] !== 'false';
    if (p['scroll-top'] !== undefined && p['scroll-top'] !== '' && el.__sTop !== p['scroll-top']) {
      el.__sTop = p['scroll-top']; var top = Number(rpxStr(String(p['scroll-top'])).replace('px', '')) || 0;
      Promise.resolve().then(function () { el.scrollTo({ top: top, behavior: smooth ? 'smooth' : 'auto' }); });
    }
    var into = p['scroll-into-view'];
    if (into && el.__into !== into) {
      el.__into = into;
      rST(function () { var t = el.querySelector('#' + CSS.escape(into)); if (t) { var r = t.getBoundingClientRect(), r0 = el.getBoundingClientRect(); el.scrollTo({ top: el.scrollTop + (r.top - r0.top) / MP.scale, behavior: smooth ? 'smooth' : 'auto' }); } }, 30);
    } else if (!into) el.__into = '';
  } else if (tag === 'input' || tag === 'textarea') {
    var inp = el.__inp;
    if (p.value != null && document.activeElement !== inp && inp.value !== String(p.value)) inp.value = String(p.value);
    if (p.placeholder != null) inp.placeholder = p.placeholder;
    if (p.maxlength != null) inp.maxLength = Number(p.maxlength) > 0 ? Number(p.maxlength) : 524288;
    if (p.type === 'number' || p.type === 'digit') inp.inputMode = 'decimal';
    if (p.password) inp.type = 'password';
    if (p.disabled) inp.disabled = true; else inp.disabled = false;
  } else if (tag === 'swiper') {
    setupSwiper(el, p);
  } else if (tag === 'rich-text') {
    var nodes = p.nodes; if (typeof nodes === 'string' && el.__html !== nodes) { el.__html = nodes; el.innerHTML = nodes; }
  } else if (tag === 'map') {
    if (MP.drawMap) MP.drawMap(el, p, isNew);
  } else if (tag === 'canvas') {
    if (p['canvas-id']) el.setAttribute('data-canvas-id', p['canvas-id']);
  } else if (tag === 'slot') {
    if (p.name) el.name = p.name;
  }
}
function fixImg(el) {
  if (!el.__nat) return;
  var fix = '';
  if (el.__mode === 'widthFix') { var w = el.getBoundingClientRect().width / (MP.scale || 1); if (w) fix = 'height:' + (w * el.__nat.h / el.__nat.w).toFixed(1) + 'px'; else rST(function () { fixImg(el); }, 50); }
  if (el.__mode === 'heightFix') { var h = el.getBoundingClientRect().height / (MP.scale || 1); if (h) fix = 'width:' + (h * el.__nat.w / el.__nat.h).toFixed(1) + 'px'; }
  if (fix !== (el.__fixH || '')) { el.__fixH = fix; el.style.cssText = (el.__st || '') + ';' + fix; el.__st = el.style.cssText === '' ? '' : el.__st; el.__st = null; }
}
function setupSwiper(el, p) {
  var items = el.__track.__kids || [];
  var multi = Number(p['display-multiple-items'] || 1);
  var n = items.length; if (!n) return;
  var cur = el.__cur || Number(p.current || 0);
  el.__cur = cur;
  var dur = Number(p.duration || 500), interval = Number(p.interval || 5000);
  var nextMargin = rpxStr(String(p['next-margin'] || '0px')), prevMargin = rpxStr(String(p['previous-margin'] || '0px'));
  var W = 'calc((100% - ' + nextMargin + ' - ' + prevMargin + ') / ' + multi + ')';
  items.forEach(function (it) { if (it.nodeType === 1) it.style.width = W; });
  el.__track.style.left = prevMargin; el.__track.style.right = nextMargin;
  var ease = p['easing-function'] === 'linear' ? 'linear' : 'ease';
  function go(i, anim) {
    el.__cur = i;
    el.__track.style.transition = anim ? 'transform ' + dur / MP.speed + 'ms ' + ease : 'none';
    el.__track.style.transform = 'translateX(calc(' + (-i) + ' * ' + W + '))';
  }
  go(cur, false);
  var auto = p.autoplay && p.autoplay !== 'false';
  if (auto && !el.__swiperTimer) {
    el.__swiperTimer = rSI(function () {
      var its = el.__track.__kids || []; var max = its.length - multi;
      var nx = el.__cur + 1;
      if (nx > max) { go(0, false); return; }
      go(nx, true); fire(el, 'change', { current: nx, source: 'autoplay' });
    }, interval / MP.speed);
  } else if (!auto && el.__swiperTimer) { rCI(el.__swiperTimer); el.__swiperTimer = null; }
}

// ---------------- events ----------------
function phoneXY(e) { var r = MP.screenRect(); return { x: (e.clientX - r.left) / MP.scale, y: (e.clientY - r.top) / MP.scale }; }
function touchList(e) { var p = phoneXY(e); return [{ identifier: e.pointerId || 0, clientX: p.x, clientY: p.y, pageX: p.x, pageY: p.y, x: p.x, y: p.y, force: 1 }]; }
function mkEvent(el, type, detail, native, target) {
  var t = target || el;
  var ev3 = {
    type: type, timeStamp: Date.now(), detail: detail || {},
    target: { id: t.id || '', dataset: t.__ds || {}, offsetLeft: 0, offsetTop: 0 },
    currentTarget: { id: el.id || '', dataset: el.__ds || {}, offsetLeft: 0, offsetTop: 0 },
    mark: {}, touches: [], changedTouches: [],
    stopPropagation: function () { native && native.stopPropagation(); }, preventDefault: function () {},
  };
  if (native && native.clientX !== undefined) { ev3.touches = touchList(native); ev3.changedTouches = ev3.touches; if (!detail) ev3.detail = { x: ev3.touches[0].x, y: ev3.touches[0].y }; }
  return ev3;
}
function callHandler(el, b, evt) {
  var owner = el.__owner; var h = b.h;
  if (typeof h === 'function') return h.call(owner, evt);
  if (!h || typeof h !== 'string') return;
  var fn = owner && owner[h];
  if (typeof fn !== 'function') { MP.log.push('no handler ' + h); return; }
  try { return fn.call(owner, evt); } catch (err) { console.error('handler ' + h, err); MP.log.push('handler ' + h + ': ' + err.message); }
}
function fire(el, type, detail) { var b = el.__on && el.__on[type]; if (b) callHandler(el, b, mkEvent(el, type, detail)); }
MP.fire = fire;
var NATIVE = { tap: 'click', touchstart: 'pointerdown', touchmove: 'pointermove', touchend: 'pointerup', touchcancel: 'pointercancel', longpress: 'pointerdown', longtap: 'pointerdown', animationend: 'animationend', transitionend: 'transitionend', animationstart: 'animationstart', animationiteration: 'animationiteration' };
function installEvents(el) {
  if (!el.__evInstalled) {
    el.__evInstalled = {};
    el.addEventListener('pointerdown', function (e) {
      if (el.__hoverCls && !e.__hoverDone) { if (el.__hoverStop) e.__hoverDone = true; el.__hoverOn = true; el.classList.add.apply(el.classList, el.__hoverCls.split(/\s+/).filter(Boolean)); }
    });
    var off = function () { if (el.__hoverOn) { rST(function () { el.__hoverOn = false; if (el.__hoverCls) el.classList.remove.apply(el.classList, el.__hoverCls.split(/\s+/).filter(Boolean)); }, el.__hoverStay / MP.speed); } };
    el.addEventListener('pointerup', off); el.addEventListener('pointercancel', off); el.addEventListener('pointerleave', off);
  }
  var on = el.__on;
  for (var type in on) {
    var nat = NATIVE[type]; if (!nat) continue;
    var key = type;
    if (el.__evInstalled[key]) continue;
    el.__evInstalled[key] = true;
    (function (type, nat) {
      var listener = function (e) {
        var b = el.__on[type]; if (!b) return;
        if (type === 'longpress' || type === 'longtap') {
          var fired = false; var tm = rST(function () { fired = true; el.__suppressClick = true; callHandler(el, b, mkEvent(el, type, null, e)); }, 350);
          var cancel = function () { rCT(tm); el.removeEventListener('pointerup', cancel); el.removeEventListener('pointercancel', cancel); };
          el.addEventListener('pointerup', cancel); el.addEventListener('pointercancel', cancel);
          return;
        }
        if (type === 'tap' && el.__suppressClick) { el.__suppressClick = false; return; }
        if (type === 'tap' && el.__tag === 'button' && isOn((el.__vp || {}).disabled)) return;
        if (type === 'touchstart' && (el.__on.touchmove || el.__on.touchend)) { try { el.setPointerCapture(e.pointerId); } catch (x) {} }
        if (type === 'touchmove' && !(e.buttons || e.pointerType === 'touch')) return;
        if (b.c) e.stopPropagation();
        callHandler(el, b, mkEvent(el, type, null, e, e.target && e.target.__ds ? e.target : el));
      };
      el.addEventListener(nat, listener, { capture: !!on[type].cap });
    })(type, nat);
  }
}
function bindInput(el, inp) {
  inp.addEventListener('input', function () { var b = el.__on && el.__on.input; if (b) { var r = callHandler(el, b, mkEvent(el, 'input', { value: inp.value, cursor: inp.selectionStart, keyCode: 0 })); if (typeof r === 'string' && r !== inp.value) inp.value = r; } });
  inp.addEventListener('focus', function () { fire(el, 'focus', { value: inp.value, height: 0 }); });
  inp.addEventListener('blur', function () { fire(el, 'blur', { value: inp.value }); });
  inp.addEventListener('keydown', function (e) { if (e.key === 'Enter' && el.__tag === 'input') fire(el, 'confirm', { value: inp.value }); });
}

// ---------------- instances ----------------
var instSeq = 0;
function mergeBehaviors(def) {
  var acc = { properties: {}, data: {}, methods: {}, lifetimes: {}, pageLifetimes: {}, observers: [], life: { created: [], attached: [], ready: [], detached: [], moved: [] }, pl: { show: [], hide: [], resize: [] }, behaviors: [] };
  function add(d) {
    if (!d || typeof d === 'string') return;
    (d.behaviors || []).forEach(add);
    Object.assign(acc.properties, d.properties || {});
    Object.assign(acc.data, clone(d.data || {}));
    Object.assign(acc.methods, d.methods || {});
    var lt = d.lifetimes || {};
    ['created', 'attached', 'ready', 'detached', 'moved'].forEach(function (k) { var f = lt[k] || d[k]; if (typeof f === 'function') acc.life[k].push(f); });
    var pl = d.pageLifetimes || {};
    ['show', 'hide', 'resize'].forEach(function (k) { if (typeof pl[k] === 'function') acc.pl[k].push(pl[k]); });
    if (d.observers) for (var ok in d.observers) acc.observers.push({ keys: ok.split(',').map(function (s) { return s.trim(); }), fn: d.observers[ok] });
    acc.behaviors.push(d);
  }
  add(def);
  return acc;
}
function propDefault(spec) {
  if (spec === null) return null;
  if (typeof spec === 'function') spec = { type: spec };
  if ('value' in spec) return clone(spec.value);
  var t = spec.type;
  return t === Boolean ? false : t === Number ? 0 : t === String ? '' : t === Array ? [] : t === Object ? null : null;
}
function coerce(spec, v) {
  if (typeof spec === 'function') spec = { type: spec };
  var t = spec && spec.type;
  if (v === undefined) return propDefault(spec || {});
  if (t === Boolean) return v === 'false' ? true && !!v : !!v;
  if (t === Number) { var n = Number(v); return isNaN(n) ? propDefault(spec) : n; }
  if (t === String) return v == null ? '' : (typeof v === 'object' ? JSON.stringify(v) : String(v));
  return v;
}
function Instance() {}
Instance.prototype.setData = function (patch, cb) {
  var changed = [];
  for (var k in patch) { if (MP.watchKey && k === MP.watchKey) MP.log.push('SET ' + k + ' ' + JSON.stringify(Object.keys(patch[k] || {})) + ' @ ' + (new Error().stack.split('\n').slice(2, 6).join(' | '))); setPath(this.data, k, patch[k]); changed.push(k); }
  this.__runObservers(changed);
  if (cb) this.__cbs.push(cb);
  this.__schedule();
};
Instance.prototype.__runObservers = function (changed) {
  var self = this; if (!this.__m || !this.__m.observers.length) return;
  this.__m.observers.forEach(function (o) {
    var hit = o.keys.some(function (key) {
      if (key === '**') return true;
      var base = key.replace(/\.\*\*$/, '');
      return changed.some(function (c) { var cs = String(c).replace(/\[(\d+)\]/g, '.$1'); return cs === base || cs.indexOf(base + '.') === 0 || base.indexOf(cs + '.') === 0; });
    });
    if (hit) { try { o.fn.apply(self, o.keys.map(function (key) { return getPath(self.data, key.replace(/\.\*\*$/, '')); })); } catch (e) { console.error('observer', self.is, e); } }
  });
};
Instance.prototype.__schedule = function () {
  if (this.__dirty || this.__dead) return;
  this.__dirty = true; var self = this;
  Promise.resolve().then(function () { if (!self.__dirty) return; self.__render(); });
};
Instance.prototype.__render = function () {
  this.__dirty = false;
  if (this.__dead) return;
  var info = infoOf(this.__path + '.wxml');
  var ctx = { tpl: info.tpl };
  var vnodes;
  try { vnodes = renderNodes(__T[this.__path + '.wxml'] || [], rootScope(this.data, info, this.__extraWxs), ctx, '', []); }
  catch (e) { console.error('render', this.__path, e); MP.log.push('render ' + this.__path + ': ' + e.message); return; }
  patchChildren(this.__root, vnodes, this);
  flushMount();
  var cbs = this.__cbs; this.__cbs = [];
  var self = this;
  cbs.forEach(function (cb) { try { cb.call(self); } catch (e) { console.error('setData cb', e); } });
};
Instance.prototype.triggerEvent = function (name, detail) {
  var host = this.__host; var b = host && host.__on && host.__on[name];
  if (!b) return;
  callHandler(host, b, mkEvent(host, name, detail));
};
function findComps(root, sel, all) {
  var res = [];
  var nodes;
  try { nodes = root.querySelectorAll(sel); } catch (e) { return all ? [] : null; }
  for (var i = 0; i < nodes.length; i++) if (nodes[i].__inst) { res.push(nodes[i].__inst); if (!all) break; }
  return all ? res : (res[0] || null);
}
Instance.prototype.selectComponent = function (sel) { return findComps(this.__root, sel, false); };
Instance.prototype.selectAllComponents = function (sel) { return findComps(this.__root, sel, true); };
Instance.prototype.createSelectorQuery = function () { return new SelectorQuery(this.__root); };
Instance.prototype.createIntersectionObserver = function (opts) { return new IObserver(this, opts); };
Instance.prototype.getRelationNodes = function () { return []; };
Instance.prototype.hasBehavior = function () { return false; };
Instance.prototype.groupSetData = function (fn) { fn && fn.call(this); };
Instance.prototype.getPageId = function () { return 'page-1'; };
Instance.prototype.selectOwnerComponent = function () { return this.__ownerInst || null; };
Instance.prototype.setUpdatePerformanceListener = function () {};
Instance.prototype.animate = function (sel, frames, dur, cb) { var el = this.__root.querySelector(sel); if (el && el.animate) { var a = el.animate(frames.map(function (f) { var o = Object.assign({}, f); delete o.offset; delete o.ease; return o; }), { duration: dur, fill: 'forwards' }); a.onfinish = function () { cb && cb(); }; } else cb && cb(); };
Instance.prototype.clearAnimation = function (sel, opt, cb) { cb && cb(); };
Instance.prototype.__setProps = function (props, initial) {
  var spec = this.__m.properties; var changed = [];
  for (var k in spec) {
    if (!(k in props) && !initial) { if (this.__given && this.__given[k]) { /* removed attr */ } else continue; }
    if (!(k in props) && initial) continue;
    var nv = coerce(spec[k], props[k]); if (nv && typeof nv === 'object') nv = clone(nv); var ov = this.data[k];
    if (initial ? true : !same(nv, ov)) {
      this.data[k] = nv; changed.push(k);
      var s = spec[k]; var obs = s && typeof s === 'object' && s.observer;
      if (obs && !initial) { var f = typeof obs === 'function' ? obs : this[obs]; if (f) { try { f.call(this, nv, ov, [k]); } catch (e) { console.error('prop observer', e); } } }
      else if (obs && initial && !same(nv, propDefault(s))) { var f2 = typeof obs === 'function' ? obs : this[obs]; if (f2) { var self = this; (function (f2, nv, ov, k) { Promise.resolve().then(function () { try { f2.call(self, nv, ov, [k]); } catch (e) { console.error('prop observer', e); } }); })(f2, nv, propDefault(s), k); } }
    }
  }
  this.__given = props;
  if (changed.length) {
    if (initial) { var self2 = this; var ch = changed; this.__initObs = ch; }
    else { this.__runObservers(changed); this.__render(); }
  }
};
Instance.prototype.__detached = function () {
  if (this.__dead) return; this.__dead = true;
  var self = this; this.__m.life.detached.forEach(function (f) { try { f.call(self); } catch (e) { console.error('detached', self.is, e); } });
};
function createComponent(path, host, ownerInst) {
  var d = loadDef(path).def;
  var m = mergeBehaviors(d);
  var inst = new Instance();
  inst.__m = m; inst.__path = path; inst.__host = host; inst.__ownerInst = ownerInst; inst.__cbs = [];
  inst.is = path; inst.__id = ++instSeq;
  Object.defineProperty(inst, 'id', { get: function () { return host.id; } });
  Object.defineProperty(inst, 'dataset', { get: function () { return host.__ds || {}; } });
  var data = clone(m.data) || {};
  for (var pk in m.properties) if (!(pk in data)) data[pk] = propDefault(m.properties[pk]);
  inst.data = data; inst.properties = data;
  for (var mk in m.methods) inst[mk] = m.methods[mk];
  host.__inst = inst;
  var root = host.attachShadow({ mode: 'open' });
  root.adoptedStyleSheets = sheetsFor(path, d);
  inst.__root = root; root.__host = host;
  m.life.created.forEach(function (f) { try { f.call(inst); } catch (e) { console.error('created', path, e); } });
  return inst;
}
function flushMount() {
  if (!pendingMount.length) return;
  var list = pendingMount; pendingMount = [];
  list.forEach(function (inst) {
    if (inst.__dead) return;
    if (inst.__initObs) { var io = inst.__initObs; inst.__initObs = null; inst.__runObservers(io); }
    inst.__m.life.attached.forEach(function (f) { try { f.call(inst); } catch (e) { console.error('attached', inst.is, e); } });
    inst.__m.pl.show.forEach(function (f) { try { f.call(inst); } catch (e) {} });
  });
  rST(function () {
    list.forEach(function (inst) { if (inst.__dead) return; inst.__m.life.ready.forEach(function (f) { try { f.call(inst); } catch (e) { console.error('ready', inst.is, e); } }); });
  }, 0);
}

// ---------------- pages ----------------
var pageStack = [];
function getCurrentPages() { return pageStack.slice(); }
getCurrentPages.__stack = function () { return pageStack; };
MP.openPage = function (path, query, mountEl) {
  var rec = loadDef(path); var d = rec.def;
  var page = new Instance();
  page.__path = path; page.__cbs = []; page.is = path; page.route = path; page.options = query || {};
  if (rec.kind === 'component') {
    var m = mergeBehaviors(d); page.__m = m;
    page.data = clone(m.data) || {};
    for (var pk in m.properties) if (!(pk in page.data)) page.data[pk] = propDefault(m.properties[pk]);
    // Component-as-page: query keys feed same-named properties
    for (var qk in (query || {})) if (qk in m.properties) page.data[qk] = coerce(m.properties[qk], query[qk]);
    page.properties = page.data;
    for (var mk in m.methods) page[mk] = m.methods[mk];
    page.__compPage = true;
  } else {
    page.__m = { properties: {}, observers: [], life: { detached: [] }, pl: { show: [] } };
    page.data = clone(d.data || {});
    for (var k in d) if (k !== 'data') page[k] = d[k];
    (d.behaviors || []).forEach(function (b) { if (b && b.methods) for (var bm in b.methods) if (!(bm in page)) page[bm] = b.methods[bm]; if (b && b.data) for (var bd in b.data) if (!(bd in page.data)) page.data[bd] = clone(b.data[bd]); });
  }
  var host = document.createElement('mp-page');
  mountEl.appendChild(host);
  var root = host.attachShadow({ mode: 'open' });
  MP.curPage = page;
  root.adoptedStyleSheets = sheetsFor(path, d);
  page.__root = root; page.__host = host;
  page.selectComponent = Instance.prototype.selectComponent;
  page.__channel = MP.__nextChannel || makeChannel(); MP.__nextChannel = null;
  page.getOpenerEventChannel = function () { return page.__channel; };
  page.getTabBar = function () { return null; };
  page.selectAllComponents = Instance.prototype.selectAllComponents;
  page.createSelectorQuery = Instance.prototype.createSelectorQuery;
  page.createIntersectionObserver = Instance.prototype.createIntersectionObserver;
  host.addEventListener('scroll', function () {
    var st = host.scrollTop;
    try { page.onPageScroll && page.onPageScroll({ scrollTop: st }); } catch (e) { console.error('onPageScroll', e); }
    if (host.scrollHeight - st - host.clientHeight < 50 && !host.__atBottom) { host.__atBottom = true; try { page.onReachBottom && page.onReachBottom(); } catch (e) { console.error('onReachBottom', e); } }
    else if (host.scrollHeight - st - host.clientHeight >= 50) host.__atBottom = false;
  }, { passive: true });
  pageStack.push(page);
  if (page.__compPage) {
    page.__m.life.created.forEach(function (f) { try { f.call(page); } catch (e) { console.error('created', path, e); } });
    page.__m.life.attached.forEach(function (f) { try { f.call(page); } catch (e) { console.error('attached', path, e); } });
  }
  try { page.onLoad && page.onLoad(query || {}); } catch (e) { console.error('onLoad', path, e); MP.log.push('onLoad ' + path + ' ' + e.message); }
  page.__render();
  try { page.onShow && page.onShow(); } catch (e) { console.error('onShow', path, e); }
  if (page.__compPage) page.__m.pl.show.forEach(function (f) { try { f.call(page); } catch (e) {} });
  rST(function () { try { page.onReady && page.onReady(); } catch (e) { console.error('onReady', path, e); } if (page.__compPage) page.__m.life.ready.forEach(function (f) { try { f.call(page); } catch (e) {} }); }, 0);
  return page;
};
function makeChannel() {
  var L = {}; var peer = null;
  var ch = { on: function (n, f) { (L[n] = L[n] || []).push(f); }, once: function (n, f) { var g = function (x) { ch.off(n, g); f(x); }; ch.on(n, g); },
    off: function (n, f) { L[n] = f ? (L[n] || []).filter(function (x) { return x !== f; }) : []; },
    emit: function (n, x) { var t = peer || ch; (t.__L[n] || []).slice().forEach(function (f) { try { f(x); } catch (e) { console.error('eventChannel', e); } }); }, __L: L };
  ch.__pair = function (other) { peer = other; };
  return ch;
}
MP.makeChannel = makeChannel;
MP.closePage = function (page) {
  try { page.onUnload && page.onUnload(); } catch (e) {}
  if (page.__compPage) page.__m.life.detached.forEach(function (f) { try { f.call(page); } catch (e) {} });
  (page.__root.__kids || []).forEach(function (k) { if (k.nodeType === 1) detachTree(k); });
  page.__dead = true;
  page.__host.remove();
  var i = pageStack.indexOf(page); if (i >= 0) pageStack.splice(i, 1);
};

// ---------------- selector query ----------------
function SelectorQuery(root) { this.root = root; this.q = []; }
SelectorQuery.prototype.in = function (comp) { if (comp && comp.__root) this.root = comp.__root; return this; };
function NodesRef(sq, sel, all) { this.sq = sq; this.sel = sel; this.all = all; }
NodesRef.prototype.boundingClientRect = function (cb) { this.sq.q.push({ ref: this, f: { rect: true, size: true, id: true, dataset: true }, cb: cb }); return this.sq; };
NodesRef.prototype.fields = function (f, cb) { this.sq.q.push({ ref: this, f: f, cb: cb }); return this.sq; };
NodesRef.prototype.scrollOffset = function (cb) { this.sq.q.push({ ref: this, f: { scrollOffset: true, id: true, dataset: true }, cb: cb }); return this.sq; };
NodesRef.prototype.node = function (cb) { this.sq.q.push({ ref: this, f: { node: true }, cb: cb }); return this.sq; };
NodesRef.prototype.context = function (cb) { this.sq.q.push({ ref: this, f: { context: true }, cb: cb }); return this.sq; };
SelectorQuery.prototype.select = function (s) { return new NodesRef(this, s, false); };
SelectorQuery.prototype.selectAll = function (s) { return new NodesRef(this, s, true); };
SelectorQuery.prototype.selectViewport = function () { return new NodesRef(this, '__viewport', false); };
function canvasNode(el) {
  if (!el.__cvAug) {
    el.__cvAug = true;
    el.requestAnimationFrame = function (cb) { return window.requestAnimationFrame(cb); };
    el.cancelAnimationFrame = function (id) { cancelAnimationFrame(id); };
    el.createImage = function () { var im = new Image(); var _s = ''; Object.defineProperty(im, 'src', { set: function (v) { _s = v; HTMLImageElement.prototype.__lookupSetter__('src').call(im, assetUrl(v)); }, get: function () { return _s; } }); return im; };
    el.createPath2D = function (p) { return new Path2D(p); };
  }
  return el;
}
MP.canvasNode = canvasNode;
function describe(el, f) {
  if (el === '__viewport') { var o0 = {}; if (f.size || f.rect) Object.assign(o0, { width: DEV0.w, height: DEV0.h, left: 0, top: 0, right: DEV0.w, bottom: DEV0.h }); if (f.scrollOffset) Object.assign(o0, { scrollTop: 0, scrollLeft: 0 }); return o0; }
  var o = {}; var r = el.getBoundingClientRect(); var p = MP.screenRect(); var s = MP.scale;
  if (f.id) o.id = el.id; if (f.dataset) o.dataset = el.__ds || {};
  if (f.rect) Object.assign(o, { left: (r.left - p.left) / s, top: (r.top - p.top) / s, right: (r.right - p.left) / s, bottom: (r.bottom - p.top) / s });
  if (f.size) Object.assign(o, { width: r.width / s, height: r.height / s });
  if (f.scrollOffset) Object.assign(o, { scrollTop: el.scrollTop || 0, scrollLeft: el.scrollLeft || 0, scrollHeight: el.scrollHeight || 0, scrollWidth: el.scrollWidth || 0 });
  if (f.node) { if (el.tagName === 'CANVAS') o.node = canvasNode(el); else if (el.__tag === 'scroll-view') o.node = { scrollTo: function (opt) { el.scrollTo({ top: opt.top, left: opt.left, behavior: opt.animated ? 'smooth' : 'auto' }); }, scrollIntoView: function () {} }; else o.node = el; }
  if (f.computedStyle) { var cs = getComputedStyle(el); f.computedStyle.forEach(function (k) { o[k] = cs.getPropertyValue(k.replace(/[A-Z]/g, function (c) { return '-' + c.toLowerCase(); })); }); }
  if (f.properties && el.__inst) f.properties.forEach(function (k) { o[k] = el.__inst.data[k]; });
  return o;
}
SelectorQuery.prototype.exec = function (cb) {
  var self = this;
  rRAF(function () {
    var res = self.q.map(function (item) {
      var sel = item.ref.sel, v;
      if (sel === '__viewport') v = describe('__viewport', item.f);
      else {
        var s2 = sel.replace(/>>>/g, ' ');
        var list = []; try { list = item.ref.all ? Array.prototype.slice.call(self.root.querySelectorAll(s2)) : [self.root.querySelector(s2)].filter(Boolean); } catch (e) {}
        v = item.ref.all ? list.map(function (el) { return describe(el, item.f); }) : (list[0] ? describe(list[0], item.f) : null);
      }
      if (item.cb) { try { item.cb(v); } catch (e) { console.error('sq cb', e); } }
      return v;
    });
    if (cb) { try { cb(res); } catch (e) { console.error('sq exec', e); } }
  });
  return this;
};
function IObserver(comp, opts) { this.comp = comp; this.opts = opts || {}; this.obs = []; this.margins = null; this.rootSel = null; }
IObserver.prototype.relativeToViewport = function (m) { this.margins = m || {}; return this; };
IObserver.prototype.relativeTo = function (sel, m) { this.rootSel = sel; this.margins = m || {}; return this; };
IObserver.prototype.observe = function (sel, cb) {
  var root = this.comp ? this.comp.__root : MP.curPage.__root; var self = this;
  var targets = []; try { targets = this.opts.observeAll ? Array.prototype.slice.call(root.querySelectorAll(sel)) : [root.querySelector(sel)].filter(Boolean); } catch (e) {}
  var m = this.margins || {};
  var rootEl = this.rootSel ? root.querySelector(this.rootSel) : MP.screenEl;
  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (en) { var p = MP.screenRect(), s = MP.scale; var r = en.boundingClientRect;
      cb({ intersectionRatio: en.intersectionRatio, id: en.target.id, dataset: en.target.__ds || {}, time: Date.now(), boundingClientRect: { left: (r.left - p.left) / s, top: (r.top - p.top) / s, width: r.width / s, height: r.height / s, bottom: (r.bottom - p.top) / s, right: (r.right - p.left) / s }, intersectionRect: {}, relativeRect: {} }); });
  }, { root: rootEl, rootMargin: [m.top || 0, m.right || 0, m.bottom || 0, m.left || 0].map(function (x) { return (x * MP.scale) + 'px'; }).join(' '), threshold: this.opts.thresholds || [0] });
  targets.forEach(function (t) { io.observe(t); });
  this.obs.push(io);
  return this;
};
IObserver.prototype.disconnect = function () { this.obs.forEach(function (o) { o.disconnect(); }); this.obs = []; };

// ---------------- app ----------------
var app = null, appDef = null;
window.App = function (def) { appDef = def; };
function getApp() { return app; }
MP.getApp = getApp;
MP.bootApp = function (launch) {
  req('app.js');
  app = MP.app = appDef || {};
  app.globalData = app.globalData || {};
  try { app.onLaunch && app.onLaunch(launch); } catch (e) { console.error('App.onLaunch', e); MP.log.push('App.onLaunch ' + e.message); }
  try { app.onShow && app.onShow(launch); } catch (e) { console.error('App.onShow', e); }
  return app;
};

// ---------------- wx ----------------
var storage = {};
var DEV = window.__DEVICE || { w: 390, h: 844, sb: 47 };
var SYS = { windowWidth: DEV.w, windowHeight: DEV.h, screenWidth: DEV.w, screenHeight: DEV.h, statusBarHeight: DEV.sb, pixelRatio: Math.min(3, window.devicePixelRatio || 2), platform: 'ios', model: 'iPhone 14', system: 'iOS 17.5', brand: 'iPhone', SDKVersion: '3.6.0', version: '8.0.53', language: 'zh_CN', theme: 'dark', safeArea: { top: 47, left: 0, right: 390, bottom: 810, width: 390, height: 763 }, screenTop: 0, benchmarkLevel: 40, enableDebug: false, fontSizeSetting: 17 };
function ok(o, res) { rST(function () { if (o && o.success) o.success(res || { errMsg: 'ok' }); if (o && o.complete) o.complete(res || {}); }, 0); return Promise.resolve(res || {}); }
function audioCtx() {
  var a = new Audio(); a.preload = 'auto';
  var L = { play: [], pause: [], ended: [], error: [], timeupdate: [], canplay: [], waiting: [], stop: [], seeked: [], seeking: [] };
  function emit(t, arg) { (L[t] || []).slice().forEach(function (f) { try { f(arg); } catch (e) {} }); }
  a.addEventListener('play', function () { emit('play'); }); a.addEventListener('pause', function () { if (!a.__stopping) emit('pause'); });
  a.addEventListener('ended', function () { emit('ended'); }); a.addEventListener('error', function () { emit('error', { errCode: 10001, errMsg: 'error' }); });
  a.addEventListener('timeupdate', function () { emit('timeupdate'); }); a.addEventListener('canplay', function () { emit('canplay'); });
  a.addEventListener('waiting', function () { emit('waiting'); }); a.addEventListener('seeked', function () { emit('seeked'); });
  var ctx = {
    get src() { return a.__src || ''; }, set src(v) { a.__src = v; a.src = assetUrl(v); },
    get duration() { return isFinite(a.duration) ? a.duration : 0; }, get currentTime() { return a.currentTime; },
    get paused() { return a.paused; }, get volume() { return a.volume; }, set volume(v) { a.volume = v; },
    get loop() { return a.loop; }, set loop(v) { a.loop = !!v; }, get autoplay() { return a.autoplay; }, set autoplay(v) { a.autoplay = !!v; if (v) a.play().catch(function () {}); },
    obeyMuteSwitch: false, startTime: 0, playbackRate: 1, buffered: 0,
    play: function () { MP.audioEls.add(a); a.playbackRate = 1; a.play().catch(function (e) { MP.log.push('audio play blocked ' + e.message); }); },
    pause: function () { a.pause(); }, stop: function () { a.__stopping = true; a.pause(); try { a.currentTime = 0; } catch (e) {} a.__stopping = false; emit('stop'); },
    seek: function (t) { try { a.currentTime = t; } catch (e) {} }, destroy: function () { a.pause(); a.removeAttribute('src'); MP.audioEls.delete(a); },
  };
  ['Play', 'Pause', 'Ended', 'Error', 'TimeUpdate', 'Canplay', 'Waiting', 'Stop', 'Seeked', 'Seeking'].forEach(function (n) {
    var t = n.toLowerCase(); ctx['on' + n] = function (f) { L[t].push(f); }; ctx['off' + n] = function (f) { L[t] = f ? L[t].filter(function (x) { return x !== f; }) : []; };
  });
  return ctx;
}
MP.audioEls = new Set();
var recorder = null;
var wxImpl = {
  getSystemInfoSync: function () { return Object.assign({}, SYS); }, getWindowInfo: function () { return Object.assign({}, SYS); },
  getDeviceInfo: function () { return Object.assign({}, SYS); }, getAppBaseInfo: function () { return Object.assign({}, SYS); },
  getSystemInfo: function (o) { return ok(o, Object.assign({}, SYS)); }, getSystemSetting: function () { return { locationEnabled: true, wifiEnabled: true }; },
  getAppAuthorizeSetting: function () { return { cameraAuthorized: 'authorized', locationAuthorized: 'authorized', microphoneAuthorized: 'authorized' }; },
  getMenuButtonBoundingClientRect: function () { return { width: 87, height: 32, top: 51, left: 296, right: 383, bottom: 83 }; },
  getAccountInfoSync: function () { return { miniProgram: { envVersion: 'develop', appId: 'demo' } }; },
  getExtConfigSync: function () { return {}; }, getLaunchOptionsSync: function () { return { scene: 1001, query: {} }; }, getEnterOptionsSync: function () { return { scene: 1001, query: {} }; },
  getStorageSync: function (k) { return k in storage ? clone(storage[k]) : ''; }, setStorageSync: function (k, v) { storage[k] = clone(v); },
  removeStorageSync: function (k) { delete storage[k]; }, clearStorageSync: function () { storage = {}; }, getStorageInfoSync: function () { return { keys: Object.keys(storage), currentSize: 1, limitSize: 10240 }; },
  getStorage: function (o) { if (o.key in storage) return ok(o, { data: clone(storage[o.key]) }); rST(function () { o.fail && o.fail({ errMsg: 'not found' }); }, 0); },
  setStorage: function (o) { storage[o.key] = clone(o.data); return ok(o); }, removeStorage: function (o) { delete storage[o.key]; return ok(o); },
  createSelectorQuery: function () { return new SelectorQuery(MP.curPage ? MP.curPage.__root : document); },
  createIntersectionObserver: function (comp, opts) { return new IObserver(comp && comp.__root ? comp : null, opts); },
  nextTick: function (f) { Promise.resolve().then(function () { rST(f, 0); }); },
  createInnerAudioContext: audioCtx, getBackgroundAudioManager: audioCtx,
  setInnerAudioOption: function (o) { return ok(o); },
  createOffscreenCanvas: function (o) { var c = document.createElement('canvas'); c.width = (o && o.width) || 300; c.height = (o && o.height) || 150; return canvasNode(c); },
  vibrateShort: function (o) { try { navigator.vibrate && navigator.vibrate(12); } catch (e) {} MP.ui.haptic && MP.ui.haptic(); return ok(o); },
  vibrateLong: function (o) { try { navigator.vibrate && navigator.vibrate(300); } catch (e) {} return ok(o); },
  showToast: function (o) { MP.ui.toast(o.title, o.icon, o.duration); return ok(o); }, hideToast: function (o) { MP.ui.hideToast(); return ok(o); },
  showLoading: function (o) { MP.ui.loading(o && o.title); return ok(o); }, hideLoading: function (o) { MP.ui.hideLoading(); return ok(o); },
  showModal: function (o) { MP.ui.modal(o); },
  showActionSheet: function (o) { MP.ui.actionSheet(o); },
  setNavigationBarColor: function (o) { MP.ui.statusColor(o.frontColor); return ok(o); }, setNavigationBarTitle: function (o) { return ok(o); },
  setKeepScreenOn: function (o) { return ok(o); }, setScreenBrightness: function (o) { return ok(o); }, getScreenBrightness: function (o) { return ok(o, { value: 0.6 }); },
  scanCode: function (o) { MP.ui.scan(o); },
  chooseMedia: function (o) { MP.ui.choosePhoto(function (p) { o.success && o.success({ tempFiles: [{ tempFilePath: p, size: 1000, fileType: 'image' }], type: 'image' }); o.complete && o.complete(); }); },
  chooseImage: function (o) { MP.ui.choosePhoto(function (p) { o.success && o.success({ tempFilePaths: [p], tempFiles: [{ path: p }] }); o.complete && o.complete(); }); },
  getLocation: function (o) { var l = MP.location(); return ok(o, { latitude: l.lat, longitude: l.lng, accuracy: 12, speed: 1, altitude: 4 }); },
  startLocationUpdate: function (o) { return ok(o); }, startLocationUpdateBackground: function (o) { return ok(o); }, stopLocationUpdate: function (o) { return ok(o); },
  onLocationChange: function (f) { MP.locListeners.push(f); }, offLocationChange: function (f) { MP.locListeners = MP.locListeners.filter(function (x) { return x !== f; }); },
  getSetting: function (o) { return ok(o, { authSetting: { 'scope.userLocation': true, 'scope.camera': true, 'scope.record': true, 'scope.werun': false, 'scope.writePhotosAlbum': true } }); },
  authorize: function (o) { return ok(o); }, openSetting: function (o) { return ok(o, { authSetting: {} }); },
  getWeRunData: function (o) { rST(function () { o.fail && o.fail({ errMsg: 'getWeRunData:fail auth deny' }); o.complete && o.complete(); }, 0); },
  login: function (o) { return ok(o, { code: 'demo' }); }, checkSession: function (o) { return ok(o); },
  request: function (o) { rST(function () { o.fail && o.fail({ errMsg: 'request:fail demo offline' }); o.complete && o.complete({}); }, 20); return { abort: function () {} }; },
  uploadFile: function (o) { rST(function () { o.fail && o.fail({ errMsg: 'uploadFile:fail demo' }); o.complete && o.complete(); }, 20); return { abort: function () {}, onProgressUpdate: function () {} }; },
  downloadFile: function (o) { return ok(o, { tempFilePath: o.url, statusCode: 200 }); },
  navigateTo: function (o) { MP.ui.toast('（演示）打开页面 ' + String(o.url || '').split('?')[0]); rST(function () { o.fail && o.fail({ errMsg: 'demo' }); }, 0); },
  redirectTo: function (o) { MP.ui.toast('（演示）跳转'); }, reLaunch: function (o) { MP.ui.toast('（演示）重新进入'); }, switchTab: function (o) { MP.ui.toast('（演示）切换标签'); },
  navigateBack: function (o) { MP.onBack && MP.onBack(); return ok(o); }, exitMiniProgram: function (o) { MP.onBack && MP.onBack(); },
  openLocation: function (o) { MP.ui.toast('打开微信地图：' + (o.name || '')); return ok(o); },
  previewImage: function (o) { MP.ui.preview(o.current || (o.urls || [])[0]); return ok(o); },
  makePhoneCall: function (o) { MP.ui.toast('拨打 ' + o.phoneNumber); }, setClipboardData: function (o) { MP.ui.toast('内容已复制'); return ok(o); },
  canvasToTempFilePath: function (o) { var c = o.canvas; try { return ok(o, { tempFilePath: c.toDataURL('image/png') }); } catch (e) { o.fail && o.fail(e); } },
  saveImageToPhotosAlbum: function (o) { MP.ui.toast('已保存到相册'); return ok(o); },
  loadFontFace: function (o) { var fam = String(o.family || ''); var p = document.fonts && document.fonts.load ? document.fonts.load('16px "' + fam + '"', '城瘾故事') : Promise.resolve([]); p.then(function (list) { if (list && list.length) { o.success && o.success({ status: 'loaded' }); } else { o.fail && o.fail({ errMsg: 'loadFontFace:fail' }); } o.complete && o.complete(); }, function () { o.fail && o.fail({ errMsg: 'loadFontFace:fail' }); o.complete && o.complete(); }); },
  canIUse: function () { return true; }, isVKSupport: function () { return false; },
  createCameraContext: function () { return { takePhoto: function (o) { MP.ui.choosePhoto(function (p) { o.success && o.success({ tempImagePath: p }); }); }, onCameraFrame: function () { return { start: function () {}, stop: function () {} }; } }; },
  createMapContext: function () { return { moveToLocation: function () {}, includePoints: function () {}, getCenterLocation: function (o) { o && o.success && o.success({ latitude: 31.25, longitude: 121.5 }); }, getScale: function (o) { o && o.success && o.success({ scale: 16 }); }, translateMarker: function () {}, addMarkers: function () {}, removeMarkers: function () {}, moveAlong: function () {}, setBoundary: function () {}, initMarkerCluster: function () {}, on: function () {} }; },
  getRecorderManager: function () {
    if (recorder) return recorder;
    var L = { start: [], stop: [], frame: [], error: [], pause: [], resume: [] }; var timer = null;
    recorder = {
      start: function (opt) { L.start.forEach(function (f) { f(); }); MP.mic.on = true; var fs = (opt && opt.frameSize) || 1; timer = rSI(function () { var buf = MP.mic.frame(); L.frame.forEach(function (f) { f({ frameBuffer: buf, isLastFrame: false }); }); }, 100 / MP.speed); },
      stop: function () { if (timer) rCI(timer); timer = null; MP.mic.on = false; L.stop.forEach(function (f) { f({ tempFilePath: 'wxfile://rec.aac', duration: 1000 }); }); },
      pause: function () {}, resume: function () {},
      onStart: function (f) { L.start.push(f); }, onStop: function (f) { L.stop.push(f); }, onFrameRecorded: function (f) { L.frame.push(f); }, onError: function (f) { L.error.push(f); },
      offStart: function (f) { L.start = L.start.filter(function (x) { return x !== f; }); }, offStop: function (f) { L.stop = L.stop.filter(function (x) { return x !== f; }); },
      offFrameRecorded: function (f) { L.frame = L.frame.filter(function (x) { return x !== f; }); }, offError: function (f) { L.error = L.error.filter(function (x) { return x !== f; }); },
      onPause: function (f) {}, onResume: function (f) {}, onInterruptionBegin: function () {}, onInterruptionEnd: function () {},
    };
    return recorder;
  },
  startAccelerometer: function (o) { return ok(o); }, stopAccelerometer: function (o) { return ok(o); }, onAccelerometerChange: function () {}, offAccelerometerChange: function () {},
  startCompass: function (o) { return ok(o); }, stopCompass: function (o) { return ok(o); }, onCompassChange: function (f) { MP.compassListeners.push(f); }, offCompassChange: function () { MP.compassListeners = []; },
  startDeviceMotionListening: function (o) { return ok(o); }, stopDeviceMotionListening: function (o) { return ok(o); }, onDeviceMotionChange: function () {}, offDeviceMotionChange: function () {},
  onAudioInterruptionBegin: function () {}, onAudioInterruptionEnd: function () {}, offAudioInterruptionBegin: function () {}, offAudioInterruptionEnd: function () {},
  onAppShow: function () {}, onAppHide: function () {}, offAppShow: function () {}, offAppHide: function () {}, onNetworkStatusChange: function () {}, offNetworkStatusChange: function () {},
  getNetworkType: function (o) { return ok(o, { networkType: 'wifi' }); }, onMemoryWarning: function () {}, onKeyboardHeightChange: function () {}, offKeyboardHeightChange: function () {},
  requestSubscribeMessage: function (o) { return ok(o, {}); }, showShareMenu: function (o) { return ok(o); }, hideShareMenu: function (o) { return ok(o); }, updateShareMenu: function (o) { return ok(o); },
  openPrivacyContract: function (o) { return ok(o); }, getPrivacySetting: function (o) { return ok(o, { needAuthorization: false }); }, onNeedPrivacyAuthorization: function () {}, requirePrivacyAuthorize: function (o) { return ok(o); },
  reportAnalytics: function () {}, reportEvent: function () {}, getRealtimeLogManager: function () { return { info: function () {}, warn: function () {}, error: function () {}, setFilterMsg: function () {}, addFilterMsg: function () {} }; },
  getLogManager: function () { return { log: function () {}, info: function () {}, warn: function () {}, debug: function () {} }; }, getPerformance: function () { return { now: function () { return Date.now(); }, createObserver: function () { return { observe: function () {}, disconnect: function () {} }; } }; },
  getFileSystemManager: function () { return { readFile: function (o) { o.fail && o.fail({}); }, writeFile: function (o) { o.success && o.success({}); }, access: function (o) { o.fail && o.fail({}); }, readFileSync: function () { return ''; }, getFileInfo: function (o) { o.success && o.success({ size: 1000 }); }, saveFile: function (o) { o.success && o.success({ savedFilePath: o.tempFilePath }); } }; },
  getImageInfo: function (o) { var im = new Image(); im.onload = function () { o.success && o.success({ width: im.naturalWidth, height: im.naturalHeight, path: o.src }); o.complete && o.complete(); }; im.onerror = function () { o.fail && o.fail({ errMsg: 'fail' }); }; im.src = assetUrl(o.src); },
  compressImage: function (o) { return ok(o, { tempFilePath: o.src }); },
  createAnimation: function () { var steps = [], cur = []; var api = { export: function () { var s = steps.concat(cur.length ? [cur] : []); steps = []; cur = []; return { actions: s }; }, step: function () { steps.push(cur); cur = []; return api; } }; ['opacity', 'translateX', 'translateY', 'translate', 'scale', 'rotate', 'width', 'height', 'backgroundColor', 'top', 'left'].forEach(function (k) { api[k] = function () { cur.push([k, [].slice.call(arguments)]); return api; }; }); return api; },
  env: { USER_DATA_PATH: 'wxfile://usr' }, getXrFrameSystem: function () { return null; },
  pageScrollTo: function (o) { return ok(o); }, startPullDownRefresh: function () {}, stopPullDownRefresh: function () {},
  hideHomeButton: function () {}, hideKeyboard: function () {}, setBackgroundColor: function () {}, setBackgroundTextStyle: function () {},
  onThemeChange: function () {}, offThemeChange: function () {}, onWindowResize: function () {}, offWindowResize: function () {},
};
var wx = MP.wx = new Proxy(wxImpl, { get: function (t, k) { if (k in t) return t[k]; if (typeof k !== 'string') return undefined; MP.unknownWx[k] = (MP.unknownWx[k] || 0) + 1; return function (o) { if (o && typeof o === 'object') rST(function () { o.complete && o.complete({}); }, 0); }; } });
window.wx = wx; window.getApp = getApp; window.getCurrentPages = getCurrentPages;
MP.locListeners = []; MP.compassListeners = [];
MP.mic = { on: false, level: 0.03, frame: function () { var n = 512, b = new Int16Array(n); var lv = MP.mic.level; for (var i = 0; i < n; i++) b[i] = Math.round((Math.random() * 2 - 1) * lv * 32767); return b.buffer; } };
MP.location = function () { return MP.loc || { lat: 31.2555, lng: 121.5115 }; };
})();
