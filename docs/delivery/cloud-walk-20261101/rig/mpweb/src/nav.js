/* Cloud-walk rig: multi-page navigation, real network, and the explicit test seams.
   Loaded after runtime.js + ui.js. Everything injected is tagged [SEAM] and listed in RIG.md. */
(function () {
'use strict';
var MP = window.MPWEB, wx = MP.wx;
var APPJSON = window.__APPJSON || {};
var PROD_API = 'https://www.chengyinhub.com/prod-api';
var CFG = MP.cfg = Object.assign({ envVersion: 'release', identity: '', scanPayload: '', loc: null, heading: 0 }, window.__RIG || {});

// ---------------------------------------------------------------- network log + real wx.request
MP.net = [];
function mapUrl(u) {
  u = String(u || '');
  if (u.indexOf(PROD_API) === 0) return '/__api' + u.slice(PROD_API.length);
  if (u.indexOf('https://www.chengyinhub.com/profile/') === 0) return '/__api/profile/' + u.slice('https://www.chengyinhub.com/profile/'.length);
  return u;
}
MP.mapUrl = mapUrl;
MP.mapRemote = function (u) { return mapUrl(u); };
function encodeForm(d) {
  if (d == null) return '';
  if (typeof d === 'string') return d;
  return Object.keys(d).filter(function (k) { return d[k] !== undefined; }).map(function (k) {
    var v = d[k]; if (v !== null && typeof v === 'object') v = JSON.stringify(v);
    return encodeURIComponent(k) + '=' + encodeURIComponent(v == null ? '' : v);
  }).join('&');
}
function headerGet(h, name) { for (var k in (h || {})) if (k.toLowerCase() === name) return h[k]; return undefined; }
wx.request = function (o) {
  o = o || {};
  var method = String(o.method || 'GET').toUpperCase();
  var url = mapUrl(o.url);
  var headers = Object.assign({}, o.header || {});
  var ct = String(headerGet(headers, 'content-type') || 'application/json');
  var body;
  if (method === 'GET' || method === 'HEAD') {
    var qs = typeof o.data === 'string' ? o.data : encodeForm(o.data);
    if (qs) url += (url.indexOf('?') >= 0 ? '&' : '?') + qs;
  } else if (/x-www-form-urlencoded/i.test(ct)) {
    body = encodeForm(o.data);
  } else {
    body = o.data == null ? undefined : (typeof o.data === 'string' ? o.data : JSON.stringify(o.data));
    if (!headerGet(headers, 'content-type')) headers['content-type'] = 'application/json';
  }
  var ctl = new AbortController(); var done = false;
  var entry = { t: Date.now(), method: method, url: url, reqBody: body ? String(body).slice(0, 2000) : '', status: 0, ms: 0, resBody: '' };
  MP.net.push(entry);
  var t0 = performance.now();
  var tm = setTimeout(function () { if (!done) { ctl.abort(); } }, o.timeout || 60000);
  fetch(url, { method: method, headers: headers, body: body, signal: ctl.signal }).then(function (r) {
    return r.text().then(function (txt) {
      done = true; clearTimeout(tm);
      var data = txt; if (o.dataType !== 'text') { try { data = JSON.parse(txt); } catch (e) { data = txt; } }
      var hdr = {}; r.headers.forEach(function (v, k) { hdr[k] = v; });
      entry.status = r.status; entry.ms = Math.round(performance.now() - t0); entry.resBody = txt.slice(0, 4000);
      var res = { statusCode: r.status, data: data, header: hdr, cookies: [], errMsg: 'request:ok' };
      try { o.success && o.success(res); } catch (e) { console.error('request success cb', url, e); }
      try { o.complete && o.complete(res); } catch (e) { console.error('request complete cb', e); }
    });
  }).catch(function (e) {
    done = true; clearTimeout(tm);
    entry.status = -1; entry.ms = Math.round(performance.now() - t0); entry.resBody = 'FAIL ' + e.message;
    var res = { errMsg: 'request:fail ' + (ctl.signal.aborted ? 'timeout' : e.message) };
    try { o.fail && o.fail(res); } catch (x) { console.error(x); }
    try { o.complete && o.complete(res); } catch (x) { console.error(x); }
  });
  return { abort: function () { ctl.abort(); }, onHeadersReceived: function () {}, offHeadersReceived: function () {} };
};
// [SEAM] files: a generated JPEG stands in for camera/album; upload itself is real multipart to the backend.
MP.samplePhoto = function () {
  var c = document.createElement('canvas'); c.width = 640; c.height = 480; var g = c.getContext('2d');
  var gr = g.createLinearGradient(0, 0, 640, 480); gr.addColorStop(0, '#3a4f6b'); gr.addColorStop(1, '#c9a46b');
  g.fillStyle = gr; g.fillRect(0, 0, 640, 480); g.fillStyle = 'rgba(255,255,255,.85)'; g.font = 'bold 42px sans-serif';
  g.fillText('rig photo ' + new Date().toISOString().slice(11, 19), 40, 250);
  return c.toDataURL('image/jpeg', 0.8);
};
MP.photoForShot = function () { return MP.samplePhoto(); };
wx.uploadFile = function (o) {
  var url = mapUrl(o.url);
  var entry = { t: Date.now(), method: 'UPLOAD', url: url, reqBody: o.name, status: 0, ms: 0, resBody: '' }; MP.net.push(entry);
  var src = String(o.filePath || '');
  var getBlob = /^data:/.test(src) || /^blob:/.test(src) || /^\//.test(src) || /^mp\//.test(src) ? fetch(/^(data|blob):/.test(src) ? src : MP.assetUrl(src)).then(function (r) { return r.blob(); })
    : fetch(MP.samplePhoto()).then(function (r) { return r.blob(); });
  var task = { abort: function () {}, onProgressUpdate: function () {}, offProgressUpdate: function () {}, onHeadersReceived: function () {} };
  getBlob.then(function (blob) {
    var fd = new FormData(); var fm = o.formData || {};
    for (var k in fm) fd.append(k, fm[k]);
    fd.append(o.name || 'file', blob, 'rig-upload.' + ((blob.type || '').split('/')[1] || 'jpg'));
    var h = Object.assign({}, o.header || {}); for (var hk in h) if (hk.toLowerCase() === 'content-type') delete h[hk];
    return fetch(url, { method: 'POST', headers: h, body: fd });
  }).then(function (r) { return r.text().then(function (t) {
    entry.status = r.status; entry.resBody = t.slice(0, 2000);
    var res = { statusCode: r.status, data: t, errMsg: 'uploadFile:ok' };
    o.success && o.success(res); o.complete && o.complete(res);
  }); }).catch(function (e) { entry.status = -1; entry.resBody = 'FAIL ' + e.message; o.fail && o.fail({ errMsg: 'uploadFile:fail ' + e.message }); o.complete && o.complete({}); });
  return task;
};
wx.downloadFile = function (o) {
  var url = mapUrl(o.url);
  fetch(url).then(function (r) { return r.blob().then(function (b) { var res = { tempFilePath: URL.createObjectURL(b), statusCode: r.status, errMsg: 'downloadFile:ok' }; o.success && o.success(res); o.complete && o.complete(res); }); })
    .catch(function (e) { o.fail && o.fail({ errMsg: 'downloadFile:fail ' + e.message }); o.complete && o.complete({}); });
  return { abort: function () {}, onProgressUpdate: function () {} };
};

// ---------------------------------------------------------------- [SEAM] identity / login
// wx.login returns code "rig-<openid>"; the backend's real /api/login/code exchanges it through the rig's
// fake jscode2session (chengyin.test.wechat-api-base-url, profile "local") and logs in that member.
wx.login = function (o) {
  var id = CFG.identity || ('walk-guest-' + Math.random().toString(36).slice(2, 8));
  var res = { code: 'rig-' + id, errMsg: 'login:ok' };
  setTimeout(function () { o && o.success && o.success(res); o && o.complete && o.complete(res); }, 30);
  return Promise.resolve(res);
};
wx.checkSession = function (o) { setTimeout(function () { o && o.success && o.success({ errMsg: 'checkSession:ok' }); o && o.complete && o.complete({}); }, 0); };
wx.getAccountInfoSync = function () { return { miniProgram: { envVersion: CFG.envVersion, appId: 'wx-rig', version: '' } }; };
// Pre-seed a session before launch: real POST /api/login/code, then the app's own session store applies it.
MP.loginAs = function (openid) {
  CFG.identity = openid;
  return fetch('/__api/api/login/code', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: 'code=' + encodeURIComponent('rig-' + openid) })
    .then(function (r) { return r.json(); }).then(function (body) {
      if (!body || !body.token || !body.data) throw new Error('login/code failed: ' + JSON.stringify(body).slice(0, 200));
      var d = body.data;
      var app = MP.getApp();
      var snap = { authorization: body.token, user_id: d.id, user_type: d.userType, open_id: '', avatar: d.avatar || '', nickname: d.nickname || '', role: d.role };
      if (app && app.getSession && app.getSession().applySnapshot) app.getSession().applySnapshot(snap);
      else for (var k in snap) wx.setStorageSync(k, snap[k]);
      return { id: d.id, role: d.role, userType: d.userType, nickname: d.nickname };
    });
};

// ---------------------------------------------------------------- [SEAM] device: GPS, compass, scan, phone, payment
MP.location = function () { return CFG.loc || { lat: 31.2304, lng: 121.4737 }; };
MP.setLocation = function (lat, lng) {
  CFG.loc = { lat: +lat, lng: +lng };
  var r = { latitude: +lat, longitude: +lng, accuracy: 8, speed: 0.8, altitude: 4, horizontalAccuracy: 8, verticalAccuracy: 8 };
  (MP.locListeners || []).slice().forEach(function (f) { try { f(r); } catch (e) { console.error('onLocationChange', e); } });
};
wx.getLocation = function (o) { var l = MP.location(); var r = { latitude: l.lat, longitude: l.lng, accuracy: 8, speed: 0.8, altitude: 4, horizontalAccuracy: 8, errMsg: 'getLocation:ok' }; setTimeout(function () { o && o.success && o.success(r); o && o.complete && o.complete(r); }, 20); return Promise.resolve(r); };
wx.getFuzzyLocation = wx.getLocation;
wx.chooseLocation = function (o) { var l = MP.location(); var r = { name: '模拟位置', address: '上海市', latitude: l.lat, longitude: l.lng, errMsg: 'chooseLocation:ok' }; setTimeout(function () { o && o.success && o.success(r); o && o.complete && o.complete(r); }, 20); };
MP.setHeading = function (deg) { CFG.heading = +deg; (MP.compassListeners || []).slice().forEach(function (f) { try { f({ direction: +deg, accuracy: 'high' }); } catch (e) {} }); };
setInterval(function () { if ((MP.compassListeners || []).length) MP.setHeading(CFG.heading || 0); }, 500);
wx.scanCode = function (o) {
  var payload = CFG.scanPayload;
  setTimeout(function () {
    if (!payload) { var f = { errMsg: 'scanCode:fail cancel' }; o && o.fail && o.fail(f); o && o.complete && o.complete(f); return; }
    var r = { result: payload, scanType: 'QR_CODE', charSet: 'UTF-8', path: /^\/?pages\//.test(payload) ? payload : '', rawData: btoa(unescape(encodeURIComponent(payload))), errMsg: 'scanCode:ok' };
    o && o.success && o.success(r); o && o.complete && o.complete(r);
  }, 200);
  MP.onSystemPrompt && MP.onSystemPrompt('scan', payload);
};
MP.setScan = function (p) { CFG.scanPayload = p || ''; };
// Payment: the stubbed prepay returns package "prepay_id=e2e-stub"; on wx.requestPayment the rig asks its
// server to run the real POST /api/registration/callback (verified fact + callback, as chain_player.py does).
MP.payDecision = 'success';
wx.requestPayment = function (o) {
  var entry = { t: Date.now(), method: 'PAY', url: '/__rig/pay', reqBody: JSON.stringify({ package: o.package }), status: 0, resBody: '' }; MP.net.push(entry);
  if (MP.payDecision === 'cancel') { setTimeout(function () { var f = { errMsg: 'requestPayment:fail cancel' }; o.fail && o.fail(f); o.complete && o.complete(f); }, 300); return; }
  var uid = (MP.getApp() && MP.getApp().getUserID && MP.getApp().getUserID()) || '';
  fetch('/__rig/pay', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ memberId: uid }) })
    .then(function (r) { return r.json(); }).then(function (j) {
      entry.status = j.ok ? 200 : 500; entry.resBody = JSON.stringify(j).slice(0, 2000);
      if (j.ok) { var r = { errMsg: 'requestPayment:ok' }; o.success && o.success(r); o.complete && o.complete(r); }
      else { var f = { errMsg: 'requestPayment:fail ' + (j.error || '') }; o.fail && o.fail(f); o.complete && o.complete(f); }
    }).catch(function (e) { var f = { errMsg: 'requestPayment:fail ' + e.message }; o.fail && o.fail(f); o.complete && o.complete(f); });
};
wx.requestVirtualPayment = wx.requestPayment;
wx.requestOrderPayment = wx.requestPayment;
wx.chooseMedia = function (o) { var p = MP.samplePhoto(); setTimeout(function () { var r = { tempFiles: [{ tempFilePath: p, size: 40000, fileType: 'image', width: 640, height: 480 }], type: 'image', errMsg: 'chooseMedia:ok' }; o.success && o.success(r); o.complete && o.complete(r); }, 50); };
wx.chooseImage = function (o) { var p = MP.samplePhoto(); setTimeout(function () { var r = { tempFilePaths: [p], tempFiles: [{ path: p, size: 40000 }], errMsg: 'chooseImage:ok' }; o.success && o.success(r); o.complete && o.complete(r); }, 50); };
wx.getImageInfo = function (o) { var im = new Image(); im.onload = function () { var r = { width: im.naturalWidth, height: im.naturalHeight, path: o.src, type: 'jpeg', orientation: 'up' }; o.success && o.success(r); o.complete && o.complete(r); }; im.onerror = function () { var f = { errMsg: 'getImageInfo:fail' }; o.fail && o.fail(f); o.complete && o.complete(f); }; im.src = /^data:|^blob:/.test(o.src) ? o.src : MP.assetUrl(o.src); };
wx.cropImage = function (o) { setTimeout(function () { o.success && o.success({ tempFilePath: o.src }); o.complete && o.complete({}); }, 20); };
wx.getUserProfile = function (o) { setTimeout(function () { var r = { userInfo: { nickName: '走查用户', avatarUrl: '' }, errMsg: 'getUserProfile:ok' }; o.success && o.success(r); o.complete && o.complete(r); }, 20); };

