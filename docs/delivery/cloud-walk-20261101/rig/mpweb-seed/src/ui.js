/* WeChat system UI inside the phone: status bar, capsule, toast, modal, action sheet, scanner, photo picker, image preview. */
(function () {
var MP = window.MPWEB;
var layer = null;
function el(tag, cls, html) { var e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }
function L() { return layer || (layer = document.getElementById('sysLayer')); }
var ui = MP.ui = {};
var toastEl = null, toastT = 0;
ui.toast = function (title, icon, dur) {
  if (!title) return;
  if (toastEl) toastEl.remove();
  toastEl = el('div', 'wx-toast' + (icon === 'success' ? ' is-success' : ''), (icon === 'success' ? '<i class="wx-toast__ok"></i>' : '') + '<span></span>');
  toastEl.lastChild.textContent = title;
  L().appendChild(toastEl);
  clearTimeout(toastT); toastT = setTimeout(ui.hideToast, dur || 1800);
};
ui.hideToast = function () { if (toastEl) { toastEl.remove(); toastEl = null; } };
var loadEl = null;
ui.loading = function (t) { ui.hideLoading(); loadEl = el('div', 'wx-toast is-loading', '<i class="wx-spin"></i><span></span>'); loadEl.lastChild.textContent = t || '加载中'; L().appendChild(loadEl); };
ui.hideLoading = function () { if (loadEl) { loadEl.remove(); loadEl = null; } };
ui.modal = function (o) {
  var m = el('div', 'wx-mask');
  var box = el('div', 'wx-modal');
  if (o.title) { var t = el('div', 'wx-modal__t'); t.textContent = o.title; box.appendChild(t); }
  if (o.content) { var c = el('div', 'wx-modal__c'); c.textContent = o.content; box.appendChild(c); }
  var row = el('div', 'wx-modal__b');
  function done(confirm) { m.remove(); var r = { confirm: confirm, cancel: !confirm, errMsg: 'showModal:ok' }; o.success && o.success(r); o.complete && o.complete(r); }
  if (o.showCancel !== false) { var cb = el('button', 'wx-modal__btn'); cb.textContent = o.cancelText || '取消'; cb.onclick = function () { done(false); }; row.appendChild(cb); }
  var ok = el('button', 'wx-modal__btn is-ok'); ok.textContent = o.confirmText || '确定'; ok.style.color = o.confirmColor || ''; ok.onclick = function () { done(true); }; row.appendChild(ok);
  box.appendChild(row); m.appendChild(box); L().appendChild(m);
  MP.onSystemPrompt && MP.onSystemPrompt('modal', o.title || o.content);
};
ui.actionSheet = function (o) {
  var m = el('div', 'wx-mask wx-mask--sheet');
  var sh = el('div', 'wx-as');
  (o.itemList || []).forEach(function (t, i) { var b = el('button', 'wx-as__i'); b.textContent = t; b.onclick = function () { m.remove(); o.success && o.success({ tapIndex: i }); o.complete && o.complete(); }; sh.appendChild(b); });
  var c = el('button', 'wx-as__i wx-as__c'); c.textContent = '取消'; c.onclick = function () { m.remove(); o.fail && o.fail({ errMsg: 'showActionSheet:fail cancel' }); }; sh.appendChild(c);
  m.appendChild(sh); L().appendChild(m);
};
ui.statusColor = function (c) { var sb = document.getElementById('statusBar'); if (sb) sb.classList.toggle('is-dark', String(c).toLowerCase() === '#000000'); var cap = document.getElementById('capsule'); if (cap) cap.classList.toggle('is-dark', String(c).toLowerCase() === '#000000'); };
// Pseudo-QR (deterministic from text); real scanning is simulated
function qrSvg(text, size) {
  var n = 25, h = 0; for (var i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) >>> 0;
  function rnd() { h ^= h << 13; h >>>= 0; h ^= h >>> 17; h ^= h << 5; h >>>= 0; return h / 4294967296; }
  var cells = '';
  function finder(x, y) { return '<rect x="' + x + '" y="' + y + '" width="7" height="7" fill="#111"/><rect x="' + (x + 1) + '" y="' + (y + 1) + '" width="5" height="5" fill="#fff"/><rect x="' + (x + 2) + '" y="' + (y + 2) + '" width="3" height="3" fill="#111"/>'; }
  for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) {
    var inF = (x < 8 && y < 8) || (x > n - 9 && y < 8) || (x < 8 && y > n - 9);
    if (!inF && rnd() > 0.52) cells += '<rect x="' + x + '" y="' + y + '" width="1" height="1" fill="#111"/>';
  }
  return '<svg viewBox="-2 -2 ' + (n + 4) + ' ' + (n + 4) + '" width="' + size + '" height="' + size + '" shape-rendering="crispEdges"><rect x="-2" y="-2" width="' + (n + 4) + '" height="' + (n + 4) + '" fill="#fff"/>' + cells + finder(0, 0) + finder(n - 7, 0) + finder(0, n - 7) + '</svg>';
}
ui.qrSvg = qrSvg;
ui.scan = function (o) {
  var target = MP.scanTarget ? MP.scanTarget() : { code: 'CY-DEMO', label: '门店码', photo: '' };
  var m = el('div', 'wx-scan');
  m.innerHTML = '<div class="wx-scan__cam" style="background-image:url(' + (target.photo || '') + ')"></div>' +
    '<div class="wx-scan__shade"></div>' +
    '<div class="wx-scan__qr"><div class="wx-scan__stand">' + qrSvg(target.code, 132) + '<b></b></div></div>' +
    '<div class="wx-scan__line"></div>' +
    '<div class="wx-scan__top"><button class="wx-scan__x" aria-label="关闭">‹</button><span>扫一扫</span><i></i></div>' +
    '<div class="wx-scan__tip">对准二维码，即可自动扫描</div>' +
    '<div class="wx-scan__bar"><span><i class="wx-scan__ic">✦</i>轻触照亮</span><span><i class="wx-scan__ic">▣</i>相册</span></div>';
  m.querySelector('.wx-scan__stand b').textContent = target.label;
  L().appendChild(m);
  var settled = false;
  m.querySelector('.wx-scan__x').onclick = function () { if (settled) return; settled = true; m.remove(); o.fail && o.fail({ errMsg: 'scanCode:fail cancel' }); o.complete && o.complete(); };
  setTimeout(function () { m.classList.add('is-lock'); }, 1500);
  setTimeout(function () {
    if (settled) return; settled = true;
    try { navigator.vibrate && navigator.vibrate(30); } catch (e) {}
    m.classList.add('is-hit');
    setTimeout(function () { m.remove(); var r = { result: target.code, scanType: 'QR_CODE', charSet: 'UTF-8', errMsg: 'scanCode:ok' }; o.success && o.success(r); o.complete && o.complete(r); }, 420);
  }, 2300);
  MP.onSystemPrompt && MP.onSystemPrompt('scan', target.code);
};
ui.choosePhoto = function (cb) {
  ui.actionSheet({ itemList: ['拍摄', '从手机相册选择'], success: function () {
    var shot = el('div', 'wx-shutter'); L().appendChild(shot);
    setTimeout(function () { shot.remove(); cb(MP.photoForShot ? MP.photoForShot() : ''); }, 520);
  } });
};
ui.preview = function (src) {
  var m = el('div', 'wx-preview'); var im = el('img'); im.src = MP.assetUrl(src); m.appendChild(im);
  m.onclick = function () { m.remove(); }; L().appendChild(m);
};
ui.haptic = function () {};
})();