// ---------------------------------------------------------------- navigation stack
var tabList = ((APPJSON.tabBar || {}).list || []).map(function (t) { return t.pagePath; });
var tabPages = {};
var stackEl = null;
function stack() { return MP.pageStackRef(); }
function parse(url, from) {
  url = String(url || ''); var qi = url.indexOf('?');
  var p = qi >= 0 ? url.slice(0, qi) : url, qs = qi >= 0 ? url.slice(qi + 1) : '';
  if (p[0] !== '/') { var base = (from || '').split('/').slice(0, -1); p.split('/').forEach(function (s) { if (s === '..') base.pop(); else if (s !== '.' && s) base.push(s); }); p = base.join('/'); }
  else p = p.slice(1);
  var q = {}; qs.split('&').filter(Boolean).forEach(function (kv) { var i = kv.indexOf('='); var k = i >= 0 ? kv.slice(0, i) : kv; var v = i >= 0 ? kv.slice(i + 1) : ''; try { q[decodeURIComponent(k)] = decodeURIComponent(v); } catch (e) { q[k] = v; } });
  return { path: p.replace(/\.html$/, ''), query: q };
}
function exists(path) { return !!(window.__C && __C[path] && __C[path].page); }
function cur() { var s = stack(); return s[s.length - 1]; }
function pageConf(path) { return (__C[path] && __C[path].json) || {}; }
function navBar(page) {
  var conf = pageConf(page.__path); var win = APPJSON.window || {};
  var custom = (conf.navigationStyle || win.navigationStyle) === 'custom';
  var bar = document.getElementById('navBar');
  if (!bar) return;
  bar.hidden = custom;
  document.getElementById('pages').classList.toggle('has-nav', !custom);
  if (!custom) {
    var bg = conf.navigationBarBackgroundColor || win.navigationBarBackgroundColor || '#000';
    var fg = (conf.navigationBarTextStyle || win.navigationBarTextStyle) === 'black' ? '#000' : '#fff';
    bar.style.background = bg; bar.style.color = fg;
    document.getElementById('navTitle').textContent = page.__title != null ? page.__title : (conf.navigationBarTitleText != null ? conf.navigationBarTitleText : (win.navigationBarTitleText || ''));
    var back = document.getElementById('navBack'); back.hidden = stack().length <= 1;
  }
  var sb = document.getElementById('statusBar');
  if (sb) sb.style.color = custom ? '' : ((conf.navigationBarTextStyle || win.navigationBarTextStyle) === 'black' ? '#000' : '#fff');
}
MP.refreshNav = function () { var p = cur(); if (p) navBar(p); };
function hide(page) { if (!page) return; page.__host.style.display = 'none'; try { page.onHide && page.onHide(); } catch (e) { console.error('onHide', e); } if (page.__compPage) page.__m.pl.hide.forEach(function (f) { try { f.call(page); } catch (e) {} }); }
function show(page, first) { page.__host.style.display = ''; MP.curPage = page; navBar(page); if (!first) { try { page.onShow && page.onShow(); } catch (e) { console.error('onShow', e); } if (page.__compPage) page.__m.pl.show.forEach(function (f) { try { f.call(page); } catch (e) {} }); } }
function open(path, query) {
  var page = MP.openPage(path, query, stackEl);
  page.__host.classList.add('mp-pagehost');
  navBar(page);
  MP.emitNav && MP.emitNav('open', path);
  return page;
}
function fail(o, msg) { var f = { errMsg: msg }; console.warn(msg); MP.log.push(msg); setTimeout(function () { o && o.fail && o.fail(f); o && o.complete && o.complete(f); }, 0); return Promise.reject(f); }
function okCb(o, extra) { var r = Object.assign({ errMsg: 'ok' }, extra || {}); setTimeout(function () { o && o.success && o.success(r); o && o.complete && o.complete(r); }, 0); return Promise.resolve(r); }
wx.navigateTo = function (o) {
  var t = parse(o.url, (cur() || {}).__path);
  if (!exists(t.path)) return fail(o, 'navigateTo:fail page "' + t.path + '" is not found');
  if (tabList.indexOf(t.path) >= 0) return fail(o, 'navigateTo:fail can not navigateTo a tabbar page');
  if (stack().length >= 10) return fail(o, 'navigateTo:fail webview count limit exceed');
  var opener = MP.makeChannel(), child = MP.makeChannel(); opener.__pair(child); child.__pair(opener);
  if (o.events) for (var n in o.events) opener.on(n, o.events[n]);
  hide(cur()); MP.__nextChannel = child;
  open(t.path, t.query);
  return okCb(o, { eventChannel: opener });
};
wx.redirectTo = function (o) {
  var t = parse(o.url, (cur() || {}).__path);
  if (!exists(t.path)) return fail(o, 'redirectTo:fail page "' + t.path + '" is not found');
  var c = cur(); if (c) closeAny(c);
  open(t.path, t.query); return okCb(o);
};
function closeAny(p) { if (tabPages[p.__path] === p) delete tabPages[p.__path]; MP.closePage(p); }
wx.switchTab = function (o) {
  var t = parse(o.url, (cur() || {}).__path);
  if (tabList.indexOf(t.path) < 0) return fail(o, 'switchTab:fail can not switch to no-tabBar page');
  stack().slice().reverse().forEach(function (p) { if (tabList.indexOf(p.__path) < 0) closeAny(p); });
  // keep at most the target tab visible; other tab pages stay alive but hidden (as in WeChat)
  stack().forEach(function (p) { if (p.__path !== t.path && p.__host.style.display !== 'none') hide(p); });
  var existing = tabPages[t.path];
  if (existing) { var s = stack(); s.splice(s.indexOf(existing), 1); s.push(existing); show(existing); }
  else tabPages[t.path] = open(t.path, {});
  return okCb(o);
};
wx.reLaunch = function (o) {
  var t = parse(o.url, (cur() || {}).__path);
  if (!exists(t.path)) return fail(o, 'reLaunch:fail page "' + t.path + '" is not found');
  stack().slice().reverse().forEach(closeAny);
  var p = open(t.path, t.query); if (tabList.indexOf(t.path) >= 0) tabPages[t.path] = p;
  return okCb(o);
};
wx.navigateBack = function (o) {
  var delta = Math.max(1, (o && o.delta) || 1);
  var s = stack();
  // only the non-tab pages above the last tab page count as the navigation stack
  if (s.length <= 1) return fail(o, 'navigateBack:fail cannot navigate back at first page.');
  for (var i = 0; i < delta && stack().length > 1; i++) closeAny(cur());
  var c = cur(); if (c) show(c);
  return okCb(o);
};
MP.onBack = function () { wx.navigateBack({}); };
wx.exitMiniProgram = function (o) { MP.log.push('exitMiniProgram'); return okCb(o); };
wx.setNavigationBarTitle = function (o) { var c = cur(); if (c) { c.__title = o.title; navBar(c); } return okCb(o); };
wx.pageScrollTo = function (o) {
  var c = cur(); if (!c) return okCb(o); var host = c.__host;
  if (o.selector) { var el = c.__root.querySelector(o.selector); if (el) host.scrollTo({ top: host.scrollTop + el.getBoundingClientRect().top - host.getBoundingClientRect().top + (o.offsetTop || 0), behavior: o.duration === 0 ? 'auto' : 'smooth' }); }
  else host.scrollTo({ top: o.scrollTop || 0, behavior: o.duration === 0 ? 'auto' : 'smooth' });
  return okCb(o);
};
wx.startPullDownRefresh = function () { var c = cur(); try { c && c.onPullDownRefresh && c.onPullDownRefresh(); } catch (e) {} };
wx.createSelectorQuery = function () { var c = cur(); return c ? c.createSelectorQuery() : null; };
wx.getEnterOptionsSync = function () { return MP.launchOpts || { scene: 1001, query: {}, path: '' }; };
wx.getLaunchOptionsSync = wx.getEnterOptionsSync;
wx.navigateToMiniProgram = function (o) { MP.ui.toast('（rig）打开其他小程序 ' + (o.appId || '')); return fail(o, 'navigateToMiniProgram:fail rig'); };
wx.openEmbeddedMiniProgram = wx.navigateToMiniProgram;
wx.showShareImageMenu = function (o) { MP.ui.toast('（rig）分享图片'); return okCb(o); };
wx.shareAppMessage = function (o) { return okCb(o); };

// ---------------------------------------------------------------- boot
MP.start = function (opts) {
  opts = opts || {};
  stackEl = document.getElementById('pages');
  MP.pageStackRef = MP.pageStackRef || function () { return window.getCurrentPages.__stack(); };
  var entry = parse(opts.path || (APPJSON.entryPagePath || (APPJSON.pages || [])[0]), '');
  MP.launchOpts = { path: entry.path, query: entry.query, scene: opts.scene || 1001, referrerInfo: {} };
  var go = function () {
    // entry page opens in the same tick as onLaunch, so pages the app pushes from onLaunch timers stack above it
    MP.bootApp(MP.launchOpts);
    var p = open(entry.path, entry.query); if (tabList.indexOf(entry.path) >= 0) tabPages[entry.path] = p; return p;
  };
  if (!opts.identity) return Promise.resolve(go());
  // [SEAM] returning user: real POST /api/login/code (fake jscode2session), session cached in storage before launch,
  // exactly the 7 fields session-store persists; app.onLaunch then restores it like a returning user.
  CFG.identity = opts.identity;
  return fetch('/__api/api/login/code', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: 'code=' + encodeURIComponent('rig-' + opts.identity) })
    .then(function (r) { return r.json(); }).then(function (body) {
      if (!body || !body.token || !body.data) throw new Error('login/code failed: ' + JSON.stringify(body).slice(0, 300));
      var d = body.data; MP.identityInfo = d;
      var snap = { authorization: body.token, user_id: d.id, user_type: d.userType, open_id: '', avatar: d.avatar || '', nickname: d.nickname || '', role: d.role };
      for (var k in snap) if (snap[k] !== '' && snap[k] != null) wx.setStorageSync(k, snap[k]);
      return go();
    });
};
MP.tabList = tabList;
})();
/* [SEAM] <button open-type>: getPhoneNumber returns a dummy code (backend exchange with WeChat cannot succeed here);
   chooseAvatar returns a generated image; share/contact/openSetting/feedback just no-op with a log entry. */
(function () {
var MP = window.MPWEB;
MP.phoneSeam = 'ok'; // 'ok' | 'deny'
MP.openTypeSeam = function (ot, el) {
  MP.log.push('open-type ' + ot);
  if (ot === 'getPhoneNumber' || ot === 'getRealtimePhoneNumber') {
    var ev = ot === 'getPhoneNumber' ? 'getphonenumber' : 'getrealtimephonenumber';
    if (MP.phoneSeam === 'deny') return { event: ev, detail: { errMsg: ot + ':fail user deny' } };
    return { event: ev, detail: { errMsg: ot + ':ok', code: 'rig-dummy-phone-code', encryptedData: '', iv: '' } };
  }
  if (ot === 'chooseAvatar') return { event: 'chooseavatar', detail: { avatarUrl: MP.samplePhoto() } };
  if (ot === 'getUserInfo') return { event: 'getuserinfo', detail: { errMsg: 'getUserInfo:ok', userInfo: { nickName: '走查用户', avatarUrl: '' } } };
  if (ot === 'agreePrivacyAuthorization') return { event: 'agreeprivacyauthorization', detail: {} };
  if (ot === 'openSetting') return { event: 'opensetting', detail: { authSetting: {} } };
  return null;
};
})();
/* <picker>: selector / multiSelector via an action sheet, date / time via a prompt-like modal. Values are real
   change events into the page; the native wheel UI itself is not reproduced. */
(function () {
var MP = window.MPWEB;
MP.pickerSeam = function (el) {
  var p = el.__vp || {}; if (p.disabled && p.disabled !== 'false') return;
  var mode = p.mode || 'selector';
  if (mode === 'selector') {
    var range = p.range || []; var key = p['range-key'];
    var items = range.map(function (r) { return key && r && typeof r === 'object' ? String(r[key]) : String(r); });
    MP.ui.actionSheet({ itemList: items, success: function (r) { MP.fire(el, 'change', { value: r.tapIndex }); } });
  } else if (mode === 'multiSelector') {
    MP.fire(el, 'change', { value: (p.value || (p.range || []).map(function () { return 0; })) });
  } else {
    var def = p.value || (mode === 'date' ? new Date().toISOString().slice(0, 10) : mode === 'time' ? '12:00' : '');
    var v = window.prompt ? null : null; // headless: use MP.nextPickerValue set by the driver
    var val = MP.nextPickerValue != null ? MP.nextPickerValue : def; MP.nextPickerValue = null;
    MP.fire(el, 'change', { value: mode === 'region' ? (Array.isArray(val) ? val : ['上海市', '上海市', '黄浦区']) : val });
  }
};
})();
